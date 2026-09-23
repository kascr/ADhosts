package com.kascr.adhosts.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kascr.adhosts.R
import com.kascr.adhosts.crash.CrashLoopTracker
import com.kascr.adhosts.crash.CrashReport
import com.kascr.adhosts.crash.CrashReportStore
import com.kascr.adhosts.databinding.ActivityCrashBinding

class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding
    private lateinit var report: CrashReport
    private var stackExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val pendingReport = CrashReportStore.load(this)
        if (pendingReport == null) {
            openApp()
            return
        }
        report = pendingReport

        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        bindReport()
        bindActions()
        playEntranceAnimation()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exitApp()
        })
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.crashRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun bindReport() = with(binding) {
        crashLoopWarning.visibility = if (report.isCrashLoop) View.VISIBLE else View.GONE
        crashAppValue.text = report.appVersion.ifBlank { getString(R.string.crash_unknown) }
        crashTimeValue.text = report.time.ifBlank { getString(R.string.crash_unknown) }
        crashSystemValue.text = report.androidVersion.ifBlank { getString(R.string.crash_unknown) }
        crashDeviceValue.text = report.device.ifBlank { getString(R.string.crash_unknown) }
        crashExceptionName.text = report.exceptionName
            .substringAfterLast('.')
            .ifBlank { getString(R.string.crash_unknown_exception) }
        crashExceptionMessage.text = report.message.ifBlank {
            getString(R.string.crash_no_exception_message)
        }
        crashStackText.text = report.stackTrace
        crashRestartButton.setText(
            if (report.isCrashLoop) R.string.crash_clear_history else R.string.crash_restart
        )
        crashRestartButton.setIconResource(
            if (report.isCrashLoop) R.drawable.ic_trash else R.drawable.ic_restart
        )
        updateStackVisibility()
    }

    private fun bindActions() = with(binding) {
        crashStackToggle.setOnClickListener {
            stackExpanded = !stackExpanded
            updateStackVisibility()
        }
        crashRestartButton.setOnClickListener {
            if (report.isCrashLoop) {
                clearCrashHistory()
            } else {
                CrashReportStore.clear(this@CrashActivity)
                openApp()
            }
        }
        crashCopyButton.setOnClickListener { copyReport() }
        crashShareButton.setOnClickListener { shareReport() }
        crashExitButton.setOnClickListener { exitApp() }
    }

    private fun updateStackVisibility() = with(binding) {
        crashStackContainer.visibility = if (stackExpanded) View.VISIBLE else View.GONE
        crashStackToggle.setText(
            if (stackExpanded) R.string.crash_collapse_details else R.string.crash_expand_details
        )
        crashStackToggle.setIconResource(
            if (stackExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_report_title), report.fullText))
        Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_share_subject))
            putExtra(Intent.EXTRA_TEXT, report.fullText)
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.crash_share)))
        }.onFailure {
            Toast.makeText(this, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, StartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun exitApp() {
        CrashReportStore.clear(this)
        finishAndRemoveTask()
    }

    private fun clearCrashHistory() {
        CrashLoopTracker.clear(this)
        CrashReportStore.clear(this)
        Toast.makeText(this, R.string.crash_history_cleared, Toast.LENGTH_LONG).show()
        finishAndRemoveTask()
    }

    private fun playEntranceAnimation() {
        binding.crashContent.apply {
            alpha = 0f
            translationY = 24f * resources.displayMetrics.density
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280L)
                .start()
        }
    }
}
