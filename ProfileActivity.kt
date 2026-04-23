package com.example.PIDAL

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.PIDAL.databinding.ActivityProfileBinding
import com.example.PIDAL.databinding.ItemMenuProfileBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val username = intent.getStringExtra("USERNAME") ?: "Noah"

        setupMenuItems()
        fetchUserData(username)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnLogout.setOnClickListener {
            val sharedPref = getSharedPreferences("PIDAL_SESSION", Context.MODE_PRIVATE)
            sharedPref.edit().clear().apply()

            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMenuItems() {
        ItemMenuProfileBinding.bind(binding.menuCredits.root).menuTitle.text = "My Credits"
        ItemMenuProfileBinding.bind(binding.menuAccounts.root).menuTitle.text = "My Linked Accounts"
        ItemMenuProfileBinding.bind(binding.menuTransactions.root).menuTitle.text = "Transactions"
        ItemMenuProfileBinding.bind(binding.menuPrivacy.root).menuTitle.text = "Privacy"
        ItemMenuProfileBinding.bind(binding.menuPromos.root).menuTitle.text = "Promos"
    }

    private fun fetchUserData(username: String) {
        val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"
        // UPDATED: Now correctly points to Accounts/{Username}
        val dbRef = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts").child(username)

        dbRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val fullName = snapshot.child("full-name").getValue(String::class.java) ?: username
                    val verified = snapshot.child("Verified").getValue(Boolean::class.java) ?: false
                    
                    // NEW LOGIC: Find the mobile number key (the one paired with the password)
                    var mobileNum = ""
                    for (child in snapshot.children) {
                        val key = child.key ?: ""
                        // The mobile number is the key that is numeric and doesn't match other field names
                        if (key.all { it.isDigit() } && key.length >= 10) {
                            mobileNum = key
                            break
                        }
                    }

                    binding.tvFullName.text = fullName
                    binding.tvMobileNum.text = if (mobileNum.startsWith("0")) mobileNum else "0$mobileNum"
                    
                    val initials = fullName.split(" ")
                        .filter { it.isNotEmpty() }
                        .take(2)
                        .map { it[0] }
                        .joinToString("")
                    binding.tvAvatar.text = initials.uppercase()

                    if (verified) {
                        binding.llVerified.visibility = View.VISIBLE
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@ProfileActivity, "Error fetching profile: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
