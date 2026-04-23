package com.example.PIDAL

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.PIDAL.databinding.ActivityScannerBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private var isProcessing = false
    private var currentUsername: String = "Noah"
    private val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openGallery()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processSelectedImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentUsername = intent.getStringExtra("USERNAME") ?: "Noah"

        cameraExecutor = Executors.newSingleThreadExecutor()
        barcodeScanner = BarcodeScanning.getClient()

        if (allPermissionsGranted(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.ivUploadIcon.setOnClickListener {
            handleUploadClick()
        }

        fetchUserCredits()
    }

    private fun fetchUserCredits() {
        // UPDATED: Points to Accounts/{Username}/credit_balance
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(currentUsername).child("credit_balance")
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val balance = (snapshot.value as? Number)?.toDouble() ?: 0.0
                    binding.tvScannerCreditsDisplay.text = String.format("%.2f", balance)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun handleUploadClick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (allPermissionsGranted(permission)) {
            openGallery()
        } else {
            requestStoragePermissionLauncher.launch(permission)
        }
    }

    private fun openGallery() {
        pickImageLauncher.launch("image/*")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("Scanner", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val result = barcode.displayValue
                        if (result != null && result.startsWith("bike-id:")) {
                            isProcessing = true
                            runOnUiThread {
                                val parsedId = result.substringAfter("bike-id:")
                                findBikeInStations(parsedId)
                            }
                            break
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processSelectedImage(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            
            val image = InputImage.fromBitmap(bitmap, 0)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val result = barcodes[0].displayValue
                        if (result != null && result.startsWith("bike-id:")) {
                            val parsedId = result.substringAfter("bike-id:")
                            findBikeInStations(parsedId)
                        } else {
                            Toast.makeText(this, "Invalid QR Format", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this, "No QR code found in image", Toast.LENGTH_SHORT).show()
                    }
                }
        } catch (e: Exception) {
            Log.e("Scanner", "Error processing image", e)
        }
    }

    private fun findBikeInStations(bikeId: String) {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Stations")

        Toast.makeText(this, "Verifying Bike: $bikeId...", Toast.LENGTH_SHORT).show()

        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var found = false
                var stationName = ""
                var isAvailable = false

                // Iterate through each station to find the bike
                for (stationSnapshot in snapshot.children) {
                    if (stationSnapshot.hasChild(bikeId)) {
                        found = true
                        stationName = stationSnapshot.key ?: ""
                        isAvailable = stationSnapshot.child(bikeId).getValue(Boolean::class.java) ?: false
                        break
                    }
                }

                if (found && isAvailable) {
                    // Map station key to full location name
                    val locationLabel = when (stationName) {
                        "BSU-AL" -> "BATSTATEU ALANGILAN"
                        "BSU-PB" -> "BATSTATEU PABLO BORBON"
                        "GRAND-T" -> "GRAND TERMINAL"
                        else -> stationName
                    }

                    val intent = Intent(this@ScannerActivity, ConfirmRideActivity::class.java)
                    intent.putExtra("BIKE_ID", bikeId)
                    intent.putExtra("STATION_NAME", locationLabel)
                    intent.putExtra("USERNAME", currentUsername)
                    startActivity(intent)
                    finish()
                } else if (found && !isAvailable) {
                    isProcessing = false
                    Toast.makeText(this@ScannerActivity, "ERROR: BIKE NOT AVAILABLE", Toast.LENGTH_LONG).show()
                } else {
                    isProcessing = false
                    Toast.makeText(this@ScannerActivity, "ERROR: BIKE NOT FOUND", Toast.LENGTH_LONG).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                isProcessing = false
                Toast.makeText(this@ScannerActivity, "Database Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun allPermissionsGranted(permission: String) = ContextCompat.checkSelfPermission(
        baseContext, permission
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
