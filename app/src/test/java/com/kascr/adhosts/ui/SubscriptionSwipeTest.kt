package com.kascr.adhosts.ui

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch
import com.kascr.adhosts.R
import com.kascr.adhosts.data.Subscription
import com.kascr.adhosts.ui.adapter.SubscriptionAdapter
import com.kascr.adhosts.ui.widget.SubscriptionSwipeLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SubscriptionSwipeTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var pager: ViewPager2
    private lateinit var list: RecyclerView
    private lateinit var scroll: NestedScrollView
    private lateinit var adapter: SubscriptionAdapter
    private val deleted = mutableListOf<String>()
    private val toggled = mutableListOf<Pair<String, Boolean>>()
    private var downTime = 0L

    @Before fun setUp() {
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.setup()
        list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            isNestedScrollingEnabled = false
        }
        adapter = SubscriptionAdapter(
            activity,
            (1..3).map { Subscription("Source $it", "https://example.com/$it") },
            deleted::add
        ) { url, enabled -> toggled += url to enabled }
        list.adapter = adapter
        scroll = NestedScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(list, LinearLayout.LayoutParams(-1, -2))
                addView(View(activity), LinearLayout.LayoutParams(-1, 2000))
            })
        }
        pager = ViewPager2(activity).apply {
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 2
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val view = if (viewType == 0) scroll else View(activity)
                    view.layoutParams = ViewGroup.LayoutParams(-1, -1)
                    return object : RecyclerView.ViewHolder(view) {}
                }
                override fun getItemViewType(position: Int) = position
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }
        activity.setContentView(pager)
        pager.measure(exactly(600), exactly(900))
        pager.layout(0, 0, 600, 900)
        idle()
        assertNotNull(list.findViewHolderForAdapterPosition(1))
    }

    @After fun tearDown() { controller.pause().stop().destroy() }

    @Test fun openingSecondRowClosesFirstAndDoesNotSwitchPage() {
        swipe(0, -180f)
        assertTrue(offset(0) < 0f)
        swipe(1, -180f)
        assertEquals(0f, offset(0), 0.1f)
        assertTrue(offset(1) < 0f)
        assertEquals(0, pager.currentItem)
        swipe(0, -180f)
        assertEquals(0f, offset(1), 0.1f)
        assertTrue(offset(0) < 0f)
    }

    @Test fun switchingRowsDuringOpeningAnimationDoesNotReopenOldRow() {
        swipe(0, -180f, wait = false)
        swipe(1, -180f)
        idle()
        assertEquals(0f, offset(0), 0.1f)
        assertTrue(offset(1) < 0f)
    }

    @Test fun rightSwipeAndContentTapCloseAnOpenRow() {
        swipe(0, -180f)
        swipe(0, 180f)
        assertEquals(0f, offset(0), 0.1f)
        swipe(0, -180f)
        val y = rowY(0)
        event(MotionEvent.ACTION_DOWN, 200f, y)
        event(MotionEvent.ACTION_UP, 200f, y)
        idle()
        assertEquals(0f, offset(0), 0.1f)
        assertTrue(deleted.isEmpty())
    }

    @Test fun exposedDeleteButtonDeletesExactlyItsOwnSubscription() {
        swipe(1, -180f)
        val button = row(1).findViewById<View>(R.id.deleteActionText)
        val location = IntArray(2).also(button::getLocationInWindow)
        val origin = IntArray(2).also(pager::getLocationInWindow)
        val x = location[0] - origin[0] + button.width / 2f
        val y = location[1] - origin[1] + button.height / 2f
        event(MotionEvent.ACTION_DOWN, x, y)
        event(MotionEvent.ACTION_UP, x, y)
        idle()
        assertEquals(listOf("https://example.com/2"), deleted)
    }

    @Test fun switchOnlyChangesItsOwnSubscription() {
        val toggle = row(1).findViewById<MaterialSwitch>(R.id.subscriptionEnabledSwitch)
        val location = IntArray(2).also(toggle::getLocationInWindow)
        val origin = IntArray(2).also(pager::getLocationInWindow)
        val x = location[0] - origin[0] + toggle.width / 2f
        val y = location[1] - origin[1] + toggle.height / 2f
        event(MotionEvent.ACTION_DOWN, x, y)
        event(MotionEvent.ACTION_UP, x, y)
        idle()
        assertEquals(listOf("https://example.com/2" to false), toggled)
        assertTrue(deleted.isEmpty())

        adapter.updateData((1..3).map {
            Subscription("Source $it", "https://example.com/$it", enabled = it != 2)
        })
        idle()
        assertFalse(row(1).findViewById<MaterialSwitch>(R.id.subscriptionEnabledSwitch).isChecked)
        assertTrue(row(0).findViewById<MaterialSwitch>(R.id.subscriptionEnabledSwitch).isChecked)
    }

    @Test fun verticalGestureScrollsParentWithoutRevealingOrDeleting() {
        val y = rowY(1)
        event(MotionEvent.ACTION_DOWN, 250f, y)
        for (i in 1..6) event(MotionEvent.ACTION_MOVE, 250f, y - i * 20f)
        event(MotionEvent.ACTION_UP, 250f, y - 120f)
        idle()
        assertTrue(scroll.scrollY > 0)
        assertEquals(0f, offset(0), 0.1f)
        assertTrue(deleted.isEmpty())
    }

    @Test fun cancelledSwipeReturnsToClosedAndDoesNotDelete() {
        val y = rowY(0)
        event(MotionEvent.ACTION_DOWN, 300f, y)
        event(MotionEvent.ACTION_MOVE, 150f, y)
        event(MotionEvent.ACTION_CANCEL, 150f, y)
        idle()
        assertEquals(0f, offset(0), 0.1f)
        assertTrue(deleted.isEmpty())
    }

    @Test fun rebindingDuringAnimationCannotLeaveAReusedRowOpen() {
        swipe(0, -180f, wait = false)
        val holder = list.findViewHolderForAdapterPosition(0) as SubscriptionAdapter.SubscriptionViewHolder
        adapter.onBindViewHolder(holder, 1)
        idle()
        assertEquals(0f, offset(0), 0.1f)
    }

    @Test fun shortSwipeDoesNotOpenOrDelete() {
        swipe(0, -20f)
        assertEquals(0f, offset(0), 0.1f)
        assertTrue(deleted.isEmpty())
    }

    private fun row(index: Int) = list.findViewHolderForAdapterPosition(index)!!.itemView as SubscriptionSwipeLayout
    private fun offset(index: Int) = row(index).findViewById<View>(R.id.contentCard).translationX
    private fun rowY(index: Int): Float {
        val position = IntArray(2).also(row(index)::getLocationInWindow)
        val origin = IntArray(2).also(pager::getLocationInWindow)
        return position[1] - origin[1] + row(index).height / 2f
    }

    private fun swipe(index: Int, delta: Float, wait: Boolean = true) {
        val y = rowY(index)
        event(MotionEvent.ACTION_DOWN, 300f, y)
        for (i in 1..6) event(MotionEvent.ACTION_MOVE, 300f + delta * i / 6, y)
        event(MotionEvent.ACTION_UP, 300f + delta, y)
        if (wait) idle()
    }

    private fun event(action: Int, x: Float, y: Float) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
        pager.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
