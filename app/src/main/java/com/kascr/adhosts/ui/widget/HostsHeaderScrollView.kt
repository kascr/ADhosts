package com.kascr.adhosts.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Keep a usable list viewport on short screens and when translated controls wrap. */
class HostsHeaderScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val bounded = if (available > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // Reserve space for the fixed subscription actions and list, including landscape.
            val fraction = if (available / resources.displayMetrics.density >= 480) 0.5f else 0.3f
            MeasureSpec.makeMeasureSpec((available * fraction).toInt(), MeasureSpec.AT_MOST)
        } else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, bounded)
    }
}
