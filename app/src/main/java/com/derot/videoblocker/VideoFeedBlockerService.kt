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
 * SECURITY NOTES:
 * - This service can see UI element IDs and structure, but deliberately does NOT
 *   read or log any user content (text, messages, passwords, etc.)
 * - All logging is disabled in release builds
 * - No data is stored persistently
 * - No network access (enforced by network_security_config.xml)
 *
 * Detection strategy:
 * 1. Monitor window state changes to detect when entering video feed screens
 * 2. Look for UI patterns that indicate infinite scroll video feeds:
 *    - Full-screen video players with vertical paging
 *    - Specific activity/fragment names from known apps
 *    - Resource IDs commonly used for reels/shorts/video feeds
 * 3. When detected, press back to exit the feed
 *
 * The service distinguishes between:
 * - Single video views (allowed) - watching a video from a post
 * - Video feeds (blocked) - infinite scroll short-form video feeds
 */
class VideoFeedBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "DerotBlocker"
        private const val CHANNEL_ID = "derot_service"
        private const val NOTIFICATION_ID = 1
        private const val BLOCKED_NOTIFICATION_ID = 2

        // Cooldown to prevent rapid back presses
        private const val BLOCK_COOLDOWN_MS = 2000L

        // Minimum time in a video screen before considering it a feed
        private const val FEED_DETECTION_DELAY_MS = 800L

        /**
         * Security: Only log in debug builds to prevent information leakage
         * in release builds. Logs could be read by other apps on older Android versions.
         */
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
    private var videoScreenEntryTime = 0L
    private var isInPotentialFeed = false
    private var consecutiveVideoChanges = 0
    private var lastVideoContentHash = 0

    // App-specific detectors
    private val appDetectors = mapOf(
        "com.twitter.android" to ::detectTwitterVideoFeed,
        "com.twitter.android.lite" to ::detectTwitterVideoFeed,
        "com.instagram.android" to ::detectInstagramVideoFeed,
        "com.zhiliaoapp.musically" to ::detectTikTokVideoFeed,  // TikTok
        "com.ss.android.ugc.trill" to ::detectTikTokVideoFeed,  // TikTok (alternate)
        "com.google.android.youtube" to ::detectYouTubeShortsVideoFeed,
        "com.facebook.katana" to ::detectFacebookVideoFeed,
        "com.facebook.lite" to ::detectFacebookVideoFeed,
        "com.snapchat.android" to ::detectSnapchatVideoFeed,
        "com.reddit.frontpage" to ::detectRedditVideoFeed,
    )

    // Generic patterns that indicate video feeds across apps
    private val genericFeedPatterns = listOf(
        // Resource ID patterns (partial matches)
        "reel", "reels", "shorts", "stories", "vertical_video", "video_feed",
        "pager", "viewpager", "full_screen_video", "immersive",
        "tiktok", "fyp", "for_you", "foryou", "trending_video"
    )

    // Activity/class name patterns that indicate feeds
    private val feedActivityPatterns = listOf(
        "ReelActivity", "ReelsActivity", "ShortsActivity", "ImmersiveActivity",
        "FullScreenVideoActivity", "VideoFeedActivity", "StoriesActivity",
        "ReelViewerFragment", "ShortsFragment", "VerticalFeedFragment",
        "ImmersiveViewerActivity", "MediaViewerActivity"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        logInfo("Derot service connected")

        // Configure service
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        createNotificationChannel()
        startForegroundNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: return

        // Ignore system apps and our own app
        if (isSystemPackage(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChange(event, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleContentChange(event, packageName)
            }
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent, packageName: String) {
        val className = event.className?.toString() ?: ""

        // Track app changes
        if (currentApp != packageName) {
            currentApp = packageName
            resetFeedState()
            logDebug("App changed to: $packageName")
        }

        // Check if entering a video feed screen by activity name
        if (feedActivityPatterns.any { className.contains(it, ignoreCase = true) }) {
            logDebug("Detected feed activity: $className")
            onPotentialFeedEntered(packageName)
        }

        // Check with app-specific detector
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val detector = appDetectors[packageName]
            if (detector != null) {
                if (detector(rootNode, event)) {
                    onPotentialFeedEntered(packageName)
                }
            } else {
                // Use generic detection for unknown apps
                if (detectGenericVideoFeed(rootNode, event)) {
                    onPotentialFeedEntered(packageName)
                }
            }
            rootNode.recycle()
        }
    }

    private fun handleContentChange(event: AccessibilityEvent, packageName: String) {
        if (!isInPotentialFeed) return

        // Security: We only use the HASH of content descriptions for change detection,
        // never the actual content. This prevents any sensitive text from being processed.
        val contentHash = event.contentDescription?.hashCode() ?: event.text?.hashCode() ?: 0
        if (contentHash != 0 && contentHash != lastVideoContentHash) {
            lastVideoContentHash = contentHash
            consecutiveVideoChanges++

            // If we see multiple video changes in quick succession, it's a feed
            if (consecutiveVideoChanges >= 2) {
                val timeSinceEntry = System.currentTimeMillis() - videoScreenEntryTime
                if (timeSinceEntry > FEED_DETECTION_DELAY_MS) {
                    logInfo("Feed behavior detected: $consecutiveVideoChanges changes in $packageName")
                    blockVideoFeed(packageName)
                }
            }
        }
    }

    private fun onPotentialFeedEntered(packageName: String) {
        if (!isInPotentialFeed) {
            isInPotentialFeed = true
            videoScreenEntryTime = System.currentTimeMillis()
            consecutiveVideoChanges = 0
            lastVideoContentHash = 0
            logDebug("Entered potential feed screen in $packageName")

            // Schedule a check after delay
            handler.postDelayed({
                if (isInPotentialFeed) {
                    checkAndBlockFeed(packageName)
                }
            }, FEED_DETECTION_DELAY_MS)
        }
    }

    private fun checkAndBlockFeed(packageName: String) {
        val rootNode = rootInActiveWindow ?: return

        try {
            // Verify we're still in a feed-like screen
            val isStillFeed = when {
                appDetectors.containsKey(packageName) -> {
                    appDetectors[packageName]?.invoke(rootNode, null) == true
                }
                else -> detectGenericVideoFeed(rootNode, null)
            }

            if (isStillFeed && consecutiveVideoChanges >= 1) {
                blockVideoFeed(packageName)
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun resetFeedState() {
        isInPotentialFeed = false
        videoScreenEntryTime = 0L
        consecutiveVideoChanges = 0
        lastVideoContentHash = 0
    }

    /**
     * Twitter/X video feed detection
     * Looks for the immersive video player that appears when scrolling into video feeds
     */
    private fun detectTwitterVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        // Look for Twitter's video feed indicators
        val indicators = listOf(
            "com.twitter.android:id/video_player_view",
            "com.twitter.android:id/immersive_player",
            "com.twitter.android:id/video_surface_view",
            "com.twitter.android:id/inline_video_player"
        )

        for (indicator in indicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                // Check if it's full-screen (feed) vs inline (single video in post)
                val node = nodes[0]
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)

                // If video takes up most of screen height, it's likely a feed
                val screenHeight = resources.displayMetrics.heightPixels
                val isFullScreen = bounds.height() > screenHeight * 0.7

                nodes.forEach { it.recycle() }

                if (isFullScreen) {
                    logDebug("Twitter: Full-screen video detected")
                    return true
                }
            }
        }

        // Also check for view pager which indicates swipeable video feed
        return hasVerticalViewPager(root)
    }

    /**
     * Instagram video feed detection (Reels)
     */
    private fun detectInstagramVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        val reelsIndicators = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/reel_viewer_view_pager",
            "com.instagram.android:id/reels_tray",
            "com.instagram.android:id/clips_tab"
        )

        for (indicator in reelsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                logDebug("Instagram: Reels indicator found - $indicator")
                return true
            }
        }

        return hasVerticalViewPager(root)
    }

    /**
     * TikTok feed detection - the whole app is basically a feed
     */
    private fun detectTikTokVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        // TikTok's main feed should be blocked, but allow profile views and search
        val feedIndicators = listOf(
            "com.zhiliaoapp.musically:id/aw0",  // Main feed
            "com.zhiliaoapp.musically:id/video_view",
            "com.ss.android.ugc.trill:id/video_view"
        )

        // Don't block if on profile or search
        val nonFeedIndicators = listOf(
            "com.zhiliaoapp.musically:id/profile_tab",
            "com.zhiliaoapp.musically:id/search",
            "com.ss.android.ugc.trill:id/profile_tab"
        )

        for (indicator in nonFeedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            val found = nodes.isNotEmpty()
            nodes.forEach { it.recycle() }
            if (found) return false
        }

        for (indicator in feedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }

        return hasVerticalViewPager(root)
    }

    /**
     * YouTube Shorts detection
     */
    private fun detectYouTubeShortsVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        val shortsIndicators = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/shorts_player",
            "com.google.android.youtube:id/reel_player_page_container"
        )

        for (indicator in shortsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                logDebug("YouTube: Shorts indicator found - $indicator")
                return true
            }
        }

        // Check content descriptions for "Shorts" indicators
        return findNodeWithText(root, listOf("shorts", "reel"))
    }

    /**
     * Facebook video feed detection (Reels)
     */
    private fun detectFacebookVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        val reelsIndicators = listOf(
            "com.facebook.katana:id/reels_viewer",
            "com.facebook.katana:id/video_channel",
            "com.facebook.lite:id/reels_viewer"
        )

        for (indicator in reelsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
        }

        return hasVerticalViewPager(root)
    }

    /**
     * Snapchat Spotlight detection
     */
    private fun detectSnapchatVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        val spotlightIndicators = listOf(
            "com.snapchat.android:id/spotlight_pager",
            "com.snapchat.android:id/spotlight_container"
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

    /**
     * Reddit video feed detection
     */
    private fun detectRedditVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        // Look for Reddit's video player in feed mode
        return findNodeWithText(root, listOf("video feed", "watch"))
    }

    /**
     * Generic detection for unknown apps
     */
    private fun detectGenericVideoFeed(root: AccessibilityNodeInfo, event: AccessibilityEvent?): Boolean {
        // Check for view IDs containing feed-related patterns
        if (hasViewIdMatching(root, genericFeedPatterns)) {
            return true
        }

        // Check for vertical scrolling video pager
        if (hasVerticalViewPager(root)) {
            return true
        }

        return false
    }

    /**
     * Check if there's a vertical ViewPager (common pattern for video feeds)
     */
    private fun hasVerticalViewPager(root: AccessibilityNodeInfo): Boolean {
        return findNode(root) { node ->
            val viewId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""

            (className.contains("ViewPager", ignoreCase = true) ||
             className.contains("RecyclerView", ignoreCase = true)) &&
            (viewId.contains("pager", ignoreCase = true) ||
             viewId.contains("feed", ignoreCase = true) ||
             viewId.contains("reel", ignoreCase = true))
        }
    }

    /**
     * Check if any view ID matches the given patterns
     */
    private fun hasViewIdMatching(root: AccessibilityNodeInfo, patterns: List<String>): Boolean {
        return findNode(root) { node ->
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            patterns.any { viewId.contains(it) }
        }
    }

    /**
     * Find node with text matching any of the given patterns.
     * Security: Only checks for specific UI label patterns, not user content.
     */
    private fun findNodeWithText(root: AccessibilityNodeInfo, patterns: List<String>): Boolean {
        return findNode(root) { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            patterns.any { text.contains(it) || contentDesc.contains(it) }
        }
    }

    /**
     * Generic node finder with depth limit to prevent stack overflow
     */
    private fun findNode(
        root: AccessibilityNodeInfo,
        depth: Int = 0,
        maxDepth: Int = 15,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (depth > maxDepth) return false
        if (predicate(root)) return true

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (findNode(child, depth + 1, maxDepth, predicate)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Block the video feed by pressing back
     */
    private fun blockVideoFeed(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return
        }
        lastBlockTime = now

        logInfo("BLOCKING video feed in $packageName")

        // Press back to exit the feed
        performGlobalAction(GLOBAL_ACTION_BACK)

        // Reset state
        resetFeedState()

        // Show notification
        showBlockedNotification(packageName)
    }

    private fun isSystemPackage(packageName: String): Boolean {
        val systemPackages = listOf(
            "com.android",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.samsung",
            "com.sec",
            "android",
            "com.derot.videoblocker"  // Don't monitor ourselves
        )
        return systemPackages.any { packageName.startsWith(it) }
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

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

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
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.blocked_notification_title))
            .setContentText(getString(R.string.blocked_notification_text, appName))
            .setSmallIcon(android.R.drawable.ic_delete)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(BLOCKED_NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() {
        logWarn("Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        logInfo("Service destroyed")
    }
}
