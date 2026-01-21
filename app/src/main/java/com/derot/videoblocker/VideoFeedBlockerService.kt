package com.derot.videoblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * Accessibility service that detects and blocks short-form video feeds.
 *
 * Detection strategy:
 * 1. Only monitor specific known apps (X, Instagram, YouTube, TikTok, etc.)
 * 2. Look for very specific UI elements that indicate a VIDEO FEED
 * 3. Block IMMEDIATELY when feed is detected - no free videos!
 * 4. Show cute frog animation to let user know we saved them
 */
class VideoFeedBlockerService : AccessibilityService() {

    companion object {
        private const val TAG = "DerotBlocker"
        private const val CHANNEL_ID = "derot_service"
        private const val NOTIFICATION_ID = 1
        private const val BLOCKED_NOTIFICATION_ID = 2

        // Cooldown to prevent rapid triggers
        private const val BLOCK_COOLDOWN_MS = 3000L

        // Debug: dump view IDs once per app session
        private const val DEBUG_DUMP_VIEW_IDS = true
        private const val VIEW_ID_DUMP_COOLDOWN_MS = 5000L

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
    private var lastViewIdDumpTime = 0L
    private val dumpedApps = mutableSetOf<String>()
    private var twitterNullCount = 0
    private var lastActivityCheckTime = 0L
    private val activityCheckCooldown = 1000L // Check activity every 1 second max
    private var lastMediaCheckTime = 0L
    private val mediaCheckCooldown = 500L // Check media sessions every 500ms max

    // Track when user first enters a video feed (allow first video, block subsequent)
    private val videoFeedEntryTime = mutableMapOf<String, Long>()
    private val videoFeedContentHash = mutableMapOf<String, Int>()
    private val videoFeedHashStableTime = mutableMapOf<String, Long>()
    private val FIRST_VIDEO_GRACE_PERIOD_MS = 2000L // Wait 2 seconds for video to load before tracking swipes

    // Apps known for short-form video feeds
    private val videoFeedApps = setOf(
        "com.twitter.android",
        "com.twitter.android.lite",
        "com.instagram.android",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.snapchat.android"
    )

    // Scroll-based detection for apps that block accessibility tree
    private val scrollCountByApp = mutableMapOf<String, Int>()
    private val scrollWindowStart = mutableMapOf<String, Long>()
    private val SCROLL_WINDOW_MS = 30000L // 30 second window
    private val SCROLL_THRESHOLD_FOR_FEED = 3 // 3+ scrolls = likely browsing feed
    private var lastScrollTime = 0L
    private val SCROLL_DEBOUNCE_MS = 500L // Debounce rapid scroll events

    // Time-in-app tracking for fallback detection
    private val appEntryTime = mutableMapOf<String, Long>()
    private val TIME_IN_APP_WARNING_MS = 120000L // 2 minutes = warning
    private var lastTimeWarning = 0L
    private val TIME_WARNING_COOLDOWN_MS = 60000L // Don't warn more than once per minute

    override fun onServiceConnected() {
        super.onServiceConnected()
        logInfo("Derot service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED  // Track scrolls for Twitter
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

        // Track app changes and time spent
        if (currentApp != packageName) {
            val previousApp = currentApp
            currentApp = packageName
            logDebug("App changed to: $packageName")

            // Reset scroll tracking when leaving an app
            scrollCountByApp.remove(previousApp)
            scrollWindowStart.remove(previousApp)

            // Track app entry time for video feed apps
            if (packageName in videoFeedApps) {
                appEntryTime[packageName] = System.currentTimeMillis()
            }
        }

        // Check time in app for Twitter (fallback detection)
        if (packageName == "com.twitter.android" || packageName == "com.twitter.android.lite") {
            checkTimeInTwitter(packageName)
        }

        // Handle scroll events (for Twitter which blocks accessibility tree)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(event, packageName)
        }

        // Check for video feeds on any window change
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // Special handling for Twitter - try event-based detection first
            if (packageName == "com.twitter.android" || packageName == "com.twitter.android.lite") {
                checkTwitterVideoFeedFromEvent(event, packageName)
            }

            checkAndBlockVideoFeed(packageName)
        }
    }

