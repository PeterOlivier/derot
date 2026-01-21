package com.derot.videoblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Simple setup activity that guides users to enable the accessibility service.
 * After enabling, the app runs entirely in the background.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var settingsButton: Button
    private lateinit var usageButton: Button
    private lateinit var notificationButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIcon = findViewById(R.id.status_icon)
        statusText = findViewById(R.id.status_text)
        settingsButton = findViewById(R.id.btn_open_settings)
        usageButton = findViewById(R.id.btn_usage_access)
        notificationButton = findViewById(R.id.btn_notification_access)

        settingsButton.setOnClickListener {
            openAccessibilitySettings()
        }

        usageButton.setOnClickListener {
            openUsageAccessSettings()
        }

        notificationButton.setOnClickListener {
            openNotificationListenerSettings()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val usageAccessEnabled = isUsageAccessEnabled()

        // Update accessibility button
        if (accessibilityEnabled) {
            statusIcon.setImageResource(android.R.drawable.ic_media_play)
            statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.secondary))
            statusText.text = getString(R.string.status_enabled)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            settingsButton.text = "✓ Accessibility enabled"
            settingsButton.isEnabled = false
        } else {
            statusIcon.setImageResource(android.R.drawable.ic_media_pause)
            statusIcon.setColorFilter(ContextCompat.getColor(this, R.color.error))
            statusText.text = getString(R.string.status_disabled)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
            settingsButton.text = getString(R.string.btn_open_settings)
            settingsButton.isEnabled = true
        }

        // Update usage access button
        if (usageAccessEnabled) {
            usageButton.text = "✓ Usage Access enabled (optional)"
            usageButton.isEnabled = false
        } else {
            usageButton.text = "Grant Usage Access (for better X/Twitter detection)"
            usageButton.isEnabled = true
        }

        // Update notification access button
        val notificationAccessEnabled = isNotificationListenerEnabled()
        if (notificationAccessEnabled) {
            notificationButton.text = "✓ Notification Access enabled (optional)"
            notificationButton.isEnabled = false
        } else {
            notificationButton.text = "Grant Notification Access (for media detection)"
            notificationButton.isEnabled = true
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }

    private fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
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
