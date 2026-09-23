package com.kascr.adhosts.ui.base

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewbinding.ViewBinding
import com.kascr.adhosts.R
import com.kascr.adhosts.utils.AppBackgroundManager

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private lateinit var _binding: VB
    protected val binding get() = _binding

    private var activityBackgroundBitmap: Bitmap? = null
    private var windowBackgroundBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prepareStoredWindowBackground()
        super.onCreate(savedInstanceState)

        _binding = getViewBinding()
        setContentView(binding.root)
    }

    protected abstract fun getViewBinding(): VB

    protected fun applyStoredBackgroundTheme() {
        setTheme(R.style.AppTheme_NoAnimation_Background)
    }

    protected fun bindStoredBackground(backgroundImage: ImageView, backgroundScrim: View) {
        if (isFinishing || isDestroyed) {
            return
        }

        backgroundScrim.setBackgroundColor(resolveThemeColor(R.attr.mainBackgroundScrimColor))

        if (backgroundImage.width == 0 || backgroundImage.height == 0) {
            binding.root.post { bindStoredBackground(backgroundImage, backgroundScrim) }
            return
        }

        val bitmap = windowBackgroundBitmap?.takeIf { !it.isRecycled }
            ?: AppBackgroundManager.decodeBackgroundBitmap(
                this,
                backgroundImage.width,
                backgroundImage.height
            )

        activityBackgroundBitmap?.takeIf { it != bitmap && !it.isRecycled }?.recycle()
        activityBackgroundBitmap = bitmap
        updateBoundBackground(backgroundImage, backgroundScrim, bitmap)
    }

    protected fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onDestroy() {
        activityBackgroundBitmap?.takeIf { !it.isRecycled }?.recycle()
        windowBackgroundBitmap?.takeIf { it != activityBackgroundBitmap && !it.isRecycled }?.recycle()
        super.onDestroy()
    }

    private fun clearBoundBackground(backgroundImage: ImageView, backgroundScrim: View) {
        activityBackgroundBitmap?.takeIf { !it.isRecycled }?.recycle()
        activityBackgroundBitmap = null

        backgroundImage.setImageDrawable(null)
        backgroundImage.visibility = View.INVISIBLE
        backgroundScrim.visibility = View.INVISIBLE

        val backgroundColor = ContextCompat.getColor(this, R.color.md_theme_background)
        binding.root.setBackgroundColor(backgroundColor)
        window.decorView.setBackgroundColor(backgroundColor)
    }

    private fun updateBoundBackground(
        backgroundImage: ImageView,
        backgroundScrim: View,
        bitmap: Bitmap?
    ) {
        if (bitmap == null) {
            clearBoundBackground(backgroundImage, backgroundScrim)
            return
        }

        backgroundImage.setImageBitmap(bitmap)
        backgroundImage.visibility = View.VISIBLE
        backgroundScrim.visibility = View.VISIBLE
    }

    private fun prepareStoredWindowBackground() {
        val metrics = resources.displayMetrics
        val bitmap = AppBackgroundManager.decodeBackgroundBitmap(
            this,
            metrics.widthPixels,
            metrics.heightPixels
        ) ?: return

        windowBackgroundBitmap = bitmap
        val drawable = CenterCropBitmapDrawable(bitmap)
        window.setBackgroundDrawable(drawable)
        window.decorView.background = drawable
    }

    private class CenterCropBitmapDrawable(
        private val bitmap: Bitmap
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (bitmap.isRecycled || bounds.isEmpty) return

            val scale = maxOf(
                bounds.width().toFloat() / bitmap.width.toFloat(),
                bounds.height().toFloat() / bitmap.height.toFloat()
            )
            val left = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
            val top = bounds.top + (bounds.height() - bitmap.height * scale) / 2f

            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
