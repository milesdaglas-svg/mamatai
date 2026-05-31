package com.mamatai.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.mamatai.R
import com.mamatai.ui.admin.AdminActivity
import com.mamatai.util.DataStore

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        DataStore.init(applicationContext)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, AdminActivity::class.java))
            finish()
        }, 1800)
    }
}
