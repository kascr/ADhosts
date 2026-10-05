package com.kascr.adhosts.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.kascr.adhosts.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** MT can write only the editing copy. Validated rules are committed by ADhosts on return. */
object ManualHostsEditor {
    const val WEBSITE = "https://mt.cc/download/"
    internal val packages = listOf("bin.mt.plus", "bin.mt.plus.canary")
    private const val DIRECTORY = "manual_hosts_editor"
    private const val MAX_BYTES = ManualHostsRuleManager.MAX_TEXT_BYTES
    private val lock = Any()
    private val gson = Gson()
    private val saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents = saved.asSharedFlow()
    private var activeWriter: File? = null
    private val saveHandler by lazy { Handler(HandlerThread("ManualHostsSave").apply { start() }.looper) }
    class DraftRecoveryRequired : IOException()

    data class EditorApp(val packageName: String, val label: String)
    data class PendingEdit(
        val hash: String,
        val parsed: ManualRulesParseResult,
        val conflicting: Boolean
    )
    private data class Session(
        val baseline: String,
        val original: String,
        val reviewed: String? = null,
        val awaitingSave: Boolean = false
    )

    fun installedEditors(context: Context): List<EditorApp> = packages.mapNotNull { name ->
        runCatching {
            val info = context.packageManager.getPackageInfo(name, 0)
            val app = info.applicationInfo ?: return@runCatching null
            if (!app.enabled) return@runCatching null
            EditorApp(name, "${app.loadLabel(context.packageManager)} · ${info.versionName.orEmpty()}")
        }.getOrNull()
    }

    fun preferredEditor(context: Context): String? = preferences(context).getString("package", null)

    fun rememberEditor(context: Context, name: String) {
        require(name in packages)
        preferences(context).edit().putString("package", name).apply()
    }

    fun intent(context: Context, name: String): Intent = editIntent(name, uri(context))

