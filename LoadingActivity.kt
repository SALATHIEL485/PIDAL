package com.example.PIDAL

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.PIDAL.databinding.ActivityLoadingBinding

class LoadingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoadingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoadingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val username = intent.getStringExtra("USERNAME") ?: "Noah"

        // Animation duration set to exactly 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("USERNAME", username)
            startActivity(intent)
            finish()
        }, 5000)
    }
}
