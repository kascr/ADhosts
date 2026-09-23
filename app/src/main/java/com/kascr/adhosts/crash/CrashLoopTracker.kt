package com.kascr.adhosts.crash

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object CrashLoopTracker {

    private const val HISTORY_FILE_NAME = "crash_timestamps"
    private const val CRASH_WINDOW_MS = 2 * 60 * 1_000L
    private const val CRASH_LIMIT = 3

    @Synchronized
    fun recordCrash(context: Context, timestamp: Long = System.currentTimeMillis()): Boolean {
        val timestamps = recentTimestamps(context, timestamp).toMutableList().apply {
            add(timestamp)
        }
        writeTimestamps(context, timestamps)
        return timestamps.size >= CRASH_LIMIT
    }

    fun isLoopDetected(context: Context, timestamp: Long = System.currentTimeMillis()): Boolean =
        recentTimestamps(context, timestamp).size >= CRASH_LIMIT

    @Synchronized
    fun clear(context: Context) {
        runCatching { historyFile(context).delete() }
    }

    private fun recentTimestamps(context: Context, now: Long): List<Long> {
        val earliestAllowed = now - CRASH_WINDOW_MS
        return runCatching {
            historyFile(context)
                .takeIf(File::isFile)
                ?.readLines(Charsets.UTF_8)
                .orEmpty()
                .mapNotNull(String::toLongOrNull)
                .filter { it in earliestAllowed..now }
        }.getOrDefault(emptyList())
    }

    private fun writeTimestamps(context: Context, timestamps: List<Long>) {
        runCatching {
            val destination = historyFile(context)
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "$HISTORY_FILE_NAME.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(timestamps.joinToString(separator = "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun historyFile(context: Context): File =
        File(File(context.noBackupFilesDir, "crash_reports"), HISTORY_FILE_NAME)
}
