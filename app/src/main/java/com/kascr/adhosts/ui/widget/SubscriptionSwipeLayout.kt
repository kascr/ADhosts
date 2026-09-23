package com.kascr.adhosts.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.kascr.adhosts.R
import kotlin.math.abs

/** Owns both the touch sequence and the only animator allowed to move a subscription. */
class SubscriptionSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var onInteraction: ((SubscriptionSwipeLayout) -> Unit)? = null
    private lateinit var content: View
    private lateinit var deleteCard: View
    private lateinit var deleteButton: View
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var animator: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var downOffset = 0f
    private var wasOpen = false
    private var downOnContent = false
    private var targetOpen = false
    private var dragging = false
    private var yielded = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.contentCard)
        deleteCard = findViewById(R.id.deleteActionCard)
        deleteButton = findViewById(R.id.deleteActionText)
        // Consume taps on the foreground so the covered delete button cannot receive them.
        content.setOnClickListener { close() }
        reset()
    }

    private fun revealWidth(): Float = (content.right - deleteCard.left).toFloat().coerceAtLeast(0f)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onInteraction?.invoke(this)
                animator?.cancel()
                animator = null
                downX = event.x
                downY = event.y
                downOffset = content.translationX
                wasOpen = targetOpen
                downOnContent = event.x < content.right + content.translationX
                dragging = false
                yielded = false
                // Reserve the sequence before ViewPager2/NestedScrollView see the first MOVE.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!yielded && !dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (abs(dy) >= abs(dx) || (dx > 0 && downOffset == 0f)) {
                            yielded = true
                            parent?.requestDisallowInterceptTouchEvent(false)
                        } else {
                            dragging = true
                            deleteButton.isEnabled = false
                        }
                    }
                }
                if (dragging && !yielded) setOffset(downOffset + event.x - downX)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger cancels the swipe and never turns into a delete tap.
                dragging = true
                yielded = true
                settle(wasOpen)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && wasOpen && downOnContent &&
            !dragging && !yielded
        ) {
            val cancelEvent = MotionEvent.obtain(event).apply {
                action = MotionEvent.ACTION_CANCEL
            }
            try {
                super.dispatchTouchEvent(cancelEvent)
            } finally {
                cancelEvent.recycle()
            }
            close()
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val open = if (event.actionMasked == MotionEvent.ACTION_CANCEL || yielded) {
                        wasOpen
                    } else {
                        revealWidth() > 0f && -content.translationX >= revealWidth() / 2f
                    }
                    settle(open)
                } else {
                    // Taps may interrupt either animation. Finish the current target rather
                    // than leaving a partially translated card after the animator is cancelled.
                    settle(targetOpen)
                }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = dragging

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && !dragging && !yielded) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        close()
        return true
    }

    fun close() = settle(false)

    fun reset() {
        animator?.cancel()
        animator = null
        dragging = false
        yielded = false
        downOnContent = false
        targetOpen = false
        setOffset(0f)
        deleteButton.isEnabled = false
    }

    private fun setOffset(offset: Float) {
        content.translationX = offset.coerceIn(-revealWidth(), 0f)
        deleteCard.visibility = if (content.translationX < 0f) View.VISIBLE else View.INVISIBLE
    }

    private fun settle(open: Boolean) {
        animator?.cancel()
        targetOpen = open
        val target = if (open) -revealWidth() else 0f
        deleteButton.isEnabled = open
        animator = ValueAnimator.ofFloat(content.translationX, target).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { setOffset(it.animatedValue as Float) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }
}
