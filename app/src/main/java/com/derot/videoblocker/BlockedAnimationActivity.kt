package com.derot.videoblocker

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * Fun animation shown when blocking a video feed.
 * Shows 100+ cute dogs holding "stop rotting pleas" signs.
 */
class BlockedAnimationActivity : Activity() {

    companion object {
        const val ANIMATION_DURATION_MS = 800L
        const val NUM_DOGS = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen animated view
        setContentView(DogAnimationView(this))

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
     * Custom view that draws 100+ bouncing dogs with signs
     */
    inner class DogAnimationView(context: Context) : View(context) {

        private val dogs = mutableListOf<Dog>()
        private val dogDrawable: Drawable? = ContextCompat.getDrawable(context, R.drawable.cute_dog)
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 60f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(8f, 2f, 2f, Color.BLACK)
        }
        private val bgPaint = Paint().apply {
            color = Color.argb(220, 30, 30, 30)
        }

        private var animator: ValueAnimator? = null

        init {
            // Generate random dogs
            post {
                for (i in 0 until NUM_DOGS) {
                    dogs.add(Dog(
                        x = Random.nextFloat() * width,
                        y = Random.nextFloat() * height,
                        size = Random.nextInt(60, 120),
                        speedX = Random.nextFloat() * 20 - 10,
                        speedY = Random.nextFloat() * 20 - 10,
                        rotation = Random.nextFloat() * 30 - 15
                    ))
                }

                // Animate
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ANIMATION_DURATION_MS
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        updateDogs()
                        invalidate()
                    }
                    start()
                }
            }
        }

        private fun updateDogs() {
            for (dog in dogs) {
                dog.x += dog.speedX
                dog.y += dog.speedY
                dog.currentRotation += dog.rotation * 0.1f
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Dark background
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Draw all dogs
            dogDrawable?.let { drawable ->
                for (dog in dogs) {
                    canvas.save()
                    canvas.translate(dog.x, dog.y)
                    canvas.rotate(dog.currentRotation)
                    drawable.setBounds(-dog.size/2, -dog.size/2, dog.size/2, dog.size/2)
                    drawable.draw(canvas)
                    canvas.restore()
                }
            }

            // Draw text in center
            val centerX = width / 2f
            val centerY = height / 2f

            // Draw "stop rotting" bigger
            textPaint.textSize = 72f
            canvas.drawText("stop rotting", centerX, centerY - 20, textPaint)

            // Draw "pleas" smaller and cute
            textPaint.textSize = 56f
            canvas.drawText("pleas 🥺", centerX, centerY + 50, textPaint)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator?.cancel()
        }
    }

    data class Dog(
        var x: Float,
        var y: Float,
        val size: Int,
        val speedX: Float,
        val speedY: Float,
        val rotation: Float,
        var currentRotation: Float = 0f
    )
}
