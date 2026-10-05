package com.kascr.adhosts.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import java.util.Locale

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

    @Test
    fun expandedToolsAndLargeTranslatedTextLeaveRoomForSubscriptions() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        for (tag in listOf("en", "de", "ru", "ja", "th")) {
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
                fontScale = 1.5f
            }
            val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme)
            for ((width, height) in listOf(320 to 640, 640 to 360)) {
                val root = LayoutInflater.from(context).inflate(R.layout.fragment_hosts, null) as ViewGroup
                root.findViewById<View>(R.id.toolsPanel).visibility = View.VISIBLE
                root.measure(exactly(width), exactly(height))
                root.layout(0, 0, width, height)
                val list = root.findViewById<RecyclerView>(R.id.recyclerView)
                assertTrue("$tag $width x $height: list hidden by header", list.height > list.paddingBottom)
                assertTrue(root.findViewById<View>(R.id.outlinedButton).isClickable)
                for (id in listOf(R.id.openButton, R.id.closeButton, R.id.updateButton)) {
                    val text = root.findViewById<TextView>(id).layout
                    assertTrue("$tag $width x $height: action label truncated",
                        (0 until text.lineCount).all { text.getEllipsisCount(it) == 0 })
                }
            }
        }
    }
}
