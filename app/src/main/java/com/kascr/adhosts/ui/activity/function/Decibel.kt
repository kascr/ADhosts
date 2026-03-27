package com.kascr.adhosts.ui.activity.function

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.kascr.adhosts.R
import com.kascr.adhosts.databinding.ActivityDecibelBinding
import com.kascr.adhosts.ui.base.BaseActivity
import com.kascr.adhosts.utils.DecibelMeter

/**
 * 分贝仪 Activity
 * 完全参照参考项目 decibel.tsx 实现：
 * - 圆形仪表盘 + 脉冲缩放动画（1.0→1.08→1.0 循环）
 * - 动态颜色仪表盘边框和数值
 * - 当前/峰值双卡片
 * - 五段彩色噪音等级条（opacity 切换）
 * - 状态徽章（动态颜色+文字）
 * - 开始/停止按钮（success/error 色切换）
 */
class Decibel : BaseActivity<ActivityDecibelBinding>() {

    private lateinit var decibelMeter: DecibelMeter
    private var isRunning = false
    private var maxDecibel = 0.0
    private var pulseAnimator: ValueAnimator? = null

    // 参考项目的颜色常量
    companion object {
        // 噪音等级颜色（与参考项目 levelColors 完全一致）
        private val LEVEL_COLORS = intArrayOf(
            Color.parseColor("#00897B"),  // 非常安静
            Color.parseColor("#43A047"),  // 安静
            Color.parseColor("#FB8C00"),  // 正常
            Color.parseColor("#E53935"),  // 嘈杂
            Color.parseColor("#B71C1C")   // 危险噪音
        )
        // 噪音等级阈值（与参考项目 levelThresholds 一致）
        private val LEVEL_THRESHOLDS = doubleArrayOf(0.0, 30.0, 50.0, 70.0, 85.0)

        // 参考项目主题色
        private val COLOR_SUCCESS = Color.parseColor("#26A69A")  // colors.success (dark)
        private val COLOR_ERROR = Color.parseColor("#F87171")    // colors.error (dark)
        private val COLOR_MUTED = Color.parseColor("#8B949E")    // colors.muted (dark)
        private val COLOR_BORDER = Color.parseColor("#30363D")   // colors.border (dark)
    }

    override fun getViewBinding(): ActivityDecibelBinding {
        return ActivityDecibelBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式状态栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 动态获取状态栏高度，给根布局设置顶部 padding
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        // 返回按钮（参考项目 backBtn → router.back()）
        binding.backButton.setOnClickListener { finish() }

        // 初始化 DecibelMeter，传入回调更新 UI
        decibelMeter = DecibelMeter(this, object : DecibelMeter.DecibelCallback {
            override fun onDecibelUpdate(db: Double) {
                runOnUiThread { updateUI(db) }
            }
        })

        // 控制按钮点击事件
        binding.controlButton.setOnClickListener {
            if (isRunning) {
                stopMeasuring()
            } else {
                startMeasuring()
            }
        }

        // 初始化 UI 状态
        resetUI()
    }

    /**
     * 开始测量（参考项目 startMeasuring）
     */
    private fun startMeasuring() {
        isRunning = true
        maxDecibel = 0.0

        // 更新按钮状态：停止测量（error 色，参考项目 colors.error）
        binding.controlButton.apply {
            text = "停止测量"
            setIconResource(R.drawable.ic_stop)
            setBackgroundColor(COLOR_ERROR)
        }

        // 启动脉冲动画（参考项目 pulseAnim: 1.0→1.08→1.0, 600ms+600ms 循环）
        startPulseAnimation()

        // 启动分贝仪
        decibelMeter.start()
    }

    /**
     * 停止测量（参考项目 stopMeasuring）
     */
    private fun stopMeasuring() {
        isRunning = false
        decibelMeter.stop()

        // 停止脉冲动画，恢复缩放（参考项目 pulseAnim.setValue(1)）
        pulseAnimator?.cancel()
        binding.gaugeOuter.scaleX = 1f
        binding.gaugeOuter.scaleY = 1f

        // 恢复按钮状态：开始测量（success 色，参考项目 colors.success）
        binding.controlButton.apply {
            text = "开始测量"
            setIconResource(R.drawable.ic_waveform)
            setBackgroundColor(COLOR_SUCCESS)
        }

        // 重置仪表盘颜色（参考项目：isRunning=false 时 gaugeColor→colors.muted）
        binding.dbValueText.setTextColor(COLOR_MUTED)

        // 重置外圆边框颜色（参考项目：isRunning=false 时 borderColor→colors.border）
        setGaugeOuterBorderColor(COLOR_BORDER)

        // 重置徽章（参考项目：isRunning=false 时 "未测量", backgroundColor→colors.muted）
        binding.categoryBadge.apply {
            text = "未测量"
            background = createRoundedBadge(COLOR_MUTED)
        }

        // 重置等级条
        updateLevelSegments(0.0)
    }

