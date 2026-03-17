package com.github.wrager.sbguserscripts

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// Временный мост до появления SplashActivity (фаза 5)
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, GameActivity::class.java))
        finish()
    }
}
