package com.kascr.adhosts.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.net.IDN
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class UpdatePreview(
    val ruleCount: Int,
    val added: Int,
    val removed: Int,
    val changed: Int,
    val cachedSources: List<String>
) {
    val hasChanges: Boolean get() = added + removed + changed > 0
}

data class DomainMatch(
    val hostname: String,
    val address: String,
    val source: String,
    val applied: Boolean,
    val manual: Boolean = false
)

object HostsUpdateManager {
    private const val PENDING = "pending_hosts_update"
    private const val CURRENT = "last_applied_hosts"
    private const val PREVIOUS = "previous_applied_hosts"
    private const val HOSTS = "ADhosts"
    private const val META = "preview.json"
    private val gson = Gson()
    private val hostnamePattern = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    private data class SavedPreview(val fingerprint: String, val preview: UpdatePreview)

    fun normalizeDomain(input: String): String? {
        val domain = input.trim().trimEnd('.').lowercase()
        if (domain.isEmpty() || domain.any(Char::isWhitespace)) return null
        val ascii = runCatching { IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
            ?: return null
        if (ascii.all { it.isDigit() || it == '.' }) return null
        return ascii.takeIf(hostnamePattern::matches)
    }

    fun prepare(context: Context): UpdatePreview {
        val fingerprint = configurationFingerprint(context)
        val pending = File(context.filesDir, PENDING)
        pending.deleteRecursively()
        if (!pending.mkdirs()) throw IOException("Cannot create update preview")
        try {
            val build = HostsSubscriptionManager.buildPreview(context, pending)
            val baseline = File(File(context.filesDir, CURRENT), HOSTS)
                .takeIf(File::isFile) ?: File(context.filesDir, HOSTS)
            val diff = compare(baseline, build.merged)
            if (fingerprint != configurationFingerprint(context)) {
                throw IOException("Subscription configuration changed during preview")
            }
            val preview = UpdatePreview(build.ruleCount, diff.first, diff.second, diff.third, build.cachedSources)
            File(pending, META).writeText(gson.toJson(SavedPreview(fingerprint, preview)))
            return preview
        } catch (error: Exception) {
            pending.deleteRecursively()
            throw error
        }
    }

    fun pendingPreview(context: Context): UpdatePreview? {
        val pending = File(context.filesDir, PENDING)
        val saved = runCatching { gson.fromJson(File(pending, META).readText(), SavedPreview::class.java) }
            .getOrNull() ?: return null
        if (saved.fingerprint != configurationFingerprint(context) || !File(pending, HOSTS).isFile) {
            pending.deleteRecursively()
            return null
        }
        return saved.preview
    }

    fun pendingHosts(context: Context): File {
        pendingPreview(context) ?: throw IOException("Update preview expired")
        return File(File(context.filesDir, PENDING), HOSTS)
    }

    fun commit(context: Context) {
        val pending = File(context.filesDir, PENDING)
        pendingPreview(context) ?: throw IOException("Update preview expired")
        HostsSubscriptionManager.commitPreview(context, pending)
        pending.deleteRecursively()
    }

    fun discard(context: Context) { File(context.filesDir, PENDING).deleteRecursively() }

    fun hasPrevious(context: Context): Boolean = File(File(context.filesDir, PREVIOUS), HOSTS).isFile

    fun previousHosts(context: Context): File = File(File(context.filesDir, PREVIOUS), HOSTS)
        .also { if (!it.isFile) throw IOException("Previous rules unavailable") }

    /** Called only after a successful root write. Keeps one prior applied generation. */
    fun recordApplied(context: Context, applied: File) {
        val current = File(context.filesDir, CURRENT)
        val previous = File(context.filesDir, PREVIOUS)
        val subscriptionsJson = gson.toJson(HostsSubscriptionManager.getSubscriptions(context))
        val manualText = ManualHostsRuleManager.getEditorText(context)
        val currentHosts = File(current, HOSTS)
        if (currentHosts.isFile && runCatching {
                File(current, "subscriptions.json").readText(Charsets.UTF_8) == subscriptionsJson &&
                    File(current, "manual.txt").readText(Charsets.UTF_8) == manualText &&
                    sameRules(currentHosts, applied)
            }.getOrDefault(false)) return
        val staging = File(context.filesDir, "$CURRENT.new")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot save applied rules")
        try {
            Files.copy(applied.toPath(), File(staging, HOSTS).toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(staging, "subscriptions.json").writeText(subscriptionsJson, Charsets.UTF_8)
            File(staging, "manual.txt").writeText(manualText, Charsets.UTF_8)
            val sourceSnapshot = File(staging, "sources")
            sourceSnapshot.mkdirs()
            HostsSubscriptionManager.getSubscriptions(context).filter { it.enabled }.forEach { subscription ->
                val source = HostsSubscriptionManager.cachedSourceForQuery(context, subscription.url)
                if (source.isFile) Files.copy(source.toPath(), File(sourceSnapshot, source.name).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
            }
            previous.deleteRecursively()
            val movedCurrent = current.isDirectory
            if (movedCurrent && !current.renameTo(previous)) throw IOException("Cannot save previous rules")
            if (!staging.renameTo(current)) {
                if (movedCurrent) previous.renameTo(current)
                throw IOException("Cannot save applied rules")
            }
        } finally { staging.deleteRecursively() }
    }

    fun restorePreviousConfiguration(context: Context) {
        val previous = File(context.filesDir, PREVIOUS)
        val subscriptions = HostsSubscriptionManager.parseSubscriptionsJson(
            File(previous, "subscriptions.json").readText(Charsets.UTF_8)
        )
        val manual = ManualHostsRuleManager.parseEditorText(File(previous, "manual.txt").readText(Charsets.UTF_8))
        if (!manual.isValid) throw IOException("Previous manual rules are invalid")
        HostsSubscriptionManager.saveSubscriptions(context, subscriptions)
        ManualHostsRuleManager.saveRules(context, manual.rules)
        subscriptions.filter { it.enabled }.forEach { subscription ->
            val targetSource = HostsSubscriptionManager.cachedSourceForQuery(context, subscription.url)
            val savedSource = File(File(previous, "sources"), targetSource.name)
            if (savedSource.isFile) {
                targetSource.parentFile?.mkdirs()
                Files.copy(savedSource.toPath(), targetSource.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val target = File(context.filesDir, HOSTS)
        Files.copy(File(previous, HOSTS).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        discard(context)
    }

    fun findDomain(context: Context, raw: String, currentlyApplied: Boolean): DomainMatch? {
        val hostname = normalizeDomain(raw) ?: return null
        val manual = ManualHostsRuleManager.getRules(context).firstOrNull { it.hostname == hostname }
        if (manual != null) return DomainMatch(hostname, manual.address, "Manual", currentlyApplied &&
            containsHost(appliedHosts(context), hostname, manual.address), manual = true)
        for (subscription in HostsSubscriptionManager.getSubscriptions(context).filter { it.enabled }) {
            val file = HostsSubscriptionManager.cachedSourceForQuery(context, subscription.url)
            val address = findAddress(file, hostname) ?: continue
            return DomainMatch(hostname, address, subscription.name, currentlyApplied &&
                containsHost(appliedHosts(context), hostname, address))
        }
        return null
    }

    private fun containsHost(file: File, hostname: String, address: String): Boolean =
        findAddress(file, hostname) == address

    private fun appliedHosts(context: Context): File = File(File(context.filesDir, CURRENT), HOSTS)
        .takeIf(File::isFile) ?: File(context.filesDir, HOSTS)

    private fun findAddress(file: File, hostname: String): String? {
        if (!file.isFile) return null
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
                if (fields.size < 2 || !HostsSubscriptionManager.isNumericIpAddress(fields[0])) continue
                if (fields.drop(1).any { raw ->
                        val normalized = raw.lowercase().trimEnd('.')
                        normalized == hostname || (normalized.any { it.code > 127 } &&
                            normalizeDomain(normalized) == hostname)
                    }) return fields[0]
            }
        }
        return null
    }

    private fun configurationFingerprint(context: Context): String {
        val config = gson.toJson(HostsSubscriptionManager.getSubscriptions(context)) + "\n" +
            ManualHostsRuleManager.getEditorText(context)
        return MessageDigest.getInstance("SHA-256").digest(config.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    internal fun compare(old: File, new: File): Triple<Int, Int, Int> {
        val parent = new.parentFile ?: throw IOException("Preview path has no parent")
        val directory = Files.createTempDirectory(parent.toPath(), "diff_").toFile()
        val oldBuckets = List(64) { File(directory, "old_$it") }
        val newBuckets = List(64) { File(directory, "new_$it") }
        try {
            split(old, oldBuckets)
            split(new, newBuckets)
            var added = 0; var removed = 0; var changed = 0
            for (index in oldBuckets.indices) {
                val previous = readBucket(oldBuckets[index])
                val latest = readBucket(newBuckets[index])
                for ((host, address) in latest) {
                    val before = previous[host]
                    if (before == null) added++ else if (before != address) changed++
                }
                removed += previous.keys.count { it !in latest }
            }
            return Triple(added, removed, changed)
        } finally { directory.deleteRecursively() }
    }

    private fun split(source: File, buckets: List<File>) {
        if (!source.isFile) return
        val writers = buckets.map { it.bufferedWriter(Charsets.UTF_8) }
        try {
            source.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
                    if (fields.size < 2) continue
                    for (host in fields.drop(1)) {
                        val normalized = host.lowercase().trimEnd('.')
                        if (normalized == "localhost" || normalized.isEmpty()) continue
                        writers[(normalized.hashCode() and Int.MAX_VALUE) % writers.size]
                            .append(normalized).append(' ').append(fields[0]).appendLine()
                    }
                }
            }
        } finally { writers.forEach { it.close() } }
    }

    private fun readBucket(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val entries = HashMap<String, String>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val separator = line.indexOf(' ')
            if (separator > 0) entries.putIfAbsent(line.substring(0, separator), line.substring(separator + 1))
        }
        return entries
    }

    private fun sameRules(first: File, second: File): Boolean {
        first.bufferedReader(Charsets.UTF_8).use { oldReader ->
            second.bufferedReader(Charsets.UTF_8).use { newReader ->
                fun nextRule(reader: java.io.BufferedReader): String? {
                    while (true) {
                        val line = reader.readLine() ?: return null
                        if (line.isNotBlank() && !line.trimStart().startsWith('#')) return line
                    }
                }
                while (true) {
                    val oldLine = nextRule(oldReader)
                    val newLine = nextRule(newReader)
                    if (oldLine != newLine) return false
                    if (oldLine == null) return true
                }
            }
        }
    }
}
