package com.derot.videoblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * Accessibility service that detects and blocks short-form video feeds.
 *
 * IMPORTANT: This is designed to be CONSERVATIVE - it only blocks when we're
 * very confident the user is in an infinite video feed, not just watching
 * a single video.
 *
 * Detection strategy:
 * 1. Only monitor specific known apps (X, Instagram, YouTube, TikTok, etc.)
 * 2. Look for very specific UI elements that indicate a VIDEO FEED (not just any video)
 * 3. Only block after detecting a SWIPE to the next video (proves it's a feed)
 * 4. Never block on first video - let user watch what they clicked on
 */
class VideoFeedBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "DerotBlocker"
        private const val CHANNEL_ID = "derot_service"
        private const val NOTIFICATION_ID = 1
        private const val BLOCKED_NOTIFICATION_ID = 2

        // Cooldown to prevent rapid back presses
        private const val BLOCK_COOLDOWN_MS = 3000L

        // How many video swipes before we block (2 = block on second video)
        private const val SWIPES_BEFORE_BLOCK = 2

        private fun logDebug(message: String) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, message)
            }
        }

        private fun logInfo(message: String) {
            if (BuildConfig.DEBUG) {
                Log.i(TAG, message)
            }
        }

        private fun logWarn(message: String) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, message)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockTime = 0L
    private var currentApp = ""
    private var isInVideoFeed = false
    private var videoSwipeCount = 0
    private var lastVideoContentHash = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        logInfo("Derot service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        createNotificationChannel()
        startForegroundNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        if (isSystemPackage(packageName)) return

        // Track app changes - reset state when switching apps
        if (currentApp != packageName) {
            currentApp = packageName
            resetState()
            logDebug("App changed to: $packageName")
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkForVideoFeed(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isInVideoFeed) {
                    detectVideoSwipe(event, packageName)
                }
            }
        }
    }

    private fun checkForVideoFeed(packageName: String) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val inFeed = when (packageName) {
                "com.twitter.android", "com.twitter.android.lite" ->
                    isInTwitterVideoFeed(rootNode)
                "com.instagram.android" ->
                    isInInstagramReels(rootNode)
                "com.google.android.youtube" ->
                    isInYouTubeShorts(rootNode)
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" ->
                    isInTikTokFeed(rootNode)
                "com.facebook.katana", "com.facebook.lite" ->
                    isInFacebookReels(rootNode)
                "com.snapchat.android" ->
                    isInSnapchatSpotlight(rootNode)
                else -> false  // Don't block unknown apps
            }

            if (inFeed && !isInVideoFeed) {
                // Just entered a video feed
                isInVideoFeed = true
                videoSwipeCount = 1  // First video
                lastVideoContentHash = 0
                logDebug("Entered video feed in $packageName (watching first video)")
            } else if (!inFeed && isInVideoFeed) {
                // Left the video feed
                resetState()
                logDebug("Left video feed in $packageName")
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun detectVideoSwipe(event: AccessibilityEvent, packageName: String) {
        // Detect content change that indicates swipe to next video
        val contentHash = event.contentDescription?.hashCode()
            ?: event.text?.toString()?.hashCode()
            ?: return

        if (contentHash != 0 && contentHash != lastVideoContentHash) {
            lastVideoContentHash = contentHash
            videoSwipeCount++
            logDebug("Video swipe detected in $packageName (count: $videoSwipeCount)")

            if (videoSwipeCount >= SWIPES_BEFORE_BLOCK) {
                blockVideoFeed(packageName)
            }
        }
    }

    /**
     * Twitter/X: Only block when in the immersive full-screen video feed,
     * NOT when watching a video in a tweet.
     */
    private fun isInTwitterVideoFeed(root: AccessibilityNodeInfo): Boolean {
        // Look specifically for the immersive video viewer (the swipeable feed)
        // This appears when you tap into a video and can swipe up/down for more
        val immersiveFeedIndicators = listOf(
            "com.twitter.android:id/immersive_player_container",
            "com.twitter.android:id/immersive_video_pager"
        )

        for (indicator in immersiveFeedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    /**
     * Instagram: Only block when in Reels, not when viewing a video in feed/stories
     */
    private fun isInInstagramReels(root: AccessibilityNodeInfo): Boolean {
        // These are specific to the Reels viewer
        val reelsIndicators = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/reel_viewer_view_pager"
        )

        for (indicator in reelsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                logDebug("Instagram: In Reels viewer")
                return true
            }
        }
        return false
    }

    /**
     * YouTube: Only block Shorts, not regular videos
     */
    private fun isInYouTubeShorts(root: AccessibilityNodeInfo): Boolean {
        // Specific to Shorts player
        val shortsIndicators = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/shorts_player_container"
        )

        for (indicator in shortsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                logDebug("YouTube: In Shorts")
                return true
            }
        }
        return false
    }

    /**
     * TikTok: Block the main For You feed, but allow profile browsing
     */
    private fun isInTikTokFeed(root: AccessibilityNodeInfo): Boolean {
        // Check if we're on the main feed (not profile, search, etc.)
        val feedIndicators = listOf(
            "com.zhiliaoapp.musically:id/rl",  // Main feed container
            "com.ss.android.ugc.trill:id/rl"
        )

        // If these are visible, we're NOT in the feed
        val nonFeedIndicators = listOf(
            "com.zhiliaoapp.musically:id/profile_fragment",
            "com.zhiliaoapp.musically:id/search_bar",
            "com.ss.android.ugc.trill:id/profile_fragment"
        )

        for (indicator in nonFeedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return false
            }
        }

        for (indicator in feedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    /**
     * Facebook: Only block Reels, not regular video posts
     */
    private fun isInFacebookReels(root: AccessibilityNodeInfo): Boolean {
        val reelsIndicators = listOf(
            "com.facebook.katana:id/reels_viewer_fragment",
            "com.facebook.katana:id/reels_surface_view",
            "com.facebook.lite:id/reels_viewer_fragment"
        )

        for (indicator in reelsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    /**
     * Snapchat: Only block Spotlight
     */
    private fun isInSnapchatSpotlight(root: AccessibilityNodeInfo): Boolean {
        val spotlightIndicators = listOf(
            "com.snapchat.android:id/spotlight_feed_container",
            "com.snapchat.android:id/spotlight_viewer"
        )

        for (indicator in spotlightIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }
        return false
    }

    private fun blockVideoFeed(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return
        }
        lastBlockTime = now

        logInfo("BLOCKING video feed in $packageName after $videoSwipeCount videos")

        performGlobalAction(GLOBAL_ACTION_BACK)
        resetState()
        showBlockedNotification(packageName)
    }

    private fun resetState() {
        isInVideoFeed = false
        videoSwipeCount = 0
        lastVideoContentHash = 0
    }

    private fun isSystemPackage(packageName: String): Boolean {
        return packageName.startsWith("com.android") ||
                packageName.startsWith("com.google.android.gms") ||
                packageName.startsWith("com.samsung") ||
                packageName.startsWith("android") ||
                packageName == "com.derot.videoblocker"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showBlockedNotification(packageName: String) {
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: Exception) {
            packageName
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.blocked_notification_title))
            .setContentText(getString(R.string.blocked_notification_text, appName))
            .setSmallIcon(android.R.drawable.ic_delete)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(BLOCKED_NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() {
        logWarn("Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        logInfo("Service destroyed")
    }
}
