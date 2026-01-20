# Security Model

Derot is an accessibility service, which is a powerful Android capability. This document explains our security model and what the app can and cannot do.

## What the App CAN Do

1. **See UI element IDs and structure** - The app can see the names of UI elements (like `com.instagram.android:id/reels_tab`) to detect video feed screens
2. **Press the back button** - When a video feed is detected, the app presses back to exit
3. **Show notifications** - To inform you when a feed was blocked

## What the App CANNOT Do

1. **Access the internet** - The app has NO network permission and explicitly blocks all network traffic via `network_security_config.xml`
2. **Read your messages/content** - The app only checks UI element IDs, not actual text content
3. **Store data** - No user data is persisted; the app has no database or file storage
4. **Run code from remote sources** - No dynamic code loading or WebViews
5. **Access other permissions** - No camera, microphone, location, contacts, etc.

## Security Measures

### 1. No Network Access
```xml
<!-- AndroidManifest.xml -->
android:networkSecurityConfig="@xml/network_security_config"
android:usesCleartextTraffic="false"
```

The app has no INTERNET permission and the network security config blocks all connections as defense-in-depth.

### 2. No Logging in Release Builds
All logging is wrapped in `BuildConfig.DEBUG` checks, preventing any information from being written to logcat in release builds. This is important because on older Android versions, other apps could read logcat.

### 3. No Sensitive Data Processing
The app uses hashes of content descriptions for change detection, never the actual content:
```kotlin
val contentHash = event.contentDescription?.hashCode() ?: 0
```

### 4. Minimal Permissions
The app only requests permissions it strictly needs:
- `BIND_ACCESSIBILITY_SERVICE` - Required for the core functionality
- `FOREGROUND_SERVICE` - Required to run persistently
- `POST_NOTIFICATIONS` - To show "feed blocked" notifications
- `QUERY_ALL_PACKAGES` - To get app names for notifications

### 5. Service Not Exported
The accessibility service is not exported, preventing other apps from binding to it:
```xml
android:exported="false"
```

### 6. No Backup
App data cannot be extracted via backup:
```xml
android:allowBackup="false"
```

## Verifying the APK

To verify you have an unmodified version:

1. **Check the signature** - Only install from trusted sources
2. **Review the source** - The full source code is available for audit
3. **Build from source** - You can build the APK yourself using Android Studio

## Permissions Explained

| Permission | Why Needed |
|------------|------------|
| `BIND_ACCESSIBILITY_SERVICE` | Core functionality - monitor apps for video feeds |
| `FOREGROUND_SERVICE` | Keep service running in background |
| `POST_NOTIFICATIONS` | Show "blocked" notifications |
| `QUERY_ALL_PACKAGES` | Get app display names for notifications |

## Threat Model

### Protected Against
- Data exfiltration (no network access)
- Log-based leaks (no release logging)
- Backup extraction (backups disabled)
- External app exploitation (service not exported)

### Not Protected Against
- Physical device access with debugging enabled
- Rooted devices (root can bypass any protection)
- Modified APKs (always verify source)

## Reporting Security Issues

If you discover a security vulnerability, please open an issue on the repository with the `security` label.
