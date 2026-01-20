package com.derot.videoblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Simple setup activity that guides users to enable the accessibility service.
 * After enabling, the app runs entirely in the background.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var settingsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIcon = findViewById(R.id.status_icon)
        statusText = findViewById(R.id.status_text)
        settingsButton = findViewById(R.id.btn_open_settings)

        settingsButton.setOnClickListener {
            openAccessibilitySettings()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            statusIcon.setImageResource(android.R.drawable.ic_media_play)
            statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.secondary))
            statusText.text = getString(R.string.status_enabled)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            settingsButton.text = "Service is running - you can close this app"
            settingsButton.isEnabled = false
        } else {
            statusIcon.setImageResource(android.R.drawable.ic_media_pause)
            statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.error))
            statusText.text = getString(R.string.status_disabled)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
            settingsButton.text = getString(R.string.btn_open_settings)
            settingsButton.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

        return enabledServices.any { serviceInfo ->
            serviceInfo.resolveInfo.serviceInfo.packageName == packageName &&
            serviceInfo.resolveInfo.serviceInfo.name == VideoFeedBlockerService::class.java.name
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
