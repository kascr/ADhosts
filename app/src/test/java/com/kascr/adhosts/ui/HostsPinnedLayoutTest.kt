package com.kascr.adhosts.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kascr.adhosts.R
import com.kascr.adhosts.data.Subscription
import com.kascr.adhosts.ui.adapter.SubscriptionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
class HostsPinnedLayoutTest {

    @Test
    fun statusControlsAndSubscriptionHeaderStayFixedWhileRowsScroll() {
        val activity = Robolectric.buildActivity(Activity::class.java).get().apply {
            setTheme(R.style.AppTheme)
        }
        val root = LayoutInflater.from(activity)
            .inflate(R.layout.fragment_hosts, null) as ViewGroup
        activity.setContentView(root)
        root.findViewById<View>(R.id.manualRulesCard).visibility = View.VISIBLE

        val list = root.findViewById<RecyclerView>(R.id.recyclerView)
        val layoutManager = LinearLayoutManager(activity)
        list.layoutManager = layoutManager
        list.adapter = SubscriptionAdapter(
            activity,
            (1..15).map { Subscription("Source $it", "https://example.com/$it") },
            onDeleteClick = {}
        )
        root.measure(exactly(400), exactly(800))
        root.layout(0, 0, 400, 800)

        val status = root.findViewById<View>(R.id.statusCard)
        val title = root.findViewById<View>(R.id.subscriptionTitle)
        val statusTop = status.top
        val titleTop = title.top
        assertTrue("The subscription viewport must remain usable", list.height > list.paddingBottom)

        list.scrollBy(0, 350)

        assertTrue(layoutManager.findFirstVisibleItemPosition() > 0)
        assertEquals(statusTop, status.top)
        assertEquals(titleTop, title.top)
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