    /**
     * Track scroll events for apps that block accessibility tree (like Twitter).
     * If user scrolls many times in a short period, they're likely browsing a feed.
     */
    private fun handleScrollEvent(event: AccessibilityEvent, packageName: String) {
        // Only track scrolls for Twitter (which blocks accessibility)
        if (packageName != "com.twitter.android" && packageName != "com.twitter.android.lite") {
            return
        }

        val now = System.currentTimeMillis()

        // Debounce - don't count rapid-fire scroll events
        if (now - lastScrollTime < SCROLL_DEBOUNCE_MS) {
            return
        }
        lastScrollTime = now

        // Check if this looks like a vertical scroll (feed browsing)
        val scrollY = event.scrollY
        val isVerticalScroll = event.scrollDeltaY != 0 || scrollY > 0

        // Debug: log scroll events from Twitter
        if (!dumpedApps.contains("scroll_debug_$packageName")) {
            dumpedApps.add("scroll_debug_$packageName")
            logInfo("=== TWITTER SCROLL EVENT ===")
            logInfo("  scrollX=${event.scrollX}, scrollY=$scrollY")
            logInfo("  scrollDeltaX=${event.scrollDeltaX}, scrollDeltaY=${event.scrollDeltaY}")
            logInfo("  fromIndex=${event.fromIndex}, toIndex=${event.toIndex}")
            logInfo("  className=${event.className}")
        }

        // Only count what looks like vertical feed scrolls
        if (!isVerticalScroll) {
            return
        }

        // Track scroll count within time window
        val windowStart = scrollWindowStart[packageName] ?: now
        if (now - windowStart > SCROLL_WINDOW_MS) {
            // Window expired, reset
            scrollCountByApp[packageName] = 1
            scrollWindowStart[packageName] = now
            logDebug("Twitter: Scroll window reset, count=1")
        } else {
            val count = (scrollCountByApp[packageName] ?: 0) + 1
            scrollCountByApp[packageName] = count
            logDebug("Twitter: Scroll count=$count (threshold=$SCROLL_THRESHOLD_FOR_FEED)")

            // Threshold reached - user is scrolling through feed
            if (count >= SCROLL_THRESHOLD_FOR_FEED) {
                logInfo("Twitter: Detected feed browsing via scroll count ($count scrolls)")
                blockVideoFeed(packageName)

                // Reset tracking after blocking
                scrollCountByApp.remove(packageName)
                scrollWindowStart.remove(packageName)
            }
        }
    }

    /**
     * Time-based detection for Twitter - warn after extended use.
     * This is a fallback when scroll detection doesn't work.
     */
    private fun checkTimeInTwitter(packageName: String) {
        val now = System.currentTimeMillis()
        val entryTime = appEntryTime[packageName] ?: return
        val timeInApp = now - entryTime

        // Don't warn too frequently
        if (now - lastTimeWarning < TIME_WARNING_COOLDOWN_MS) {
            return
        }

        // After 2 minutes in Twitter, block (likely scrolling feed)
        if (timeInApp >= TIME_IN_APP_WARNING_MS) {
            logInfo("Twitter: Time-based detection triggered (${timeInApp / 1000}s in app)")
            lastTimeWarning = now

            // Reset entry time so we don't immediately re-trigger
            appEntryTime[packageName] = now

            blockVideoFeed(packageName)
        }
    }

