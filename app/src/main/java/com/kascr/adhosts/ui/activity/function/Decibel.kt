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
import java.util.Locale

class Decibel : BaseActivity<ActivityDecibelBinding>() {

    private lateinit var decibelMeter: DecibelMeter
    private var isRunning = false
    private var maxDecibel = 0.0
    private var pulseAnimator: ValueAnimator? = null

    private val levelSegments by lazy {
        listOf(
            binding.levelSeg0,
            binding.levelSeg1,
            binding.levelSeg2,
            binding.levelSeg3,
            binding.levelSeg4
        )
    }

    override fun getViewBinding(): ActivityDecibelBinding {
        return ActivityDecibelBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredBackgroundTheme()
        super.onCreate(savedInstanceState)

        configureWindow()
        initControls()
        initDecibelMeter()
        resetUI()
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
        pulseAnimator?.cancel()
        decibelMeter.stop(notifyStopped = false)
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bindStoredBackground(binding.functionBackgroundImage, binding.functionBackgroundScrim)
        ViewCompat.setOnApplyWindowInsetsListener(binding.functionContentRoot) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, statusBarHeight, 0, 0)
            insets
        }
    }

    private fun initControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.controlButton.setOnClickListener {
            if (isRunning) {
                stopMeasuring()
            } else {
                startMeasuring()
            }
        }
    }

    private fun initDecibelMeter() {
        decibelMeter = DecibelMeter(this, object : DecibelMeter.DecibelCallback {
            override fun onMeasurementStarted() {
                runOnUiThread { showMeasuringState() }
            }

            override fun onMeasurementStopped() {
                runOnUiThread { showStoppedState() }
            }

            override fun onDecibelUpdate(db: Double) {
                runOnUiThread { updateUI(db) }
            }
        })
    }

    private fun startMeasuring() {
        maxDecibel = 0.0
        decibelMeter.start()
    }

    private fun stopMeasuring() {
        decibelMeter.stop()
    }

    private fun showMeasuringState() {
        isRunning = true
        maxDecibel = 0.0

        binding.controlButton.apply {
            text = getString(R.string.decibel_stop)
            setIconResource(R.drawable.ic_stop)
            setBackgroundColor(COLOR_ERROR)
        }

        startPulseAnimation()
    }

    private fun showStoppedState() {
        isRunning = false
        pulseAnimator?.cancel()
        pulseAnimator = null

        binding.gaugeOuter.scaleX = 1f
        binding.gaugeOuter.scaleY = 1f
        binding.gaugeInner.scaleX = 1f
        binding.gaugeInner.scaleY = 1f

        binding.controlButton.apply {
            text = getString(R.string.decibel_start)
            setIconResource(R.drawable.ic_waveform)
            setBackgroundColor(COLOR_SUCCESS)
        }

        binding.dbValueText.setTextColor(COLOR_MUTED)
        setGaugeOuterBorderColor(COLOR_BORDER)
        binding.categoryBadge.apply {
            text = getString(R.string.decibel_not_measured)
            background = createRoundedBadge(COLOR_MUTED)
        }
        updateLevelSegments(0.0)
    }

    private fun updateUI(db: Double) {
        if (db > maxDecibel) {
            maxDecibel = db
        }

        binding.dbValueText.text = String.format(Locale.US, "%.1f", db)
        binding.currentDbText.text = String.format(Locale.US, "%.1f", db)
        binding.maxDbText.text = String.format(Locale.US, "%.1f", maxDecibel)

        val gaugeColor = getGaugeColor(db)
        binding.dbValueText.setTextColor(gaugeColor)
        setGaugeOuterBorderColor(gaugeColor)

        val category = getDecibelCategory(db)
        binding.categoryBadge.apply {
            text = getString(category.labelRes)
            background = createRoundedBadge(category.color)
        }

        updateLevelSegments(db)
    }

    private fun getGaugeColor(db: Double): Int {
        val ratio = (db / 100.0).coerceIn(0.0, 1.0).toFloat()

        for (index in 0 until GAUGE_COLOR_STOPS.lastIndex) {
            if (ratio <= GAUGE_COLOR_STOPS[index + 1]) {
                val localRatio =
                    (ratio - GAUGE_COLOR_STOPS[index]) /
                        (GAUGE_COLOR_STOPS[index + 1] - GAUGE_COLOR_STOPS[index])
                return ArgbEvaluator().evaluate(localRatio, GAUGE_COLORS[index], GAUGE_COLORS[index + 1]) as Int
            }
        }

        return GAUGE_COLORS.last()
    }

    private fun setGaugeOuterBorderColor(color: Int) {
        val background = binding.gaugeOuter.background
        if (background is GradientDrawable) {
            background.setStroke(3.dpToPx(), color)
        } else {
            binding.gaugeOuter.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#161B22"))
                setStroke(3.dpToPx(), color)
            }
        }
    }

    private fun updateLevelSegments(db: Double) {
        levelSegments.forEachIndexed { index, view ->
            view.alpha = if (db >= LEVEL_THRESHOLDS[index]) 1f else 0.3f
        }
    }

    private fun getDecibelCategory(db: Double): DecibelCategory {
        return when {
            db < 30 -> DecibelCategory(R.string.decibel_very_quiet, Color.parseColor("#00897B"))
            db < 50 -> DecibelCategory(R.string.decibel_quiet, Color.parseColor("#43A047"))
            db < 70 -> DecibelCategory(R.string.decibel_normal, Color.parseColor("#FB8C00"))
            db < 85 -> DecibelCategory(R.string.decibel_noisy, Color.parseColor("#E53935"))
            else -> DecibelCategory(R.string.decibel_danger_noise, Color.parseColor("#B71C1C"))
        }
    }

    private fun createRoundedBadge(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 20.dpToPx().toFloat()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.08f, 1.0f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                binding.gaugeOuter.scaleX = scale
                binding.gaugeOuter.scaleY = scale
                binding.gaugeInner.scaleX = scale
                binding.gaugeInner.scaleY = scale
            }
            start()
        }
    }

    private fun resetUI() {
        binding.dbValueText.setTextColor(COLOR_MUTED)
        binding.categoryBadge.text = getString(R.string.decibel_not_measured)
        binding.categoryBadge.background = createRoundedBadge(COLOR_MUTED)
        setGaugeOuterBorderColor(COLOR_BORDER)
        updateLevelSegments(0.0)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private data class DecibelCategory(
        val labelRes: Int,
        val color: Int
    )

    companion object {
        private val LEVEL_THRESHOLDS = doubleArrayOf(0.0, 30.0, 50.0, 70.0, 85.0)
        private val GAUGE_COLOR_STOPS = floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 0.85f, 1f)
        private val GAUGE_COLORS = intArrayOf(
            Color.parseColor("#00897B"),
            Color.parseColor("#43A047"),
            Color.parseColor("#FB8C00"),
            Color.parseColor("#E53935"),
            Color.parseColor("#C62828"),
            Color.parseColor("#B71C1C")
        )

        private val COLOR_SUCCESS = Color.parseColor("#26A69A")
        private val COLOR_ERROR = Color.parseColor("#F87171")
        private val COLOR_MUTED = Color.parseColor("#8B949E")
        private val COLOR_BORDER = Color.parseColor("#30363D")
    }
}
