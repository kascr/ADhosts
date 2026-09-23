package com.kascr.adhosts.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import com.kascr.adhosts.ui.activity.CrashActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

class CrashHandler private constructor(
    context: Context,
    private val fallbackHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    private val applicationContext = context.applicationContext
    private val handlingCrash = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (ExpectedExitGuard.isExpected()) {
            terminateProcess()
        }

        if (!handlingCrash.compareAndSet(false, true)) {
            fallbackHandler?.uncaughtException(thread, throwable)
            return
        }

        val saved = CrashReportStore.save(applicationContext, thread, throwable) != null
        if (saved) {
            runCatching {
                applicationContext.startActivity(
                    Intent(applicationContext, CrashActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                    }
                )
                // Give ActivityTaskManager enough time to start the isolated crash process.
                Thread.sleep(CRASH_SCREEN_START_GRACE_MS)
            }
        }

        terminateProcess()
    }

    private fun terminateProcess(): Nothing {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    companion object {
        private const val CRASH_SCREEN_START_GRACE_MS = 350L

        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, current))
        }
    }
}