    /**
     * Twitter-specific detection using event data directly (when rootInActiveWindow is null)
     */
    private fun checkTwitterVideoFeedFromEvent(event: AccessibilityEvent, packageName: String) {
        // Get class name from event
        val className = event.className?.toString() ?: ""

        // Get content description and text from event
        val contentDesc = event.contentDescription?.toString() ?: ""
        val text = event.text.joinToString(" ")

        // Debug: log what we can see from Twitter
        if (!dumpedApps.contains("twitter_event_debug")) {
            dumpedApps.add("twitter_event_debug")
            logInfo("=== TWITTER EVENT DEBUG ===")
            logInfo("  className: $className")
            logInfo("  contentDesc: $contentDesc")
            logInfo("  text: $text")
            logInfo("  eventType: ${event.eventType}")
        }

        // METHOD 2: Check current activity via UsageStats (more reliable)
        val activityName = getCurrentActivityName(packageName)
        if (isVideoFeedActivity(activityName)) {
            logInfo("Twitter: Video feed detected via UsageStats activity: $activityName")
            blockVideoFeed(packageName)
            return
        }

        // METHOD 3: Check if media is playing via MediaSession
        if (checkMediaSessionPlaying(packageName)) {
            logInfo("Twitter: Video detected via MediaSession playback")
            // Don't auto-block just on media playing - could be regular video
            // Just log for now to understand the patterns
        }

        // Try to get info from event source
        val source = event.source
        if (source != null) {
            try {
                if (!dumpedApps.contains("twitter_source_debug")) {
                    dumpedApps.add("twitter_source_debug")
                    logInfo("=== TWITTER SOURCE DEBUG ===")
                    logInfo("  source.className: ${source.className}")
                    logInfo("  source.viewIdResourceName: ${source.viewIdResourceName}")
                    logInfo("  source.contentDescription: ${source.contentDescription}")
                    logInfo("  source.text: ${source.text}")

                    // Try to dump IDs from source's window
                    val viewIds = mutableSetOf<String>()
                    collectViewIds(source, viewIds, 0)
                    logInfo("=== TWITTER SOURCE VIEW IDS ===")
                    viewIds.sorted().forEach { viewId ->
                        logInfo("  $viewId")
                    }
                    logInfo("=== END TWITTER SOURCE (${viewIds.size} total) ===")
                }

                // Check for video feed indicators in class names
                val videoFeedClasses = listOf(
                    "ImmersivePlayer",
                    "VideoPlayer",
                    "FullScreenVideo",
                    "MediaViewer",
                    "ViewPager"
                )

                for (indicator in videoFeedClasses) {
                    if (className.contains(indicator, ignoreCase = true) ||
                        source.className?.toString()?.contains(indicator, ignoreCase = true) == true) {
                        logDebug("Twitter: Found video indicator class: $indicator")
                        // Don't block yet - just log for now until we understand the patterns
                    }
                }
            } finally {
                source.recycle()
            }
        }
    }

    private fun checkAndBlockVideoFeed(packageName: String) {
        val rootNode = rootInActiveWindow

        // Debug: log when we can't get the root node for target apps
        if (rootNode == null) {
            if (packageName == "com.twitter.android") {
                twitterNullCount++
                if (twitterNullCount <= 5 || twitterNullCount % 50 == 0) {
                    logDebug("Twitter: rootInActiveWindow is NULL (count: $twitterNullCount)")
                }
            }
            return
        }

        // Reset null counter when we get a valid root
        if (packageName == "com.twitter.android") {
            if (twitterNullCount > 0) {
                logDebug("Twitter: Got valid rootNode after $twitterNullCount nulls")
            }
            twitterNullCount = 0
        }

        try {
            // Debug: dump view IDs for target apps
            if (packageName in listOf("com.twitter.android", "com.instagram.android")) {
                dumpViewIds(rootNode, packageName)
            }

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
                else -> false
            }

            if (inFeed) {
                blockVideoFeed(packageName)
            }
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Twitter/X: Block the immersive full-screen video feed
     */
    private fun isInTwitterVideoFeed(root: AccessibilityNodeInfo): Boolean {
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
     * Instagram: Block Reels - but allow the first video they clicked on
     */
    private fun isInInstagramReels(root: AccessibilityNodeInfo): Boolean {
        val reelsIndicators = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/reel_viewer_view_pager"
        )

        var inReels = false
        for (indicator in reelsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                inReels = true
                break
            }
        }

        if (!inReels) {
            // Left the reels viewer - reset tracking
            videoFeedEntryTime.remove("instagram")
            videoFeedContentHash.remove("instagram")
            videoFeedHashStableTime.remove("instagram")
            return false
        }

        val contentHash = getContentHash(root)
        val now = System.currentTimeMillis()

        // First time entering reels?
        if (!videoFeedEntryTime.containsKey("instagram")) {
            videoFeedEntryTime["instagram"] = now
            videoFeedContentHash["instagram"] = contentHash
            videoFeedHashStableTime["instagram"] = now
            logDebug("Instagram: Entered Reels - allowing first video (grace period started)")
            return false // Allow the first video
        }

        val entryTime = videoFeedEntryTime["instagram"] ?: now

        // Still in grace period? Update the "stable" hash but don't block
        if (now - entryTime < FIRST_VIDEO_GRACE_PERIOD_MS) {
            videoFeedContentHash["instagram"] = contentHash
            videoFeedHashStableTime["instagram"] = now
            return false // Still loading, don't block
        }

        // Grace period over - now check for swipes
        val previousHash = videoFeedContentHash["instagram"] ?: 0
        if (contentHash != previousHash && contentHash != 0) {
            logDebug("Instagram: Detected swipe to next video (hash: $previousHash -> $contentHash)")
            return true // Block!
        }

        return false
    }

