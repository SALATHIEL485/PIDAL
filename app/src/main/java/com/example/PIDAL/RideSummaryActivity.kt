package com.example.PIDAL

import android.animation.Animator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.PIDAL.databinding.ActivityRideSummaryBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class RideSummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRideSummaryBinding
    private val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRideSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Get Data from Intent
        val rideTime = intent.getStringExtra("RIDE_TIME") ?: "00:00:00"
        val distance = intent.getStringExtra("DISTANCE") ?: "0.0 km"
        val pace = intent.getStringExtra("PACE") ?: "0"
        val bikeId = intent.getStringExtra("BIKE_ID") ?: "PDL - XXXX"
        val stationId = intent.getStringExtra("STATION") ?: ""
        val userTag = intent.getStringExtra("USER_TAG") ?: "User"
        val baseCharge = intent.getStringExtra("BASE_CHARGE") ?: "0.00"
        val overtimeCharge = intent.getStringExtra("OVERTIME_CHARGE") ?: "0.00"
        val totalCharge = intent.getStringExtra("TOTAL_CHARGE") ?: "0.00"
        val username = intent.getStringExtra("USERNAME") ?: "Noah"
        val trackPoints = intent.getSerializableExtra("TRACK_POINTS") as? ArrayList<Double>

        // Map station ID to display name
        val stationName = when(stationId) {
            "BSU-AL" -> "BSU - ALANGILAN STATION"
            "BSU-PB" -> "BSU - PABLO BORBON STATION"
            "GRAND-T" -> "GRAND TERMINAL STATION"
            else -> stationId
        }

        // 2. Display Data
        binding.tvSummaryTime.text = rideTime
        binding.tvSummaryDistance.text = distance
        binding.tvSummaryPace.text = pace
        binding.tvSummaryBikeId.text = bikeId
        binding.tvSummaryStation.text = stationName
        binding.tvSummaryUser.text = userTag
        binding.tvSummaryBaseCharge.text = baseCharge
        binding.tvSummaryOvertimeCharge.text = overtimeCharge
        binding.tvSummaryTotal.text = "TOTAL: $totalCharge"

        // 3. Map Setup
        setupSummaryMap(trackPoints)

        // 4. Navigation
        binding.btnMenuSummary.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("USERNAME", username)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        binding.btnProfileSummary.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
        }
    }

    private fun setupSummaryMap(trackPoints: ArrayList<Double>?) {
        binding.summaryMapView.apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMultiTouchControls(true)
            
            // Grayscale filter
            val matrix = ColorMatrix()
            matrix.setSaturation(0f)
            getOverlayManager().getTilesOverlay().setColorFilter(ColorMatrixColorFilter(matrix))

            val points = mutableListOf<GeoPoint>()
            if (trackPoints != null && trackPoints.size >= 2) {
                val polyline = Polyline()
                polyline.outlinePaint.color = Color.parseColor("#FFD700") 
                polyline.outlinePaint.strokeWidth = 8f
                
                for (i in 0 until trackPoints.size step 2) {
                    val gp = GeoPoint(trackPoints[i], trackPoints[i+1])
                    points.add(gp)
                    polyline.addPoint(gp)
                }
                overlays.add(polyline)

                // Add User Marker (Arrow) at the last point
                val lastPoint = points.last()
                val userMarker = Marker(this)
                userMarker.position = lastPoint
                val arrowDrawable = ContextCompat.getDrawable(this@RideSummaryActivity, R.drawable.arrow)
                userMarker?.icon = if (arrowDrawable != null) scaleDrawable(arrowDrawable, 24, 24) else null
                userMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                userMarker?.setInfoWindow(null)
                overlays.add(userMarker)

                // Zoom to fit the track
                post {
                    if (points.isNotEmpty()) {
                        val box = BoundingBox.fromGeoPoints(points)
                        zoomToBoundingBox(box.increaseByScale(1.4f), true)
                    }
                }
            } else {
                controller.setZoom(16.0)
                controller.setCenter(GeoPoint(13.7544, 121.0533))
            }

            // Add Station Pins
            fetchStationPins()
        }
    }

    private fun fetchStationPins() {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Stations")
        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (stationSnapshot in snapshot.children) {
                    val coords = stationSnapshot.child("coords")
                    val lat = (coords.child("lat").value as? Number)?.toDouble()
                    val lng = (coords.child("long").value as? Number)?.toDouble()
                    if (lat != null && lng != null) {
                        val marker = Marker(binding.summaryMapView)
                        marker.position = GeoPoint(lat, lng)
                        val pinDrawable = ContextCompat.getDrawable(this@RideSummaryActivity, R.drawable.pin_station)
                        marker.icon = if (pinDrawable != null) scaleDrawable(pinDrawable, 24, 24) else null
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        marker.setInfoWindow(null)
                        binding.summaryMapView.overlays.add(marker)
                    }
                }
                binding.summaryMapView.invalidate()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
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

    override fun onResume() { super.onResume(); binding.summaryMapView.onResume() }
    override fun onPause() { super.onPause(); binding.summaryMapView.onPause() }
}
