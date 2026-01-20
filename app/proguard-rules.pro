# Derot Video Blocker ProGuard Rules

# Keep accessibility service
-keep class com.derot.videoblocker.VideoFeedBlockerService { *; }

# Keep the app config classes
-keep class com.derot.videoblocker.** { *; }
