package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * App khulte hi sabse pehle yeh screen dikhti hai — ek hi jagah se saari
 * zaroori permissions maang leta hai (calls, SMS, mic, camera, location,
 * notifications, storage) taaki baad me feature-by-feature popups na aaye.
 */
class PermissionsActivity : AppCompatActivity() {

    private val allPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.VIBRATE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
                perms += Manifest.permission.READ_MEDIA_IMAGES
                perms += Manifest.permission.READ_MEDIA_VIDEO
                perms += Manifest.permission.READ_MEDIA_AUDIO
            } else {
                perms += Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return perms.toTypedArray()
        }

    private val requestPermsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* result ignored — user MainActivity me continue kar sakta hai even agar kuch deny ho */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        findViewById<android.widget.Button>(R.id.btnGrantAll).setOnClickListener {
            requestPermsLauncher.launch(allPermissions)
        }

        findViewById<android.widget.Button>(R.id.btnBattery).setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        findViewById<android.widget.Button>(R.id.btnContinue).setOnClickListener {
            val serviceIntent = Intent(this, JarvisService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Kuch OEMs (MIUI/ColorOS) is intent ko block karte hain —
                // fail-silent, user ko manually Settings me jaake karna hoga
            }
        }
    }
}