    /**
     * YouTube: Block Shorts - but allow the first video they clicked on
     */
    private fun isInYouTubeShorts(root: AccessibilityNodeInfo): Boolean {
        val shortsIndicators = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/shorts_player_container"
        )

        var inShorts = false
        for (indicator in shortsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                inShorts = true
                break
            }
        }

        if (!inShorts) {
            // Left shorts - reset tracking
            videoFeedEntryTime.remove("youtube")
            videoFeedContentHash.remove("youtube")
            videoFeedHashStableTime.remove("youtube")
            return false
        }

        val contentHash = getContentHash(root)
        val now = System.currentTimeMillis()

        // First time entering shorts?
        if (!videoFeedEntryTime.containsKey("youtube")) {
            videoFeedEntryTime["youtube"] = now
            videoFeedContentHash["youtube"] = contentHash
            videoFeedHashStableTime["youtube"] = now
            logDebug("YouTube: Entered Shorts - allowing first video (grace period started)")
            return false // Allow the first video
        }

        val entryTime = videoFeedEntryTime["youtube"] ?: now

        // Still in grace period? Update the "stable" hash but don't block
        if (now - entryTime < FIRST_VIDEO_GRACE_PERIOD_MS) {
            videoFeedContentHash["youtube"] = contentHash
            videoFeedHashStableTime["youtube"] = now
            return false // Still loading, don't block
        }

        // Grace period over - now check for swipes
        val previousHash = videoFeedContentHash["youtube"] ?: 0
        if (contentHash != previousHash && contentHash != 0) {
            logDebug("YouTube: Detected swipe to next video (hash: $previousHash -> $contentHash)")
            return true // Block!
        }

        return false
    }

    /**
     * TikTok: Block the main For You feed - but allow the first video
     */
    private fun isInTikTokFeed(root: AccessibilityNodeInfo): Boolean {
        val feedIndicators = listOf(
            "com.zhiliaoapp.musically:id/rl",
            "com.ss.android.ugc.trill:id/rl"
        )

        val nonFeedIndicators = listOf(
            "com.zhiliaoapp.musically:id/profile_fragment",
            "com.zhiliaoapp.musically:id/search_bar",
            "com.ss.android.ugc.trill:id/profile_fragment"
        )

        // Check if we're NOT in a feed (profile, search, etc.)
        for (indicator in nonFeedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                videoFeedEntryTime.remove("tiktok")
                videoFeedContentHash.remove("tiktok")
                videoFeedHashStableTime.remove("tiktok")
                return false
            }
        }

        var inFeed = false
        for (indicator in feedIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                inFeed = true
                break
            }
        }

        if (!inFeed) {
            videoFeedEntryTime.remove("tiktok")
            videoFeedContentHash.remove("tiktok")
            videoFeedHashStableTime.remove("tiktok")
            return false
        }

        val contentHash = getContentHash(root)
        val now = System.currentTimeMillis()

        // First time entering feed?
        if (!videoFeedEntryTime.containsKey("tiktok")) {
            videoFeedEntryTime["tiktok"] = now
            videoFeedContentHash["tiktok"] = contentHash
            videoFeedHashStableTime["tiktok"] = now
            logDebug("TikTok: Entered feed - allowing first video (grace period started)")
            return false
        }

        val entryTime = videoFeedEntryTime["tiktok"] ?: now

        // Still in grace period?
        if (now - entryTime < FIRST_VIDEO_GRACE_PERIOD_MS) {
            videoFeedContentHash["tiktok"] = contentHash
            videoFeedHashStableTime["tiktok"] = now
            return false
        }

        // Grace period over - check for swipes
        val previousHash = videoFeedContentHash["tiktok"] ?: 0
        if (contentHash != previousHash && contentHash != 0) {
            logDebug("TikTok: Detected swipe to next video (hash: $previousHash -> $contentHash)")
            return true
        }

        return false
    }

    /**
     * Facebook: Block Reels - but allow the first video
     */
    private fun isInFacebookReels(root: AccessibilityNodeInfo): Boolean {
        val reelsIndicators = listOf(
            "com.facebook.katana:id/reels_viewer_fragment",
            "com.facebook.katana:id/reels_surface_view",
            "com.facebook.lite:id/reels_viewer_fragment"
        )

        var inReels = false
        for (indicator in reelsIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                inReels = true
                break
            }
        }

        if (!inReels) {
            videoFeedEntryTime.remove("facebook")
            videoFeedContentHash.remove("facebook")
            videoFeedHashStableTime.remove("facebook")
            return false
        }

        val contentHash = getContentHash(root)
        val now = System.currentTimeMillis()

        if (!videoFeedEntryTime.containsKey("facebook")) {
            videoFeedEntryTime["facebook"] = now
            videoFeedContentHash["facebook"] = contentHash
            videoFeedHashStableTime["facebook"] = now
            logDebug("Facebook: Entered Reels - allowing first video (grace period started)")
            return false
        }

        val entryTime = videoFeedEntryTime["facebook"] ?: now

        if (now - entryTime < FIRST_VIDEO_GRACE_PERIOD_MS) {
            videoFeedContentHash["facebook"] = contentHash
            videoFeedHashStableTime["facebook"] = now
            return false
        }

        val previousHash = videoFeedContentHash["facebook"] ?: 0
        if (contentHash != previousHash && contentHash != 0) {
            logDebug("Facebook: Detected swipe to next video")
            return true
        }

        return false
    }

    /**
     * Snapchat: Block Spotlight - but allow the first video
     */
    private fun isInSnapchatSpotlight(root: AccessibilityNodeInfo): Boolean {
        val spotlightIndicators = listOf(
            "com.snapchat.android:id/spotlight_feed_container",
            "com.snapchat.android:id/spotlight_viewer"
        )

        var inSpotlight = false
        for (indicator in spotlightIndicators) {
            val nodes = root.findAccessibilityNodeInfosByViewId(indicator)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                inSpotlight = true
                break
            }
        }

        if (!inSpotlight) {
            videoFeedEntryTime.remove("snapchat")
            videoFeedContentHash.remove("snapchat")
            videoFeedHashStableTime.remove("snapchat")
            return false
        }

        val contentHash = getContentHash(root)
        val now = System.currentTimeMillis()

        if (!videoFeedEntryTime.containsKey("snapchat")) {
            videoFeedEntryTime["snapchat"] = now
            videoFeedContentHash["snapchat"] = contentHash
            videoFeedHashStableTime["snapchat"] = now
            logDebug("Snapchat: Entered Spotlight - allowing first video (grace period started)")
            return false
        }

        val entryTime = videoFeedEntryTime["snapchat"] ?: now

        if (now - entryTime < FIRST_VIDEO_GRACE_PERIOD_MS) {
            videoFeedContentHash["snapchat"] = contentHash
            videoFeedHashStableTime["snapchat"] = now
            return false
        }

        val previousHash = videoFeedContentHash["snapchat"] ?: 0
        if (contentHash != previousHash && contentHash != 0) {
            logDebug("Snapchat: Detected swipe to next video")
            return true
        }

        return false
    }

    private fun blockVideoFeed(packageName: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return
        }
        lastBlockTime = now

        logInfo("BLOCKING video feed in $packageName")

        // Show the cute frog animation!
        showBlockAnimation()

        // Press back AFTER animation finishes (animation is 800ms)
        // Press multiple times since some apps need more than one back press
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            logDebug("First back press")
        }, 850)

        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            logDebug("Second back press")
        }, 950)

        showBlockedNotification(packageName)
    }

    private fun showBlockAnimation() {
        try {
            val intent = Intent(this, BlockedAnimationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            logWarn("Could not show animation: ${e.message}")
        }
    }

    /**
     * Debug: Dump all view IDs in the current screen to logcat
     */
    private fun dumpViewIds(root: AccessibilityNodeInfo, packageName: String) {
        if (!DEBUG_DUMP_VIEW_IDS) return

        val now = System.currentTimeMillis()
        if (now - lastViewIdDumpTime < VIEW_ID_DUMP_COOLDOWN_MS) return
        if (dumpedApps.contains(packageName)) return

        lastViewIdDumpTime = now
        dumpedApps.add(packageName)

        val viewIds = mutableSetOf<String>()
        collectViewIds(root, viewIds, 0)

        logInfo("=== VIEW IDS FOR $packageName ===")
        viewIds.sorted().forEach { viewId ->
            logInfo("  $viewId")
        }
        logInfo("=== END VIEW IDS (${viewIds.size} total) ===")
    }

    private fun collectViewIds(node: AccessibilityNodeInfo, ids: MutableSet<String>, depth: Int) {
        if (depth > 30) return // Prevent infinite recursion

        node.viewIdResourceName?.let { id ->
            ids.add(id)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectViewIds(child, ids, depth + 1)
            child.recycle()
        }
    }

    /**
     * Get a hash of the current screen content to detect when user swipes to new video.
     * Uses content descriptions and text which typically contain video titles, usernames, etc.
     */
    private fun getContentHash(root: AccessibilityNodeInfo): Int {
        val contentBuilder = StringBuilder()
        collectContentForHash(root, contentBuilder, 0)
        return contentBuilder.toString().hashCode()
    }

    private fun collectContentForHash(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        if (depth > 15) return // Don't go too deep

        // Collect text and content descriptions (video titles, usernames, etc.)
        node.text?.let { builder.append(it) }
        node.contentDescription?.let { builder.append(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectContentForHash(child, builder, depth + 1)
            child.recycle()
        }
    }

    /**
     * Get current foreground activity name using UsageStats
     * This is more resistant to apps blocking accessibility
     */
    private fun getCurrentActivityName(packageName: String): String? {
        val now = System.currentTimeMillis()
        if (now - lastActivityCheckTime < activityCheckCooldown) return null
        lastActivityCheckTime = now

        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null

            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 5000 // Last 5 seconds

            val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
            var lastActivityName: String? = null

            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.packageName == packageName &&
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastActivityName = event.className
                }
            }

            if (lastActivityName != null && !dumpedApps.contains("activity_$packageName")) {
                dumpedApps.add("activity_$packageName")
                logInfo("=== FOREGROUND ACTIVITY for $packageName ===")
                logInfo("  Activity: $lastActivityName")
            }

            return lastActivityName
        } catch (e: Exception) {
            logWarn("UsageStats error: ${e.message}")
            return null
        }
    }

    /**
     * Check if a video feed app is currently playing media
     * Uses MediaSessionManager to detect active playback
     */
    private fun checkMediaSessionPlaying(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastMediaCheckTime < mediaCheckCooldown) return false
        lastMediaCheckTime = now

        try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return false

            // Try to get active sessions (requires notification listener permission)
            val activeSessions: List<MediaController>
            try {
                activeSessions = mediaSessionManager.getActiveSessions(
                    ComponentName(this, MediaListenerService::class.java)
                )
            } catch (e: SecurityException) {
                // No notification listener permission - user needs to enable it
                logDebug("MediaSession: No permission - enable Notification Access for Derot")
                return false
            }

            for (controller in activeSessions) {
                val sessionPackage = controller.packageName
                val playbackState = controller.playbackState

                // Debug: log active media sessions from target apps
                if (sessionPackage in videoFeedApps && !dumpedApps.contains("media_$sessionPackage")) {
                    dumpedApps.add("media_$sessionPackage")
                    logInfo("=== MEDIA SESSION for $sessionPackage ===")
                    logInfo("  state: ${playbackState?.state}")
                    logInfo("  metadata title: ${controller.metadata?.description?.title}")
                    logInfo("  metadata subtitle: ${controller.metadata?.description?.subtitle}")
                }

                // Check if this is our target app and it's playing
                if (sessionPackage == packageName) {
                    val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
                    if (isPlaying) {
                        logDebug("MediaSession: $packageName is playing media")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            logWarn("MediaSession error: ${e.message}")
        }

        return false
    }

    /**
     * Check if activity name indicates a video feed
     */
    private fun isVideoFeedActivity(activityName: String?): Boolean {
        if (activityName == null) return false

        val videoFeedActivityPatterns = listOf(
            // Twitter/X - specific video feed activities (NOT "main" which is the whole app)
            "immersive", "video", "media", "player", "reel", "explore",
            // YouTube
            "shorts",
            // Instagram
            "clips",
            // TikTok
            "foryou",
            // Generic
            "fullscreen", "vertical"
        )

        val lowerActivity = activityName.lowercase()
        for (pattern in videoFeedActivityPatterns) {
            if (lowerActivity.contains(pattern)) {
                logDebug("Activity matches video pattern '$pattern': $activityName")
                return true
            }
        }
        return false
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
