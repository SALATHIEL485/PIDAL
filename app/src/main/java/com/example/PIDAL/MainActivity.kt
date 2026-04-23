package com.example.PIDAL

import android.Manifest
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.example.PIDAL.databinding.ActivityMainBinding
import com.example.PIDAL.databinding.DialogConfirmStopBinding
import com.example.PIDAL.databinding.LayoutActiveRideBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var activeRideBinding: LayoutActiveRideBinding
    
    private var locationOverlay: MyLocationNewOverlay? = null
    private var userMarker: Marker? = null
    
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var locationManager: LocationManager

    private var isMenuOpen = false
    private var currentUsername: String = "Noah"
    private val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"

    // MAP AUTO-CENTERING STATE
    private var autoFollowUser = true
    private val recenterHandler = Handler(Looper.getMainLooper())
    private val recenterRunnable = Runnable {
        autoFollowUser = true
        lastLocation?.let { 
            centerMapOnPointWithOffset(GeoPoint(it.latitude, it.longitude))
        }
    }

    // RIDE TRACKING STATE
    private var isRiding = false
    private var startTimeMillis: Long = 0
    private var totalDistanceMeters: Double = 0.0
    private var lastLocation: Location? = null
    private var lastTrackLocation: Location? = null
    private var rideTrackPolyline: Polyline? = null
    private var rideTimerHandler = Handler(Looper.getMainLooper())
    private var currentBikeId: String = ""

    // CHARGE RATES
    private var initialCharge: Double = 0.0
    private var succeedingRate: Double = 0.0

    // DYNAMIC STATION DATA
    private val stationMarkers = mutableMapOf<String, Marker>()
    private val stationRadii = mutableMapOf<String, Polygon>()
    private val stationCoords = mutableMapOf<String, GeoPoint>()
    private val stationBikeCounts = mutableMapOf<String, Int>()
    private var stationRadius: Double = 30.0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            checkLocationSettings()
        } else {
            Toast.makeText(this, "Permissions required for location", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeRideBinding = binding.layoutActiveRide
        currentUsername = intent.getStringExtra("USERNAME") ?: "Noah"

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        setupMenuToggle()
        setupScannerButton()
        setupProfileButton()
        setupTopUpButton()
        
        fetchRealTimeDashboardData()
        fetchRealTimeBikeCount() 
        fetchChargeRates()
        fetchGlobalValues()

        if (hasPermissions()) {
            checkLocationSettings()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        
        activeRideBinding.btnEndRide.setOnClickListener {
            handleEndRideClick()
        }
    }

    private fun fetchGlobalValues() {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("values")
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val r = snapshot.child("radius_meter").value
                    stationRadius = (r as? Number)?.toDouble() ?: 30.0
                    updateStationOverlays() 
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun handleEndRideClick() {
        val lastKnown = lastLocation
        if (lastKnown == null) {
            Toast.makeText(this, "WAITING FOR GPS SIGNAL...", Toast.LENGTH_SHORT).show()
            return
        }

        var nearestDist = Double.MAX_VALUE
        var nearestStationId = ""
        if (stationCoords.isNotEmpty()) {
            for ((id, stationPos) in stationCoords) {
                val results = FloatArray(1)
                Location.distanceBetween(lastKnown.latitude, lastKnown.longitude, stationPos.latitude, stationPos.longitude, results)
                if (results[0] < nearestDist) {
                    nearestDist = results[0].toDouble()
                    nearestStationId = id
                }
            }
        }

        showConfirmStopOverlay(nearestDist, nearestStationId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showConfirmStopOverlay(initialDistance: Double, initialStationId: String) {
        val dialogBinding = DialogConfirmStopBinding.inflate(layoutInflater)
        val builder = AlertDialog.Builder(this, R.style.TransparentDialog)
        builder.setView(dialogBinding.root)
        val dialog = builder.create()

        var currentDist = initialDistance
        var currentNearestStationId = initialStationId

        val dialogLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                var minDist = Double.MAX_VALUE
                var minStationId = ""
                if (stationCoords.isNotEmpty()) {
                    for ((id, stationPos) in stationCoords) {
                        val results = FloatArray(1)
                        Location.distanceBetween(location.latitude, location.longitude, stationPos.latitude, stationPos.longitude, results)
                        if (results[0] < minDist) {
                            minDist = results[0].toDouble()
                            minStationId = id
                        }
                    }
                }
                currentDist = minDist
                currentNearestStationId = minStationId
                runOnUiThread {
                    if (currentDist == Double.MAX_VALUE) {
                        dialogBinding.tvNearestStationDistance.text = "Searching..."
                    } else {
                        dialogBinding.tvNearestStationDistance.text = if (currentDist >= 1000) String.format("%.1f KM", currentDist / 1000.0) else String.format("%.0f M", currentDist)
                    }
                    
                    if (currentDist <= stationRadius) {
                        dialogBinding.lottieConfirmAnim.alpha = 1.0f
                        if (!dialogBinding.lottieConfirmAnim.isAnimating && dialogBinding.lottieConfirmAnim.progress == 0f) {
                            dialogBinding.lottieConfirmAnim.playAnimation()
                        }
                    } else {
                        dialogBinding.lottieConfirmAnim.apply {
                            alpha = 0.4f
                            isClickable = false
                            pauseAnimation()
                            progress = 0f
                        }
                    }
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        dialogBinding.lottieConfirmAnim.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                if (currentDist <= stationRadius) {
                    dialogBinding.lottieConfirmAnim.isClickable = true
                }
            }
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
        })

        if (currentDist == Double.MAX_VALUE) {
            dialogBinding.tvNearestStationDistance.text = "Searching..."
        } else {
            dialogBinding.tvNearestStationDistance.text = if (currentDist >= 1000) String.format("%.1f KM", currentDist / 1000.0) else String.format("%.0f M", currentDist)
        }

        dialogBinding.lottieConfirmAnim.apply {
            setAnimation(R.raw.confirm_stop)
            repeatCount = 0
            if (currentDist <= stationRadius) {
                alpha = 1.0f
                playAnimation()
            } else {
                alpha = 0.4f
                isClickable = false
                progress = 0f
            }
            
            setOnClickListener {
                if (currentDist <= stationRadius && currentNearestStationId.isNotEmpty()) {
                    locationManager.removeUpdates(dialogLocationListener)
                    dialog.dismiss()
                    
                    val timeStr = activeRideBinding.tvRideTime.text.toString()
                    val distStr = activeRideBinding.tvActiveDistance.text.toString() + " " + activeRideBinding.tvActiveDistanceUnit.text.toString()
                    val paceStr = activeRideBinding.tvActivePace.text.toString()
                    
                    stopRide(currentNearestStationId, timeStr, distStr, paceStr)
                }
            }
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, dialogLocationListener)
        } catch (e: SecurityException) {}

        dialog.setOnDismissListener {
            locationManager.removeUpdates(dialogLocationListener)
        }

        dialog.show()
    }

    private fun fetchChargeRates() {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Charge")
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    initialCharge = (snapshot.child("Initial").value as? Number)?.toDouble() ?: 0.0
                    succeedingRate = (snapshot.child("AddAfter").value as? Number)?.toDouble() ?: 0.0
                    if (isRiding) updateActiveChargeDisplay()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startRide(bikeId: String, savedStartTime: Long) {
        if (isRiding) return
        isRiding = true
        startTimeMillis = savedStartTime
        totalDistanceMeters = 0.0
        lastTrackLocation = null
        currentBikeId = bikeId
        
        binding.rideCard.visibility = View.GONE
        binding.btnQR.visibility = View.GONE
        activeRideBinding.root.visibility = View.VISIBLE
        activeRideBinding.tvActiveBikeId.text = bikeId
        
        rideTrackPolyline = Polyline()
        rideTrackPolyline?.outlinePaint?.color = Color.parseColor("#FFD700")
        rideTrackPolyline?.outlinePaint?.strokeWidth = 10f
        binding.mapView.overlays.add(rideTrackPolyline)

        val trackRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(currentUsername).child("RideTrack")
        trackRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    var prevLoc: Location? = null
                    rideTrackPolyline?.setPoints(emptyList()) 
                    for (pointSnapshot in snapshot.children) {
                        val lat = pointSnapshot.child("lat").getValue(Double::class.java)
                        val lng = pointSnapshot.child("lng").getValue(Double::class.java)
                        if (lat != null && lng != null) {
                            val gp = GeoPoint(lat, lng)
                            rideTrackPolyline?.addPoint(gp)
                            
                            val currLoc = Location("").apply {
                                latitude = lat
                                longitude = lng
                            }
                            if (prevLoc != null) {
                                totalDistanceMeters += prevLoc!!.distanceTo(currLoc)
                            }
                            prevLoc = currLoc
                            lastTrackLocation = currLoc 
                        }
                    }
                    updateDistanceDisplay(totalDistanceMeters)
                    binding.mapView.invalidate()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        
        startRideTimer()
    }

    private fun stopRide(targetStationId: String, time: String, dist: String, pace: String) {
        val rootRef = FirebaseDatabase.getInstance(databaseUrl).getReference()
        val userRef = rootRef.child("Accounts").child(currentUsername)

        // 1. Fetch current balance to subtract the charge
        userRef.child("credit_balance").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentBalance = (snapshot.value as? Number)?.toDouble() ?: 0.0
                
                // Calculate charges
                val base = initialCharge
                val elapsedMillis = System.currentTimeMillis() - startTimeMillis
                val totalSeconds = Math.max(0, elapsedMillis / 1000)
                val blockInSeconds = 12 * 3600
                val overtime = if (totalSeconds < blockInSeconds) 0.0 else ((totalSeconds / blockInSeconds).toInt() * succeedingRate)
                val total = base + overtime
                val newBalance = currentBalance - total

                // 2. Perform updates
                rootRef.child("Stations").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(statSnapshot: DataSnapshot) {
                        val updates = mutableMapOf<String, Any?>()
                        
                        for (stationSnapshot in statSnapshot.children) {
                            if (stationSnapshot.hasChild(currentBikeId)) {
                                val sid = stationSnapshot.key ?: continue
                                updates["Stations/$sid/$currentBikeId"] = null
                            }
                        }
                        
                        updates["Stations/$targetStationId/$currentBikeId"] = true
                        updates["Accounts/$currentUsername/Status"] = "not_unlock_a_bike"
                        updates["Accounts/$currentUsername/RideStartTime"] = null
                        updates["Accounts/$currentUsername/RideTrack"] = null
                        updates["Accounts/$currentUsername/CurrentBike"] = null
                        updates["Accounts/$currentUsername/credit_balance"] = newBalance

                        rootRef.updateChildren(updates).addOnSuccessListener {
                            // COLLECT TRACK POINTS
                            val trackPoints = ArrayList<Double>()
                            rideTrackPolyline?.actualPoints?.forEach { 
                                trackPoints.add(it.latitude)
                                trackPoints.add(it.longitude)
                            }

                            // REDIRECT TO SUMMARY
                            val summaryIntent = Intent(this@MainActivity, RideSummaryActivity::class.java)
                            summaryIntent.putExtra("RIDE_TIME", time)
                            summaryIntent.putExtra("DISTANCE", dist)
                            summaryIntent.putExtra("PACE", pace)
                            summaryIntent.putExtra("BIKE_ID", currentBikeId)
                            summaryIntent.putExtra("STATION", targetStationId)
                            summaryIntent.putExtra("USER_TAG", binding.tvAccountTag.text.toString())
                            summaryIntent.putExtra("BASE_CHARGE", String.format("%.2f", base))
                            summaryIntent.putExtra("OVERTIME_CHARGE", String.format("%.2f", overtime))
                            summaryIntent.putExtra("TOTAL_CHARGE", String.format("%.2f", total))
                            summaryIntent.putExtra("USERNAME", currentUsername)
                            summaryIntent.putExtra("TRACK_POINTS", trackPoints)
                            startActivity(summaryIntent)

                            isRiding = false
                            rideTimerHandler.removeCallbacksAndMessages(null)
                            binding.rideCard.visibility = View.VISIBLE
                            binding.btnQR.visibility = View.VISIBLE
                            activeRideBinding.root.visibility = View.GONE
                            
                            rideTrackPolyline?.let { binding.mapView.overlays.remove(it) }
                            rideTrackPolyline = null
                            lastLocation?.let { updateNearestStationDisplay(it) }
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startRideTimer() {
        rideTimerHandler.post(object : Runnable {
            override fun run() {
                if (!isRiding) return
                val elapsed = System.currentTimeMillis() - startTimeMillis
                val totalSeconds = Math.max(0, elapsed / 1000)
                val seconds = totalSeconds % 60
                val minutes = (totalSeconds / 60) % 60
                val hours = (totalSeconds / 3600)
                
                activeRideBinding.tvRideTime.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                updateActiveChargeDisplay()
                rideTimerHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun updateActiveChargeDisplay() {
        val elapsedMillis = System.currentTimeMillis() - startTimeMillis
        val totalSeconds = Math.max(0, elapsedMillis / 1000)
        val blockInSeconds = 12 * 3600
        val currentCharge = if (totalSeconds < blockInSeconds) initialCharge else initialCharge + ((totalSeconds / blockInSeconds).toInt() * succeedingRate)
        activeRideBinding.tvActiveCharge.text = "CHARGE: ${String.format("%.0f", currentCharge)}"
    }

    private fun fetchRealTimeDashboardData() {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(currentUsername)
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val fullNameRaw = snapshot.child("full-name").value
                    val fullName = if (fullNameRaw is String) fullNameRaw else currentUsername
                    
                    val balance = (snapshot.child("credit_balance").value as? Number)?.toDouble() ?: 0.0
                    val status = snapshot.child("Status").getValue(String::class.java) ?: "not_unlock_a_bike"
                    val savedStartTime = (snapshot.child("RideStartTime").value as? Number)?.toLong() ?: System.currentTimeMillis()

                    if (status == "unlocked_a_bike") {
                        val bikeId = snapshot.child("CurrentBike").getValue(String::class.java) ?: "PDL-2601"
                        currentBikeId = bikeId
                        startRide(bikeId, savedStartTime)
                    } else if (isRiding) {
                        isRiding = false
                        rideTimerHandler.removeCallbacksAndMessages(null)
                        binding.rideCard.visibility = View.VISIBLE
                        binding.btnQR.visibility = View.VISIBLE
                        activeRideBinding.root.visibility = View.GONE
                        rideTrackPolyline?.let { binding.mapView.overlays.remove(it) }
                        rideTrackPolyline = null
                    }

                    val masked = maskName(fullName)
                    binding.tvAccountTag.text = masked
                    activeRideBinding.tvActiveUserTag.text = masked
                    binding.tvMainCreditsDisplay.text = String.format("%.2f", balance)
                    activeRideBinding.tvActiveCredits.text = "CREDITS: ${String.format("%.2f", balance)}"
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun maskName(name: String): String {
        if (name.isBlank()) return name
        val parts = name.trim().split("\\s+".toRegex())
        val maskedParts = parts.map { part ->
            val normalized = part.lowercase().replaceFirstChar { it.uppercase() }
            when {
                normalized.length <= 2 -> normalized
                normalized.length <= 4 -> normalized.substring(0, 2) + "*".repeat(normalized.length - 2)
                else -> normalized.substring(0, 2) + "*".repeat(normalized.length - 3) + normalized.substring(normalized.length - 1)
            }
        }
        return maskedParts.joinToString(" ")
    }

    private fun fetchRealTimeBikeCount() {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Stations")
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var totalCount = 0
                val newCoords = mutableMapOf<String, GeoPoint>()
                val newBikeCounts = mutableMapOf<String, Int>()
                
                for (stationSnapshot in snapshot.children) {
                    val stationId = stationSnapshot.key ?: continue
                    val coordsSnapshot = stationSnapshot.child("coords")
                    val lat = (coordsSnapshot.child("lat").value as? Number)?.toDouble()
                    val lng = (coordsSnapshot.child("long").value as? Number)?.toDouble()
                    
                    if (lat != null && lng != null) {
                        newCoords[stationId] = GeoPoint(lat, lng)
                    }
                    
                    var stationBikeCount = 0
                    for (bikeSnapshot in stationSnapshot.children) {
                        if (bikeSnapshot.key?.startsWith("PDL-") == true) {
                            val isAvailable = bikeSnapshot.getValue(Boolean::class.java) ?: false
                            if (isAvailable) stationBikeCount++
                        }
                    }
                    newBikeCounts[stationId] = stationBikeCount
                    totalCount += stationBikeCount
                }
                
                stationCoords.clear()
                stationCoords.putAll(newCoords)
                stationBikeCounts.clear()
                stationBikeCounts.putAll(newBikeCounts)
                
                binding.tvMainBikesDisplay.text = "BIKES: $totalCount"
                updateStationOverlays() 
                lastLocation?.let { updateNearestStationDisplay(it) }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateStationOverlays() {
        val mapView = binding.mapView
        if (mapView == null) return
        
        stationMarkers.values.forEach { mapView.overlays.remove(it) }
        stationRadii.values.forEach { mapView.overlays.remove(it) }
        stationMarkers.clear()
        stationRadii.clear()

        stationCoords.forEach { (id, pos) ->
            val circle = Polygon(mapView)
            circle.points = Polygon.pointsAsCircle(pos, stationRadius)
            circle.fillPaint.color = Color.parseColor("#338B5CF6")
            circle.outlinePaint.color = Color.parseColor("#8B5CF6")
            circle.outlinePaint.strokeWidth = 2f
            stationRadii[id] = circle
            mapView.overlays.add(circle)

            val marker = Marker(mapView)
            marker.position = pos
            val pinDrawable = ContextCompat.getDrawable(this, R.drawable.pin_station)
            marker.icon = if (pinDrawable != null) scaleDrawable(pinDrawable, 32, 32) else null
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            val formattedName = when(id) {
                "BSU-AL" -> "BatStateU Alangilan"
                "BSU-PB" -> "BatStateU Pablo Borbon"
                "GRAND-T" -> "Grand Terminal"
                else -> id
            }
            
            val bikeCount = stationBikeCounts[id] ?: 0
            marker.title = "$formattedName\nStation"
            marker.snippet = "bikes available: $bikeCount"
            stationMarkers[id] = marker
            mapView.overlays.add(marker)
        }
        
        locationOverlay?.let { 
            mapView.overlays.remove(it)
            mapView.overlays.add(it)
        }
        userMarker?.let {
            mapView.overlays.remove(it)
            mapView.overlays.add(it)
        }
        
        mapView.invalidate()
    }

    private fun setupTopUpButton() { binding.btnTopUp.setOnClickListener { startActivity(Intent(this, TopUpActivity::class.java).apply { putExtra("USERNAME", currentUsername) }) } }
    private fun setupScannerButton() { binding.btnQR.setOnClickListener { startActivity(Intent(this, ScannerActivity::class.java).apply { putExtra("USERNAME", currentUsername) }) } }
    private fun setupProfileButton() { binding.btnProfile.setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java).apply { putExtra("USERNAME", currentUsername) }) } }

    private fun setupMenuToggle() {
        binding.btnMenu.setOnClickListener {
            isMenuOpen = !isMenuOpen
            TransitionManager.beginDelayedTransition(binding.rideCard, TransitionSet().addTransition(ChangeBounds()).setDuration(400))
            binding.ivMenuIcon.animate().rotation(if (isMenuOpen) 180f else 0f).setDuration(400).start()
            val visibility = if (isMenuOpen) View.GONE else View.VISIBLE
            binding.tvAccountTag.visibility = visibility
            binding.llStatsRow.visibility = visibility
            binding.llButtonsRow.visibility = visibility
            binding.tvScanQrLabel.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
            binding.vCollapsedSpacer.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
            listOf(binding.btnSub1, binding.btnSub2, binding.btnSub3).forEach { it.visibility = if (isMenuOpen) View.VISIBLE else View.GONE }
        }
    }

    private fun hasPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun checkLocationSettings() { if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) else initMap() }

    @SuppressLint("ClickableViewAccessibility")
    private fun initMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMultiTouchControls(true)
            
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        autoFollowUser = false
                        recenterHandler.removeCallbacks(recenterRunnable)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        recenterHandler.postDelayed(recenterRunnable, 10000) 
                    }
                }
                false 
            }

            val matrix = ColorMatrix()
            matrix.setSaturation(0f)
            getOverlayManager().getTilesOverlay().setColorFilter(ColorMatrixColorFilter(matrix))

            minZoomLevel = 5.5
            val philippinesBox = BoundingBox(22.0, 127.0, 4.0, 115.0)
            setScrollableAreaLimitDouble(philippinesBox)
            controller.setZoom(17.0)

            locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this@MainActivity), this)
            locationOverlay?.enableMyLocation()
            locationOverlay?.setDrawAccuracyEnabled(false) 
            
            val transparentBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            transparentBitmap.setPixel(0, 0, Color.TRANSPARENT)
            locationOverlay?.setPersonIcon(transparentBitmap)
            locationOverlay?.setDirectionIcon(transparentBitmap)
            
            userMarker = Marker(this)
            val arrowDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.arrow)
            userMarker?.icon = if (arrowDrawable != null) scaleDrawable(arrowDrawable, 32, 32) else null
            userMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            userMarker?.setInfoWindow(null)

            val lastLoc = try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) { null }
            
            if (lastLoc != null) {
                val gp = GeoPoint(lastLoc.latitude, lastLoc.longitude)
                userMarker?.position = gp
                lastLocation = lastLoc
                postCenterWithOffset(gp) 
                updateNearestStationDisplay(lastLoc)
            } else {
                controller.setCenter(GeoPoint(13.7445, 121.0703))
            }

            if (!overlays.contains(locationOverlay)) overlays.add(locationOverlay)
            if (!overlays.contains(userMarker)) overlays.add(userMarker)
            
            updateStationOverlays() 
        }
        startPassiveTracking()
    }

    private fun postCenterWithOffset(gp: GeoPoint) {
        binding.mapView.post {
            centerMapOnPointWithOffset(gp)
        }
    }

    private fun centerMapOnPointWithOffset(gp: GeoPoint) {
        val mapView = binding.mapView
        val projection = mapView.projection ?: return
        val screenPoint = android.graphics.Point()
        projection.toPixels(gp, screenPoint)
        val centerY = mapView.height / 2
        val targetY = mapView.height / 3
        val offset = centerY - targetY
        val offsetGeoPoint = projection.fromPixels(screenPoint.x, screenPoint.y + offset) as GeoPoint
        mapView.controller.animateTo(offsetGeoPoint)
    }

    private fun scaleDrawable(drawable: Drawable, widthDp: Int, heightDp: Int): Drawable {
        val density = resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, widthPx, heightPx)
        drawable.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    private fun startPassiveTracking() { 
        if (hasPermissions()) {
            try { 
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this) 
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, this) 
            } catch (e: Exception) {
                Log.e("PIDAL", "Tracking error: ${e.message}")
            } 
        } 
    }

    override fun onLocationChanged(location: Location) {
        if (location.accuracy < 150) {
            updateNearestStationDisplay(location)
        }

        if (location.accuracy > 45) return

        val userPos = GeoPoint(location.latitude, location.longitude)
        userMarker?.position = userPos
        if (autoFollowUser) centerMapOnPointWithOffset(userPos)
        if (isRiding) updateRideStats(location)
        lastLocation = location 
        binding.mapView.invalidate()
    }

    private fun updateNearestStationDisplay(location: Location) {
        var nearestDist = Double.MAX_VALUE
        if (stationCoords.isNotEmpty()) {
            stationCoords.values.forEach { pos ->
                val results = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, pos.latitude, pos.longitude, results)
                if (results[0] < nearestDist) nearestDist = results[0].toDouble()
            }
        }
        
        val displayStr = if (nearestDist == Double.MAX_VALUE) {
            "Locating..."
        } else if (nearestDist >= 1000) {
            String.format("%.1f KM", nearestDist / 1000.0)
        } else {
            String.format("%.0f M", nearestDist)
        }
        
        if (!isRiding) {
            binding.tvMainDistanceDisplay.text = displayStr
        }
        if (isRiding) activeRideBinding.tvActiveTotalDistance.text = displayStr
    }

    private fun updateRideStats(location: Location) {
        if (location.accuracy > 30) return
        val lastTrackLoc = lastTrackLocation
        
        if (lastTrackLoc != null && location.distanceTo(lastTrackLoc) >= 3) {
            val newPoint = GeoPoint(location.latitude, location.longitude)
            rideTrackPolyline?.addPoint(newPoint)
            
            // SAVE POINT TO FIREBASE FOR PERSISTENCE - UNDER ACCOUNTS
            val trackRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(currentUsername).child("RideTrack")
            val pointMap = mapOf("lat" to location.latitude, "lng" to location.longitude)
            trackRef.push().setValue(pointMap)

            totalDistanceMeters += lastTrackLoc.distanceTo(location)
            updateDistanceDisplay(totalDistanceMeters)
            updatePaceDisplay(location.speed)
            
            lastTrackLocation = location 
        } else if (lastTrackLoc == null) {
            val newPoint = GeoPoint(location.latitude, location.longitude)
            rideTrackPolyline?.addPoint(newPoint)
            lastTrackLocation = location
        }
    }

    private fun updateDistanceDisplay(meters: Double) {
        if (meters < 100) {
            activeRideBinding.tvActiveDistance.text = String.format("%.0f", meters)
            activeRideBinding.tvActiveDistanceUnit.text = "M"
        } else {
            activeRideBinding.tvActiveDistance.text = String.format("%.1f", meters / 1000.0)
            activeRideBinding.tvActiveDistanceUnit.text = "KM"
        }
    }

    private fun updatePaceDisplay(speedMps: Float) {
        val speedKmh = speedMps * 3.6
        if (speedKmh < 1.0) {
            activeRideBinding.tvActivePace.text = String.format("%.1f", speedMps)
            activeRideBinding.tvActivePaceUnit.text = "M/S"
        } else {
            activeRideBinding.tvActivePace.text = String.format("%.0f", speedKmh)
            activeRideBinding.tvActivePaceUnit.text = "KM/H"
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            userMarker?.rotation = -azimuth
            binding.mapView.invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() { super.onResume() ; binding.mapView.onResume(); rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }; startPassiveTracking() }
    override fun onPause() { super.onPause() ; binding.mapView.onPause(); sensorManager.unregisterListener(this); locationManager.removeUpdates(this) }
}
