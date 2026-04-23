package com.example.PIDAL

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.PIDAL.databinding.ActivitySignupBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            registerUser()
        }

        binding.btnBackToLogin.setOnClickListener {
            finish()
        }
    }

    private fun registerUser() {
        val firstName = binding.etFirstName.text.toString().trim()
        val middleName = binding.etMiddleInitial.text.toString().trim()
        val lastName = binding.etLastName.text.toString().trim()
        val mobile = binding.etMobileNumber.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (firstName.isEmpty() || lastName.isEmpty() || mobile.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        // Use First Name as the key under Accounts (matching your provided structure)
        val userKey = firstName 
        val fullName = "$firstName $middleName $lastName".replace("\\s+".toRegex(), " ").trim()

        val database = FirebaseDatabase.getInstance(databaseUrl).reference

        // 1. Check if phone number or username already exists under Accounts
        database.child("Accounts").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var phoneExists = false
                for (userSnapshot in snapshot.children) {
                    if (userSnapshot.hasChild(mobile)) {
                        phoneExists = true
                        break
                    }
                }

                if (phoneExists) {
                    Toast.makeText(this@SignupActivity, "ERROR: ACCOUNT WITH THIS NUMBER ALREADY EXISTS", Toast.LENGTH_LONG).show()
                } else if (snapshot.hasChild(userKey)) {
                    Toast.makeText(this@SignupActivity, "ERROR: USERNAME ALREADY EXISTS", Toast.LENGTH_LONG).show()
                } else {
                    // 2. Add New Account and Details
                    val userData = mapOf(
                        mobile to password,
                        "full-name" to fullName,
                        "mobile-num" to mobile.toLongOrNull(),
                        "credit_balance" to 0,
                        "Status" to "not_unlock_a_bike",
                        "Verified" to false
                    )

                    database.child("Accounts").child(userKey).setValue(userData).addOnSuccessListener {
                        Toast.makeText(this@SignupActivity, "Registration Successful!", Toast.LENGTH_SHORT).show()
                        finish()
                    }.addOnFailureListener {
                        Toast.makeText(this@SignupActivity, "Registration Failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@SignupActivity, "Database Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
