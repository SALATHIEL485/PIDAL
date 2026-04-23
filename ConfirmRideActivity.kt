package com.example.PIDAL

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.PIDAL.databinding.ActivityConfirmRideBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

class ConfirmRideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmRideBinding
    private var currentUsername: String = "Noah"
    private val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmRideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentUsername = intent.getStringExtra("USERNAME") ?: "Noah"
        val bikeId = intent.getStringExtra("BIKE_ID") ?: "PDL-2601"
        val stationName = intent.getStringExtra("STATION_NAME") ?: "MAIN GATE"

        // Display basic bike and station info
        binding.tvBikeId.text = bikeId.substringAfter("-")
        binding.tvTerminalLocation.text = "$stationName STATION"

        fetchChargeRates()
        fetchUserCredits(currentUsername)

        binding.btnUnlock.setOnClickListener {
            unlockBike(bikeId)
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun unlockBike(bikeId: String) {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference()

        dbRef.child("Stations").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var foundStation = ""
                for (stationSnapshot in snapshot.children) {
                    if (stationSnapshot.hasChild(bikeId)) {
                        foundStation = stationSnapshot.key ?: ""
                        break
                    }
                }

                if (foundStation.isNotEmpty()) {
                    // 1. Mark bike as unavailable (false) in the station
                    dbRef.child("Stations").child(foundStation).child(bikeId).setValue(false)
                    
                    // 2. Update User Node under Accounts: Status, CurrentBike, and RideStartTime
                    val updates = mapOf(
                        "Status" to "unlocked_a_bike",
                        "CurrentBike" to bikeId,
                        "RideStartTime" to ServerValue.TIMESTAMP
                    )
                    
                    dbRef.child("Accounts").child(currentUsername).updateChildren(updates)
                        .addOnSuccessListener {
                            Toast.makeText(this@ConfirmRideActivity, "Bike Unlocked!", Toast.LENGTH_SHORT).show()
                            
                            // Go back to Main with updated state
                            val intent = Intent(this@ConfirmRideActivity, MainActivity::class.java)
                            intent.putExtra("USERNAME", currentUsername)
                            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            startActivity(intent)
                            finish()
                        }
                } else {
                    Toast.makeText(this@ConfirmRideActivity, "Error finding bike in stations", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun fetchChargeRates() {
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Charge")
        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val initial = snapshot.child("Initial").value?.toString() ?: "0"
                    val succeeding = snapshot.child("AddAfter").value?.toString() ?: "0"
                    
                    binding.tvCharge.text = initial
                    binding.tvSucceedingRateValue.text = succeeding
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun fetchUserCredits(userName: String) {
        // UPDATED: Points to Accounts/{Username}/credit_balance
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(userName).child("credit_balance")
        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val balance = (snapshot.value as? Number)?.toDouble() ?: 0.0
                    binding.tvUserCredits.text = String.format("%.2f", balance)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
