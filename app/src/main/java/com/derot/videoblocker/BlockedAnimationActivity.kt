package com.derot.videoblocker

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Animation shown when blocking a video feed.
 * Shows a cute dog holding a "stop rotting pleas" sign.
 */
class BlockedAnimationActivity : Activity() {

    companion object {
        const val ANIMATION_DURATION_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen view with centered dog
        setContentView(DogView(this))

        // Auto-close after animation
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
        }, ANIMATION_DURATION_MS)
    }

    override fun onBackPressed() {
        // Don't allow dismissing early
    }

    /**
     * Simple view that draws a centered dog
     */
    inner class DogView(context: Context) : View(context) {

        private val dogDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.cute_dog)
        private val bgPaint = Paint().apply {
            color = Color.argb(220, 30, 30, 30)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Dark background
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Draw centered dog
            dogDrawable?.let { drawable ->
                val dogSize = minOf(width, height) * 2 / 3
                val centerX = width / 2
                val centerY = height / 2
                drawable.setBounds(
                    centerX - dogSize / 2,
                    centerY - dogSize / 2,
                    centerX + dogSize / 2,
                    centerY + dogSize / 2
                )
                drawable.draw(canvas)
            }
        }
    }
}
