package com.example.PIDAL

import android.content.Context
import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.PIDAL.databinding.ActivityLoginBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("PIDAL_SESSION", Context.MODE_PRIVATE)
        val isLoggedIn = sharedPref.getBoolean("isLoggedIn", false)
        val lastActivity = sharedPref.getLong("lastActivity", 0L)
        val username = sharedPref.getString("username", null)

        if (isLoggedIn && username != null) {
            val currentTime = System.currentTimeMillis()
            val threeDaysMs = 3 * 24 * 60 * 60 * 1000L
            
            if (currentTime - lastActivity > threeDaysMs) {
                sharedPref.edit().clear().apply()
            } else {
                sharedPref.edit().putLong("lastActivity", currentTime).apply()
                val intent = Intent(this, LoadingActivity::class.java)
                intent.putExtra("USERNAME", username)
                startActivity(intent)
                finish()
                return
            }
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTextGradient(binding.tvAccountNameLabel)
        applyTextGradient(binding.tvPasswordLabel)
        applyTextGradient(binding.btnSignup)
        applyTextGradient(binding.tvTagline)

        binding.btnLogin.setOnClickListener {
            val inputUser = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (inputUser.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter both credentials", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Checking credentials...", Toast.LENGTH_SHORT).show()
            checkLogin(inputUser, password)
        }

        binding.btnSignup.setOnClickListener {
            val intent = Intent(this, SignupActivity::class.java)
            startActivity(intent)
        }
    }

    private fun applyTextGradient(textView: TextView) {
        val paint = textView.paint
        val width = paint.measureText(textView.text.toString())
        val purple = ContextCompat.getColor(this, R.color.brand_purple)
        val gold = ContextCompat.getColor(this, R.color.brand_gold)
        val textShader: Shader = LinearGradient(0f, 0f, width, textView.textSize, intArrayOf(purple, gold), null, Shader.TileMode.CLAMP)
        textView.paint.shader = textShader
        textView.invalidate()
    }

    private fun checkLogin(inputName: String, inputPass: String) {
        val databaseUrl = "https://pidal-92106-default-rtdb.asia-southeast1.firebasedatabase.app/"
        val database = FirebaseDatabase.getInstance(databaseUrl).getReference("Accounts")
        
        database.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var foundUserKey = ""
                var foundPassword = ""
                
                // Search through each user node in Accounts (e.g., Noah, Janrick Kayne)
                for (userSnapshot in snapshot.children) {
                    if (userSnapshot.hasChild(inputName)) {
                        foundUserKey = userSnapshot.key ?: ""
                        foundPassword = userSnapshot.child(inputName).value?.toString() ?: ""
                        break
                    }
                }

                if (foundUserKey.isNotEmpty()) {
                    if (foundPassword == inputPass) {
                        // SUCCESS
                        val sharedPref = getSharedPreferences("PIDAL_SESSION", Context.MODE_PRIVATE)
                        sharedPref.edit().apply {
                            putBoolean("isLoggedIn", true)
                            putString("username", foundUserKey)
                            putLong("lastActivity", System.currentTimeMillis())
                            apply()
                        }

                        Toast.makeText(this@LoginActivity, "Login Successful!", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@LoginActivity, LoadingActivity::class.java)
                        intent.putExtra("USERNAME", foundUserKey)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "Incorrect Password", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@LoginActivity, "Account with number $inputName does not exist", Toast.LENGTH_LONG).show()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@LoginActivity, "Firebase Error: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }
}
