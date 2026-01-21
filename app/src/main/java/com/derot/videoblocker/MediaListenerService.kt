package com.derot.videoblocker

import android.service.notification.NotificationListenerService

/**
 * Simple notification listener service that enables MediaSessionManager access.
 * We don't actually process notifications - we just need this to get media session info.
 */
class MediaListenerService : NotificationListenerService() {
    // No implementation needed - just having this service registered
    // allows us to use MediaSessionManager.getActiveSessions()
}
