package com.example.PIDAL

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.example.PIDAL.databinding.ActivityTopUpBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class TopUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopUpBinding
    private var currentBalance: Double = 0.0
    private var selectedAmount: Double = 0.0
    private var selectedCredits: Double = 0.0
    private lateinit var username: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTopUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        username = intent.getStringExtra("USERNAME") ?: "Noah"

        fetchUserBalance()

        binding.btnBack.setOnClickListener { finish() }

        // Setup Package Card Clicks
        binding.card21.setOnClickListener {
            updateInputFromSelection(20.0, 21.0)
        }

        binding.card105.setOnClickListener {
            updateInputFromSelection(100.0, 105.0)
        }

        // Setup Dynamic Text Input Listener
        binding.etPaymentInput.addTextChangedListener { text ->
            val input = text.toString().toDoubleOrNull() ?: 0.0
            if (selectedAmount != input) { // Avoid loop if triggered by code
                selectedAmount = input
                // Logic: 1 bonus credit for every 20 PHP (Calculated as float)
                val bonus = selectedAmount / 20.0
                selectedCredits = selectedAmount + bonus
                
                binding.tvBonusDisplay.text = String.format("(+%.2f Bonus Credits)", bonus)
            }
        }

        binding.btnPay.setOnClickListener {
            if (selectedAmount <= 0.0) {
                Toast.makeText(this, "Please select or enter an amount!", Toast.LENGTH_SHORT).show()
            } else {
                processTopUp()
            }
        }
    }

    private fun updateInputFromSelection(amount: Double, credits: Double) {
        selectedAmount = amount
        selectedCredits = credits
        binding.etPaymentInput.setText(String.format("%.2f", amount))
        // Bonus calculation for display (e.g. 21 - 20 = 1.00)
        val bonus = (credits - amount)
        binding.tvBonusDisplay.text = String.format("(+%.2f Bonus Credits)", bonus)
    }

    private fun fetchUserBalance() {
        val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"
        // UPDATED: Points to Accounts/{Username}/credit_balance
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(username).child("credit_balance")

        dbRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                currentBalance = (snapshot.value as? Number)?.toDouble() ?: 0.0
                binding.tvCurrentBalance.text = String.format("%.2f", currentBalance)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@TopUpActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun processTopUp() {
        val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"
        // UPDATED: Points to Accounts/{Username}/credit_balance
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(username).child("credit_balance")

        val newBalance = currentBalance + selectedCredits
        dbRef.setValue(newBalance).addOnSuccessListener {
            Toast.makeText(this, "Top Up Successful!", Toast.LENGTH_LONG).show()
            selectedAmount = 0.0
            selectedCredits = 0.0
            binding.etPaymentInput.setText("0.00")
            binding.tvBonusDisplay.text = "(+0.00 Bonus Credits)"
        }.addOnFailureListener {
            Toast.makeText(this, "Top Up Failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