    /**
     * 根据分贝值更新所有 UI 元素（参考项目 intervalRef callback 逻辑）
     */
    private fun updateUI(db: Double) {
        // 更新峰值（参考项目 setMaxDecibel(prev => Math.max(prev, db))）
        if (db > maxDecibel) maxDecibel = db

        // 更新大数字显示（参考项目 decibelValue: db.toFixed(1)）
        binding.dbValueText.text = String.format("%.1f", db)
        binding.currentDbText.text = String.format("%.1f", db)
        binding.maxDbText.text = String.format("%.1f", maxDecibel)

        // 获取当前分贝等级颜色（参考项目 gaugeColor interpolation）
        val gaugeColor = getGaugeColor(db)

        // 更新仪表盘数值颜色（参考项目：isRunning 时 color→gaugeColor）
        binding.dbValueText.setTextColor(gaugeColor)

        // 更新外圆边框颜色（参考项目：isRunning 时 borderColor→gaugeColor）
        setGaugeOuterBorderColor(gaugeColor)

        // 更新徽章（参考项目 categoryBadge）
        val category = getDecibelCategory(db)
        binding.categoryBadge.apply {
            text = category.first
            background = createRoundedBadge(Color.parseColor(category.second))
        }

        // 更新噪音等级条
        updateLevelSegments(db)
    }

    /**
     * 获取仪表盘颜色（参考项目 gaugeColor interpolation）
     * inputRange: [0, 0.3, 0.5, 0.7, 0.85, 1]
     * outputRange: ["#00897B", "#43A047", "#FB8C00", "#E53935", "#C62828", "#B71C1C"]
     */
    private fun getGaugeColor(db: Double): Int {
        val ratio = (db / 100.0).coerceIn(0.0, 1.0).toFloat()
        val stops = floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 0.85f, 1f)
        val colors = intArrayOf(
            Color.parseColor("#00897B"),
            Color.parseColor("#43A047"),
            Color.parseColor("#FB8C00"),
            Color.parseColor("#E53935"),
            Color.parseColor("#C62828"),
            Color.parseColor("#B71C1C")
        )

        // 找到当前 ratio 所在的区间并插值
        for (i in 0 until stops.size - 1) {
            if (ratio <= stops[i + 1]) {
                val localRatio = (ratio - stops[i]) / (stops[i + 1] - stops[i])
                return ArgbEvaluator().evaluate(localRatio, colors[i], colors[i + 1]) as Int
            }
        }
        return colors.last()
    }

    /**
     * 动态设置外圆边框颜色（参考项目 gaugeOuter borderColor 动态变化）
     */
    private fun setGaugeOuterBorderColor(color: Int) {
        val bg = binding.gaugeOuter.background
        if (bg is GradientDrawable) {
            bg.setStroke(3.dpToPx(), color)
        } else {
            // 重新创建
            binding.gaugeOuter.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#161B22"))
                setStroke(3.dpToPx(), color)
            }
        }
    }

    /**
     * 更新五段噪音等级进度条的高亮状态
     * 参考项目逻辑：opacity = decibel >= threshold ? 1 : 0.3
     */
    private fun updateLevelSegments(db: Double) {
        val segments = listOf(
            binding.levelSeg0,
            binding.levelSeg1,
            binding.levelSeg2,
            binding.levelSeg3,
            binding.levelSeg4
        )

        segments.forEachIndexed { index, view ->
            val isActive = db >= LEVEL_THRESHOLDS[index]
            view.alpha = if (isActive) 1.0f else 0.3f
        }
    }

    /**
     * 根据分贝值返回等级标签和颜色（参考项目 getDecibelCategory 完全一致）
     */
    private fun getDecibelCategory(db: Double): Pair<String, String> {
        return when {
            db < 30 -> Pair("非常安静", "#00897B")
            db < 50 -> Pair("安静", "#43A047")
            db < 70 -> Pair("正常", "#FB8C00")
            db < 85 -> Pair("嘈杂", "#E53935")
            else    -> Pair("危险噪音", "#B71C1C")
        }
    }

    /**
     * 创建带圆角的彩色徽章背景（参考项目 categoryBadge: borderRadius=20）
     */
    private fun createRoundedBadge(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 20.dpToPx().toFloat()
        }
    }

    /**
     * 启动外圆脉冲动画
     * 参考项目：Animated.loop(sequence([
     *   timing(pulseAnim, toValue:1.08, duration:600, easing:inOut(ease)),
     *   timing(pulseAnim, toValue:1, duration:600, easing:inOut(ease))
     * ]))
     */
    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.08f, 1.0f).apply {
            duration = 1200  // 600ms up + 600ms down
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                binding.gaugeOuter.scaleX = scale
                binding.gaugeOuter.scaleY = scale
                // 同时缩放内圆，保持视觉一致
                binding.gaugeInner.scaleX = scale
                binding.gaugeInner.scaleY = scale
            }
            start()
        }
    }

    /**
     * 重置 UI 到初始状态
     */
    private fun resetUI() {
        binding.dbValueText.setTextColor(COLOR_MUTED)
        binding.categoryBadge.background = createRoundedBadge(COLOR_MUTED)
        updateLevelSegments(0.0)
    }

    /**
     * dp 转 px 扩展函数
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        decibelMeter.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        decibelMeter.stop()
    }
}
