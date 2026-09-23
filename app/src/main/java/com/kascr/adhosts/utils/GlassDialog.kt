package com.kascr.adhosts.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AlertDialog
import com.kascr.adhosts.R

object GlassDialog {

    fun show(dialog: android.app.Dialog) {
        configureWindow(dialog.window)
        dialog.show()
        animateEntrance(dialog.window)
    }

    fun show(dialog: AlertDialog) {
        configureWindow(dialog.window)
        dialog.setOnShowListener {
            val panel = dialog.findViewById<View>(androidx.appcompat.R.id.parentPanel)
                ?: return@setOnShowListener
            panel.setBackgroundResource(R.drawable.dialog_glass_background)
            animateEntrance(dialog.window)
        }
        dialog.show()
    }

    private fun animateEntrance(window: Window?) {
        val decorView = window?.decorView ?: return
        val offset = decorView.resources.displayMetrics.density * ENTER_OFFSET_DP
        decorView.apply {
            alpha = 0f
            scaleX = ENTER_SCALE
            scaleY = ENTER_SCALE
            translationY = offset
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(ENTER_INTERPOLATOR)
                .start()
        }
    }

    private fun configureWindow(window: Window?) {
        window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(DIM_AMOUNT)
        window.setWindowAnimations(0)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = BLUR_RADIUS_PX
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    private const val BLUR_RADIUS_PX = 48
    private const val DIM_AMOUNT = 0.28f
    private const val ENTER_SCALE = 0.94f
    private const val ENTER_OFFSET_DP = 14f
    private const val ENTER_DURATION_MS = 220L
    private val ENTER_INTERPOLATOR = OvershootInterpolator(0.7f)
}
