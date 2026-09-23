package com.kascr.adhosts.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReport(
    val time: String,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val thread: String,
    val exceptionName: String,
    val message: String,
    val stackTrace: String,
    val fullText: String,
    val isCrashLoop: Boolean
)

object CrashReportStore {

    private const val DIRECTORY_NAME = "crash_reports"
    private const val PENDING_FILE_NAME = "pending_crash.txt"
    private const val MAX_REPORT_LENGTH = 128 * 1024

    fun save(context: Context, thread: Thread, throwable: Throwable): CrashReport? = runCatching {
        val report = createReport(context, thread, throwable)
        val directory = File(context.noBackupFilesDir, DIRECTORY_NAME).apply { mkdirs() }
        val destination = File(directory, PENDING_FILE_NAME)
        val temporary = File(directory, "$PENDING_FILE_NAME.tmp")

        FileOutputStream(temporary).use { output ->
            output.write(report.fullText.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }

        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        CrashLoopTracker.recordCrash(context)
        report
    }.getOrNull()

    fun load(context: Context): CrashReport? = runCatching {
        val text = pendingFile(context).readText(Charsets.UTF_8)
        val stackMarker = "\n--- Stack trace ---\n"
        val stackTrace = text.substringAfter(stackMarker, missingDelimiterValue = text)
        CrashReport(
            time = text.headerValue("Time"),
            appVersion = text.headerValue("App"),
            androidVersion = text.headerValue("Android"),
            device = text.headerValue("Device"),
            thread = text.headerValue("Thread"),
            exceptionName = text.headerValue("Exception"),
            message = text.headerValue("Message"),
            stackTrace = stackTrace,
            fullText = text,
            isCrashLoop = CrashLoopTracker.isLoopDetected(context)
        )
    }.getOrNull()

    fun hasPending(context: Context): Boolean = pendingFile(context).isFile

    fun clear(context: Context) {
        runCatching { pendingFile(context).delete() }
    }

    private fun createReport(context: Context, thread: Thread, throwable: Throwable): CrashReport {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName.orEmpty().ifBlank { "unknown" }
        val versionCode = packageInfo.longVersionCode
        val appVersion = "$versionName ($versionCode)"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val device = listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val exceptionName = throwable.javaClass.name
        val message = throwable.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            .orEmpty()
        val rawStackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString()
        val stackTrace = sanitize(context, rawStackTrace).take(MAX_REPORT_LENGTH)

        val reportText = buildString {
            appendLine("ADhosts Crash Report")
            appendLine("Time: $time")
            appendLine("App: $appVersion")
            appendLine("Android: $androidVersion")
            appendLine("Device: $device")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("Exception: $exceptionName")
            appendLine("Message: ${sanitize(context, message).ifBlank { "<none>" }}")
            appendLine()
            appendLine("--- Stack trace ---")
            append(stackTrace)
        }.take(MAX_REPORT_LENGTH)

        return CrashReport(
            time = time,
            appVersion = appVersion,
            androidVersion = androidVersion,
            device = device,
            thread = "${thread.name} (id=${thread.id})",
            exceptionName = exceptionName,
            message = sanitize(context, message),
            stackTrace = stackTrace,
            fullText = reportText,
            isCrashLoop = false
        )
    }

    private fun sanitize(context: Context, value: String): String {
        var sanitized = value
        val replacements = linkedMapOf<String, String>()
        context.dataDir.absolutePath.let { replacements[it] = "<app-data>" }
        context.filesDir.absolutePath.let { replacements[it] = "<app-files>" }
        context.cacheDir.absolutePath.let { replacements[it] = "<app-cache>" }
        context.getExternalFilesDir(null)?.absolutePath?.let { replacements[it] = "<external-files>" }
        context.externalCacheDir?.absolutePath?.let { replacements[it] = "<external-cache>" }

        replacements.entries
            .sortedByDescending { it.key.length }
            .forEach { (path, replacement) -> sanitized = sanitized.replace(path, replacement) }
        return sanitized
    }

    private fun pendingFile(context: Context): File =
        File(File(context.noBackupFilesDir, DIRECTORY_NAME), PENDING_FILE_NAME)

    private fun String.headerValue(name: String): String =
        lineSequence()
            .firstOrNull { it.startsWith("$name: ") }
            ?.substringAfter(": ")
            .orEmpty()
}
