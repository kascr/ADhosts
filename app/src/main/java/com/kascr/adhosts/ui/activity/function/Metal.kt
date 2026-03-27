package com.kascr.adhosts.ui.activity.function

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.kascr.adhosts.R
import com.kascr.adhosts.databinding.ActivityMetalBinding
import com.kascr.adhosts.ui.base.BaseActivity
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 金属检测 Activity
 */
class Metal : BaseActivity<ActivityMetalBinding>(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var magneticSensor: Sensor? = null

    // 基准磁场强度（校准阶段的平均值）
    private var baselineStrength = -1f
    private var calibrationSamples = mutableListOf<Float>()
    private var isCalibrating = true
    private val CALIBRATION_SAMPLE_COUNT = 10

    // 平滑过渡用的当前值
    private var smoothDelta = 0f

    // 节流控制：每 200ms 更新一次 UI
    private val handler = Handler(Looper.getMainLooper())
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL_MS = 200L

    // 最新原始传感器数据
    private var latestRawX = 0f
    private var latestRawY = 0f
    private var latestRawZ = 0f

    companion object {
        // 轴颜色
        private val COLOR_X = Color.parseColor("#E53935")
        private val COLOR_Y = Color.parseColor("#00897B")
        private val COLOR_Z = Color.parseColor("#1565C0")

        // 金属检测阈值（μT 变化量）
        private const val THRESHOLD_WEAK   = 5f    // 轻微金属
        private const val THRESHOLD_MEDIUM = 15f   // 中等金属
        private const val THRESHOLD_STRONG = 30f   // 强金属/大型金属

        // 进度条最大变化量（超过此值进度条满格）
        private const val MAX_DELTA = 50f

        // 平滑因子
        private const val SMOOTH_FACTOR = 0.2f
    }

    override fun getViewBinding(): ActivityMetalBinding {
        return ActivityMetalBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        binding.backButton.setOnClickListener { finish() }

        // 初始化传感器
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magneticSensor == null) {
            binding.unavailableCard.visibility = View.VISIBLE
            binding.contentLayout.visibility = View.GONE
        } else {
            // 显示校准中状态
            showCalibratingState()
        }

        // 初始化等级徽章
        binding.levelBadge.background = createRoundedBadge(Color.parseColor("#00897B"))

        // 初始化进度条为 0%
        setBarProgress(binding.barX, 0f)
        setBarProgress(binding.barY, 0f)
        setBarProgress(binding.barZ, 0f)
    }

    override fun onResume() {
        super.onResume()
        // 重新校准
        resetCalibration()
        magneticSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    private fun resetCalibration() {
        isCalibrating = true
        baselineStrength = -1f
        calibrationSamples.clear()
        showCalibratingState()
    }

    private fun showCalibratingState() {
        binding.totalStrengthText.text = "---"
        binding.totalStrengthText.setTextColor(Color.parseColor("#9E9E9E"))
        binding.levelBadge.text = getString(R.string.metal_calibrating)
        binding.levelBadge.background = createRoundedBadge(Color.parseColor("#9E9E9E"))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                latestRawX = it.values[0]
                latestRawY = it.values[1]
                latestRawZ = it.values[2]

                val currentStrength = sqrt(
                    (latestRawX * latestRawX + latestRawY * latestRawY + latestRawZ * latestRawZ).toDouble()
                ).toFloat()

                // 校准阶段：收集初始样本
                if (isCalibrating) {
                    calibrationSamples.add(currentStrength)
                    if (calibrationSamples.size >= CALIBRATION_SAMPLE_COUNT) {
                        baselineStrength = calibrationSamples.average().toFloat()
                        isCalibrating = false
                    }
                    return
                }

                // 节流：每 200ms 更新一次 UI
                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= UPDATE_INTERVAL_MS) {
                    lastUpdateTime = now
                    updateUI(latestRawX, latestRawY, latestRawZ, currentStrength)
                }
            }
        }
    }

    private fun updateUI(rawX: Float, rawY: Float, rawZ: Float, currentStrength: Float) {
        // 1. 计算磁场变化量（与基准的差值）
        val delta = abs(currentStrength - baselineStrength)

        // 2. 显示变化量（金属检测值）
        binding.totalStrengthText.text = String.format("%.1f", delta)

        // 3. 获取检测等级
        val level = getMetalLevel(delta)
        val levelColor = Color.parseColor(level.second)
        binding.levelBadge.apply {
            text = level.first
            background = createRoundedBadge(levelColor)
        }
        binding.totalStrengthText.setTextColor(levelColor)

        // 4. 更新三轴文本（显示与基准的变化量）
        binding.textViewX.text = String.format("%.2f μT", rawX)
        binding.textViewY.text = String.format("%.2f μT", rawY)
        binding.textViewZ.text = String.format("%.2f μT", rawZ)

        // 5. 平滑过渡进度条（基于变化量百分比）
        val targetDelta = (delta / MAX_DELTA).coerceIn(0f, 1f)
        smoothDelta = smoothValue(smoothDelta, targetDelta)

        // 各轴进度条显示各轴占总变化量的比例
        val totalAbs = abs(rawX) + abs(rawY) + abs(rawZ)
        val targetX = if (totalAbs > 0f) (abs(rawX) / totalAbs * smoothDelta) else 0f
        val targetY = if (totalAbs > 0f) (abs(rawY) / totalAbs * smoothDelta) else 0f
        val targetZ = if (totalAbs > 0f) (abs(rawZ) / totalAbs * smoothDelta) else 0f

        setBarProgress(binding.barX, targetX)
        setBarProgress(binding.barY, targetY)
        setBarProgress(binding.barZ, targetZ)
    }

    private fun smoothValue(current: Float, target: Float): Float {
        return current + SMOOTH_FACTOR * (target - current)
    }

    private fun setBarProgress(barView: View, progress: Float) {
        val parent = barView.parent
        if (parent is LinearLayout) {
            val lp = barView.layoutParams as LinearLayout.LayoutParams
            lp.weight = progress.coerceIn(0.001f, 1f)
            lp.width = 0
            barView.layoutParams = lp

            val spacer = parent.getChildAt(1)
            if (spacer != null) {
                val spacerLp = spacer.layoutParams as LinearLayout.LayoutParams
                spacerLp.weight = (1f - progress).coerceIn(0f, 1f)
                spacerLp.width = 0
                spacer.layoutParams = spacerLp
            }
        }
    }

    /**
     * 根据磁场变化量（Δ）判断金属检测等级
     */
    private fun getMetalLevel(delta: Float): Pair<String, String> {
        return when {
            delta < THRESHOLD_WEAK   -> Pair(getString(R.string.metal_no_metal),     "#00897B")
            delta < THRESHOLD_MEDIUM -> Pair(getString(R.string.metal_weak_metal),   "#43A047")
            delta < THRESHOLD_STRONG -> Pair(getString(R.string.metal_medium_metal), "#FB8C00")
            else                     -> Pair(getString(R.string.metal_strong_metal), "#E53935")
        }
    }

    private fun createRoundedBadge(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = (20 * resources.displayMetrics.density)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