    internal fun editIntent(name: String, uri: Uri): Intent {
        require(name in packages)
        return Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "text/plain")
            // Both official packages expose this stable alias for their text editor.
            setClassName(name, "$name.OpenTextActivity")
            clipData = ClipData.newRawUri("Hosts", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun begin(context: Context) = synchronized(lock) {
        if (writing(context).exists() && activeWriter == null) throw DraftRecoveryRequired()
        if (activeWriter != null) throw IOException(context.getString(R.string.manual_editor_save_pending))
        val existing = readSession(context)
        if (existing != null) {
            if (!document(context).isFile) throw DraftRecoveryRequired()
            val text = readDocument(context, document(context))
            val parsed = ManualHostsRuleManager.parseEditorText(text)
            val unchanged = !existing.awaitingSave && (hash(text) == existing.original ||
                (parsed.isValid && ruleHash(parsed.rules) == existing.baseline))
            if (!unchanged || ruleHash(ManualHostsRuleManager.getRules(context)) == existing.baseline) {
                writeSession(context, existing.copy(reviewed = null))
                return@synchronized
            }
            // An untouched session must not hide changes made by import or restore.
            state(context).delete()
        }
        val rules = ManualHostsRuleManager.getRules(context)
        val oldText = document(context).takeIf { it.isFile && it.length() <= MAX_BYTES }
            ?.let { readDocument(context, it) }
        val oldRules = oldText?.let(ManualHostsRuleManager::parseEditorText)
        val reusableText = oldText?.takeIf { oldRules?.isValid == true && oldRules.rules == rules }
        val body = if (reusableText != null) {
            // Refresh generated help when the app language changes, retaining user comments.
            if (reusableText.startsWith("# ADhosts\n") && "# ---\n" in reusableText) {
                val prefixRules = reusableText.substringBefore("# ---\n").lineSequence()
                    .filter { it.substringBefore('#').isNotBlank() }.joinToString("\n")
                val body = reusableText.substringAfter("# ---\n").trimStart('\n')
                listOf(prefixRules, body).filter(String::isNotEmpty).joinToString("\n")
            } else reusableText
        } else rules.joinToString("\n", transform = ManualHostsRule::asHostsLine)
        val refreshed = template(context) + body + if (body.endsWith('\n') || body.isEmpty()) "" else "\n"
        // Longer localized help must not make an already valid editing copy impossible to reopen.
        val text = if (reusableText != null && refreshed.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            reusableText
        } else refreshed
        writeAtomic(document(context), text)
        writeSession(context, Session(ruleHash(rules), hash(text)))
    }

    internal fun template(context: Context): String = buildString {
        appendLine("# ADhosts")
        context.getString(R.string.manual_editor_template).lineSequence().forEach { appendLine("# $it") }
        appendLine("# ads.example.com")
        appendLine("# 0.0.0.0 tracker.example.com")
        appendLine("# ::1 metrics.example.com")
        appendLine("# ---")
        appendLine()
    }

    fun pending(context: Context, includeReviewed: Boolean = false): PendingEdit? = synchronized(lock) {
        if (writing(context).exists()) {
            if (activeWriter == null) throw DraftRecoveryRequired()
            return@synchronized null
        }
        val session = readSession(context) ?: return@synchronized null
        if (session.awaitingSave) return@synchronized null
        val file = document(context)
        if (!file.isFile || file.length() > MAX_BYTES) {
            throw DraftRecoveryRequired()
        }
        val text = readDocument(context, file)
        val hash = hash(text)
        if (hash == session.original || (!includeReviewed && hash == session.reviewed)) return@synchronized null
        val parsed = ManualHostsRuleManager.parseEditorText(text)
        // Editing only comments or whitespace never triggers a merge or Root write.
        if (parsed.isValid && ruleHash(parsed.rules) == session.baseline) return@synchronized null
        val current = ruleHash(ManualHostsRuleManager.getRules(context))
        PendingEdit(hash, parsed, current != session.baseline && current != ruleHash(parsed.rules))
    }

    fun markReviewed(context: Context, hash: String) = synchronized(lock) {
        readSession(context)?.let { writeSession(context, it.copy(reviewed = hash)) }
    }

    /** A concurrent save remains pending instead of being deleted with the imported generation. */
    fun complete(context: Context, importedHash: String): Boolean = synchronized(lock) {
        if (writing(context).exists()) return@synchronized false
        val file = document(context)
        if (file.isFile && hash(readDocument(context, file)) != importedHash) {
            writeSession(context, Session(
                ruleHash(ManualHostsRuleManager.getRules(context)), importedHash
            ))
            false
        } else {
            state(context).delete()
            true
        }
    }

    fun discard(context: Context) = synchronized(lock) {
        // A late close callback can no longer publish this discarded generation.
        activeWriter = null
        writing(context).delete()
        state(context).delete()
        AtomicFile(document(context)).delete()
    }

    /** Writes go to an isolated inode. Only a successful close publishes an immutable copy. */
    fun openForWrite(context: Context, mode: String): ParcelFileDescriptor = synchronized(lock) {
        if (writing(context).exists()) throw IOException(context.getString(R.string.manual_editor_save_pending))
        if (readSession(context) == null) throw IOException(context.getString(R.string.manual_editor_changed))
        val stage = File.createTempFile("save_", ".txt", document(context).parentFile)
        if ('t' !in mode && mode != "w") document(context).copyTo(stage, overwrite = true)
        writeAtomic(writing(context), stage.name)
        activeWriter = stage
        try {
            ParcelFileDescriptor.open(stage, ParcelFileDescriptor.parseMode(mode),
                saveHandler) { error ->
                synchronized(lock) save@{
                    if (activeWriter != stage) { stage.delete(); return@save }
                    activeWriter = null
                    try {
                        if (error != null) throw error
                        val text = readDocument(context, stage)
                        writeAtomic(document(context), text)
                        readSession(context)?.takeIf { it.awaitingSave }?.let {
                            writeSession(context, it.copy(awaitingSave = false,
                                original = hash("reviewed:${it.original}")))
                        }
                        writing(context).delete()
                        stage.delete()
                    } catch (failure: Exception) {
                        // Keep the staging file and marker for explicit recovery; never auto-import it.
                        Log.w("ManualHostsEditor", "MT save needs recovery", failure)
                    }
                    saved.tryEmit(Unit)
                }
            }
        } catch (error: Exception) {
            activeWriter = null
            writing(context).delete()
            stage.delete()
            throw error
        }
    }

    /** Reopens an interrupted save for user review; a fresh save is required before import. */
    fun recoverDraft(context: Context) = synchronized(lock) {
        if (activeWriter != null) throw IOException(context.getString(R.string.manual_editor_save_pending))
        val stageName = writing(context).takeIf(File::isFile)?.readText()?.trim()
        val stage = stageName?.takeIf { it.matches(Regex("save_[a-zA-Z0-9_-]+\\.txt")) }
            ?.let { File(document(context).parentFile, it) }?.takeIf(File::isFile)
        val rules = ManualHostsRuleManager.getRules(context)
        val text = stage?.let { readDocument(context, it) }
            ?: document(context).takeIf { it.isFile && it.length() <= MAX_BYTES }?.let { readDocument(context, it) }
            ?: template(context) + rules.joinToString("\n", transform = ManualHostsRule::asHostsLine)
        writeAtomic(document(context), text)
        writeSession(context, Session(ruleHash(rules), hash(text), awaitingSave = true))
        writing(context).delete()
        stage?.delete()
    }

    private fun readDocument(context: Context, file: File): String {
        return try {
            ManualHostsRuleManager.readTextWithLimit(file)
        } catch (error: IOException) {
            throw IOException(context.getString(R.string.manual_editor_open_failed), error)
        }
    }

    internal fun document(context: Context): File = File(context.filesDir, "$DIRECTORY/document/hosts.txt")
    fun uri(context: Context): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.manualrules", document(context)
    )
    private fun preferences(context: Context) = context.getSharedPreferences(DIRECTORY, Context.MODE_PRIVATE)
    private fun state(context: Context) = AtomicFile(File(context.filesDir, "$DIRECTORY/session.json"))
    private fun writing(context: Context) = File(context.filesDir, "$DIRECTORY/writing.txt")
    private fun readSession(context: Context): Session? {
        val file = state(context)
        if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return null
        return runCatching {
            file.openRead().bufferedReader(Charsets.UTF_8).use { gson.fromJson(it, Session::class.java) }
                .also { require(it.baseline.isNotBlank() && it.original.isNotBlank()) }
        }.getOrElse { throw DraftRecoveryRequired() }
    }
    private fun writeSession(context: Context, session: Session) = writeAtomic(state(context).baseFile, gson.toJson(session))
    private fun writeAtomic(target: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Manual rules are too large")
        val parent = target.parentFile ?: throw IOException("Editor directory unavailable")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create editor directory")
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
    private fun ruleHash(rules: List<ManualHostsRule>) = hash(rules.joinToString("\n", transform = ManualHostsRule::asHostsLine))
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
