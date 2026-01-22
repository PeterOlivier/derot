package com.derot.videoblocker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating overlay that displays real-time research data about the current app.
 * Shows what Derot "sees" and thinks about the current state.
 */
class ResearchOverlay(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // UI elements
    private var appNameText: TextView? = null
    private var activityText: TextView? = null
    private var verdictText: TextView? = null
    private var mediaStateText: TextView? = null
    private var mediaPositionText: TextView? = null
    private var mediaTitleText: TextView? = null
    private var scrollCountText: TextView? = null
    private var accessibilityText: TextView? = null
    private var signalsText: TextView? = null

    data class ResearchState(
        val appName: String = "",
        val activityName: String = "",
        val verdict: Verdict = Verdict.UNKNOWN,
        val mediaState: String = "none",
        val mediaPosition: Long = 0,
        val mediaDuration: Long = 0,
        val mediaTitle: String = "",
        val positionResetCount: Int = 0,
        val scrollCount: Int = 0,
        val accessibilityAvailable: Boolean = false,
        val isFullscreen: Boolean = false,
        val signals: List<String> = emptyList()
    )

    enum class Verdict(val display: String, val color: Int) {
        UNKNOWN("UNKNOWN", Color.GRAY),
        SAFE("SAFE", Color.rgb(100, 200, 100)),
        WATCHING("WATCHING", Color.rgb(200, 200, 100)),
        FEED_LIKELY("FEED LIKELY", Color.rgb(255, 165, 0)),
        FEED_DETECTED("FEED DETECTED", Color.rgb(255, 80, 80))
    }

    fun show() {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 100
        }

        overlayView = createOverlayView()
        windowManager?.addView(overlayView, params)
    }

    fun hide() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    fun update(state: ResearchState) {
        appNameText?.text = "App: ${state.appName.substringAfterLast('.')}"
        activityText?.text = "Activity: ${state.activityName.substringAfterLast('.')}"

        verdictText?.apply {
            text = state.verdict.display
            setTextColor(state.verdict.color)
        }

        mediaStateText?.text = "Media: ${state.mediaState}"

        val posStr = formatTime(state.mediaPosition)
        val durStr = formatTime(state.mediaDuration)
        mediaPositionText?.text = "Position: $posStr / $durStr (resets: ${state.positionResetCount})"

        mediaTitleText?.text = "Title: ${state.mediaTitle.take(30)}${if (state.mediaTitle.length > 30) "..." else ""}"

        scrollCountText?.text = "Scrolls: ${state.scrollCount}"

        accessibilityText?.text = "A11y tree: ${if (state.accessibilityAvailable) "YES" else "NO (blocked)"}"
        accessibilityText?.setTextColor(if (state.accessibilityAvailable) Color.rgb(100, 200, 100) else Color.rgb(255, 100, 100))

        signalsText?.text = if (state.signals.isEmpty()) {
            "Signals: none"
        } else {
            "Signals: ${state.signals.joinToString(", ")}"
        }
    }

    private fun createOverlayView(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 20, 20, 30))
            setPadding(dp(12), dp(8), dp(12), dp(8))

            // Header
            addView(TextView(context).apply {
                text = "DEROT RESEARCH MODE"
                setTextColor(Color.rgb(150, 150, 255))
                setTypeface(null, Typeface.BOLD)
                textSize = 12f
            })

            // Verdict (large)
            verdictText = TextView(context).apply {
                text = "UNKNOWN"
                setTextColor(Color.GRAY)
                setTypeface(null, Typeface.BOLD)
                textSize = 18f
            }
            addView(verdictText)

            // Divider
            addView(View(context).apply {
                setBackgroundColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
            })

            // App info
            appNameText = createInfoText("App: ---")
            addView(appNameText)

            activityText = createInfoText("Activity: ---")
            addView(activityText)

            // Accessibility
            accessibilityText = createInfoText("A11y tree: ---")
            addView(accessibilityText)

            // Divider
            addView(View(context).apply {
                setBackgroundColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
            })

            // Media info
            addView(TextView(context).apply {
                text = "MediaSession:"
                setTextColor(Color.rgb(150, 150, 150))
                textSize = 10f
            })

            mediaStateText = createInfoText("Media: none")
            addView(mediaStateText)

            mediaPositionText = createInfoText("Position: --:-- / --:--")
            addView(mediaPositionText)

            mediaTitleText = createInfoText("Title: ---")
            addView(mediaTitleText)

            // Divider
            addView(View(context).apply {
                setBackgroundColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
            })

            // Behavior tracking
            scrollCountText = createInfoText("Scrolls: 0")
            addView(scrollCountText)

            signalsText = createInfoText("Signals: none")
            addView(signalsText)
        }
    }

    private fun createInfoText(initial: String): TextView {
        return TextView(context).apply {
            text = initial
            setTextColor(Color.rgb(200, 200, 200))
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "--:--"
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return "%d:%02d".format(minutes, seconds)
    }
}
