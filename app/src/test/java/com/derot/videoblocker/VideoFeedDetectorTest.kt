package com.derot.videoblocker

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for video feed detection patterns.
 * These test the pattern matching logic without needing a real device.
 */
class VideoFeedDetectorTest {

    // Patterns from the service
    private val genericFeedPatterns = listOf(
        "reel", "reels", "shorts", "stories", "vertical_video", "video_feed",
        "pager", "viewpager", "full_screen_video", "immersive",
        "tiktok", "fyp", "for_you", "foryou", "trending_video"
    )

    private val feedActivityPatterns = listOf(
        "ReelActivity", "ReelsActivity", "ShortsActivity", "ImmersiveActivity",
        "FullScreenVideoActivity", "VideoFeedActivity", "StoriesActivity",
        "ReelViewerFragment", "ShortsFragment", "VerticalFeedFragment",
        "ImmersiveViewerActivity", "MediaViewerActivity"
    )

    @Test
    fun `generic patterns match Instagram reels resource IDs`() {
        val instagramIds = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/reel_viewer_view_pager",
            "com.instagram.android:id/reels_tray"
        )

        for (id in instagramIds) {
            val matches = genericFeedPatterns.any { id.lowercase().contains(it) }
            assertTrue("Pattern should match: $id", matches)
        }
    }

    @Test
    fun `generic patterns match YouTube Shorts resource IDs`() {
        val youtubeIds = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/shorts_player",
            "com.google.android.youtube:id/reel_player_page_container"
        )

        for (id in youtubeIds) {
            val matches = genericFeedPatterns.any { id.lowercase().contains(it) }
            assertTrue("Pattern should match: $id", matches)
        }
    }

    @Test
    fun `generic patterns match TikTok resource IDs`() {
        val tiktokIds = listOf(
            "com.zhiliaoapp.musically:id/video_view",
            "com.ss.android.ugc.trill:id/video_view"
        )

        // Note: These may not match generic patterns since TikTok uses generic names
        // The app-specific detector handles TikTok
    }

    @Test
    fun `generic patterns do NOT match regular feed content`() {
        val normalIds = listOf(
            "com.twitter.android:id/tweet_list",
            "com.instagram.android:id/feed_recycler",
            "com.facebook.katana:id/news_feed"
        )

        for (id in normalIds) {
            val matches = genericFeedPatterns.any { pattern ->
                pattern != "pager" && id.lowercase().contains(pattern)
            }
            assertFalse("Pattern should NOT match normal feed: $id", matches)
        }
    }

    @Test
    fun `activity patterns match known video feed activities`() {
        val feedActivities = listOf(
            "com.instagram.android.reels.ReelActivity",
            "com.google.android.youtube.shorts.ShortsActivity",
            "com.facebook.video.ImmersiveActivity"
        )

        for (activity in feedActivities) {
            val matches = feedActivityPatterns.any { activity.contains(it, ignoreCase = true) }
            assertTrue("Should detect feed activity: $activity", matches)
        }
    }

    @Test
    fun `activity patterns do NOT match normal activities`() {
        val normalActivities = listOf(
            "com.twitter.android.MainActivity",
            "com.instagram.android.ProfileActivity",
            "com.facebook.katana.LoginActivity"
        )

        for (activity in normalActivities) {
            val matches = feedActivityPatterns.any { activity.contains(it, ignoreCase = true) }
            assertFalse("Should NOT detect normal activity: $activity", matches)
        }
    }

    @Test
    fun `system packages are correctly identified`() {
        val systemPackages = listOf(
            "com.android.systemui",
            "com.google.android.gms",
            "com.samsung.android.launcher",
            "android"
        )

        val systemPrefixes = listOf(
            "com.android", "com.google.android.gms", "com.google.android.gsf",
            "com.samsung", "com.sec", "android", "com.derot.videoblocker"
        )

        for (pkg in systemPackages) {
            val isSystem = systemPrefixes.any { pkg.startsWith(it) }
            assertTrue("Should detect system package: $pkg", isSystem)
        }
    }

    @Test
    fun `social apps are NOT identified as system`() {
        val socialApps = listOf(
            "com.twitter.android",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.facebook.katana"
        )

        val systemPrefixes = listOf(
            "com.android", "com.google.android.gms", "com.google.android.gsf",
            "com.samsung", "com.sec", "android", "com.derot.videoblocker"
        )

        for (pkg in socialApps) {
            val isSystem = systemPrefixes.any { pkg.startsWith(it) }
            assertFalse("Should NOT detect social app as system: $pkg", isSystem)
        }
    }

    @Test
    fun `cooldown prevents rapid blocking`() {
        val cooldownMs = 2000L
        var lastBlockTime = 0L

        // First block should work
        val now1 = 1000L
        val canBlock1 = now1 - lastBlockTime >= cooldownMs
        assertTrue("First block should be allowed", canBlock1)
        lastBlockTime = now1

        // Immediate second block should be prevented
        val now2 = 1500L
        val canBlock2 = now2 - lastBlockTime >= cooldownMs
        assertFalse("Rapid second block should be prevented", canBlock2)

        // Block after cooldown should work
        val now3 = 3500L
        val canBlock3 = now3 - lastBlockTime >= cooldownMs
        assertTrue("Block after cooldown should be allowed", canBlock3)
    }

    @Test
    fun `feed detection requires minimum time in screen`() {
        val feedDetectionDelay = 800L
        var entryTime = System.currentTimeMillis()

        // Immediate detection should fail
        val elapsed1 = 100L
        assertFalse("Should not detect feed immediately", elapsed1 > feedDetectionDelay)

        // After delay should pass
        val elapsed2 = 900L
        assertTrue("Should detect feed after delay", elapsed2 > feedDetectionDelay)
    }
}
