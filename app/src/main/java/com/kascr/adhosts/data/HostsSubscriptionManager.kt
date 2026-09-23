package com.kascr.adhosts.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class Subscription(
    val name: String,
    val url: String,
    val enabled: Boolean = true
)

object HostsSubscriptionManager {

    private const val FILE_NAME = "hosts_subscriptions.json"
    private const val MERGED_HOSTS_NAME = "ADhosts"
    private const val SOURCE_DIRECTORY = "hosts_sources"
    private const val ENABLED_DIRECTORY = "enabled"
    private const val DISABLED_DIRECTORY = "disabled"
    private const val LEGACY_SOURCE_CACHE_DIRECTORY = "hosts_source_cache"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REMOTE_FILE_BYTES = 10 * 1024 * 1024
    private const val MERGE_BUCKET_COUNT = 64

    private val gson = Gson()
    private val subscriptionsLock = Any()

    fun getSubscriptions(context: Context): List<Subscription> {
        return synchronized(subscriptionsLock) {
            readSubscriptions(context).also { subscriptions ->
                subscriptions.forEach { subscription ->
                    runCatching { reconcileSourceFile(context, subscription) }
                        .onFailure { Log.w(TAG, "Could not reconcile subscription file", it) }
                }
            }
        }
    }

    private fun readSubscriptions(context: Context): List<Subscription> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                parseSubscriptionsJson(reader.readText())
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: JsonSyntaxException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    fun saveSubscriptions(context: Context, subscriptions: List<Subscription>) {
        synchronized(subscriptionsLock) {
            val previous = readSubscriptions(context)
            val normalized = normalizeSubscriptions(subscriptions)
            writeSubscriptions(context, normalized)
            try {
                normalized.forEach { reconcileSourceFile(context, it) }
            } catch (error: Exception) {
                runCatching {
                    writeSubscriptions(context, previous)
                    previous.forEach { reconcileSourceFile(context, it) }
                }
                throw error
            }
        }
    }

    private fun writeSubscriptions(context: Context, subscriptions: List<Subscription>) {
        val file = File(context.filesDir, FILE_NAME)
        val normalized = normalizeSubscriptions(subscriptions)

        writeAtomically(file) { writer -> gson.toJson(normalized, writer) }
    }

    fun addSubscription(context: Context, name: String, url: String): Boolean {
        val normalizedName = name.trim()
        val normalizedUrl = url.trim()
        if (normalizedName.isEmpty() || normalizedUrl.isEmpty()) return false

        return synchronized(subscriptionsLock) {
            val subscriptions = readSubscriptions(context).toMutableList()
            if (subscriptions.any { urlsMatch(it.url, normalizedUrl) }) return@synchronized false

            subscriptions.add(Subscription(normalizedName, normalizedUrl))
            writeSubscriptions(context, subscriptions)
            try {
                reconcileSourceFile(context, subscriptions.last())
                clearMergedHostsCache(context)
            } catch (error: Exception) {
                runCatching { writeSubscriptions(context, subscriptions.dropLast(1)) }
                throw error
            }
            true
        }
    }

    fun hasSubscription(context: Context, url: String): Boolean {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isEmpty()) return false

        return synchronized(subscriptionsLock) {
            readSubscriptions(context).any { urlsMatch(it.url, normalizedUrl) }
        }
    }

    fun removeSubscription(context: Context, url: String) {
        val normalizedUrl = url.trim()
        synchronized(subscriptionsLock) {
            val subscriptions = readSubscriptions(context)
                .filterNot { urlsMatch(it.url, normalizedUrl) }
            writeSubscriptions(context, subscriptions)
        }
    }

    fun setSubscriptionEnabled(context: Context, url: String, enabled: Boolean): Boolean {
        return synchronized(subscriptionsLock) {
            val subscriptions = readSubscriptions(context)
            val index = subscriptions.indexOfFirst { urlsMatch(it.url, url.trim()) }
            if (index < 0) return@synchronized false
            if (subscriptions[index].enabled == enabled) {
                reconcileSourceFile(context, subscriptions[index])
            } else {
                val updated = subscriptions[index].copy(enabled = enabled)
                writeSubscriptions(context, subscriptions.toMutableList().apply { this[index] = updated })
                try {
                    reconcileSourceFile(context, updated)
                    clearMergedHostsCache(context)
                } catch (error: Exception) {
                    runCatching {
                        writeSubscriptions(context, subscriptions)
                        reconcileSourceFile(context, subscriptions[index])
                    }
                    throw error
                }
            }
            true
        }
    }

    fun clearMergedHostsCache(context: Context) {
        val mergedFile = File(context.filesDir, MERGED_HOSTS_NAME)
        if (mergedFile.exists() && !mergedFile.delete()) {
            throw IOException("Cannot clear merged hosts file")
        }
    }

    fun createMergedHostsSnapshot(context: Context): File? {
        val source = File(context.filesDir, MERGED_HOSTS_NAME)
        if (!source.isFile) return null
        val cacheDirectory = context.cacheDir
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("Cannot create snapshot directory")
        }
        val snapshot = File.createTempFile("$MERGED_HOSTS_NAME.", ".snapshot", cacheDirectory)
        try {
            source.inputStream().use { input ->
                FileOutputStream(snapshot).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            return snapshot
        } catch (error: Exception) {
            snapshot.delete()
            throw error
        }
    }

    fun restoreMergedHostsCache(context: Context, snapshot: File?) {
        if (snapshot == null) {
            clearMergedHostsCache(context)
        } else {
            require(snapshot.isFile) { "Merged hosts snapshot is missing" }
            moveAtomically(snapshot, File(context.filesDir, MERGED_HOSTS_NAME))
        }
    }

    fun hasCachedSource(context: Context, url: String): Boolean = synchronized(subscriptionsLock) {
        val subscription = readSubscriptions(context).firstOrNull { urlsMatch(it.url, url.trim()) }
            ?: return@synchronized false
        reconcileSourceFile(context, subscription)
        sourceFile(context, subscription.url, subscription.enabled).isFile
    }

    /** Supports both the new object format and legacy URL-only JSON arrays. */
    fun parseSubscriptionsJson(json: String): List<Subscription> {
        val root = JsonParser.parseString(json)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull(::subscriptionFromJson).let(::normalizeSubscriptions)
    }

    @Throws(Exception::class)
    fun downloadAndMergeSubscriptions(context: Context, refreshRemote: Boolean = true): Int {
        val subscriptions = getSubscriptions(context).filter { it.enabled }
        val manualRules = ManualHostsRuleManager.getRules(context)
        if (subscriptions.isEmpty() && manualRules.isEmpty()) {
            clearMergedHostsCache(context)
            return 0
        }

        val sourceFiles = mutableListOf<File>()
        val unavailableSources = mutableListOf<String>()
        for (subscription in subscriptions) {
            try {
                reconcileSourceFile(context, subscription)
                val cacheFile = sourceFile(context, subscription.url, enabled = true)
                if (refreshRemote || !cacheFile.isFile) {
                    try {
                        downloadSourceToFile(subscription.url, cacheFile)
                    } catch (error: Exception) {
                        if (!cacheFile.isFile) throw error
                    }
                }
                sourceFiles += cacheFile
            } catch (e: Exception) {
                unavailableSources += "${subscription.name} (${e.message ?: "unknown error"})"
            }
        }

        if (unavailableSources.isNotEmpty()) {
            throw IOException("Unavailable subscriptions: ${unavailableSources.joinToString()}")
        }
        return mergeSourceFiles(context, subscriptions, sourceFiles, manualRules)
    }

    /** Builds a preview without changing the active source cache or merged hosts file. */
    internal fun buildPreview(context: Context, directory: File): PreviewBuild {
        val subscriptions = getSubscriptions(context).filter { it.enabled }
        val manualRules = ManualHostsRuleManager.getRules(context)
        require(subscriptions.isNotEmpty() || manualRules.isNotEmpty()) { "No enabled rules" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create preview directory")
        val sources = mutableListOf<File>()
        val fallback = mutableListOf<String>()
        subscriptions.forEach { subscription ->
            val cached = sourceFile(context, subscription.url, true)
            val staged = File(directory, sourceFileName(subscription.url))
            try {
                downloadSourceToFile(subscription.url, staged)
            } catch (error: Exception) {
                if (!cached.isFile) throw IOException("${subscription.name}: ${error.message}", error)
                Files.copy(cached.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
                fallback += subscription.name
            }
            sources += staged
        }
        val merged = File(directory, MERGED_HOSTS_NAME)
        val count = mergeSourceFiles(context, subscriptions, sources, manualRules, merged)
        return PreviewBuild(merged, count, fallback)
    }

    internal data class PreviewBuild(val merged: File, val ruleCount: Int, val cachedSources: List<String>)

    internal fun commitPreview(context: Context, directory: File) {
        val replacements = getSubscriptions(context).filter { it.enabled }.map { subscription ->
            File(directory, sourceFileName(subscription.url)) to sourceFile(context, subscription.url, true)
        } + (File(directory, MERGED_HOSTS_NAME) to File(context.filesDir, MERGED_HOSTS_NAME))
        replacements.forEach { (staged, _) ->
            if (!staged.isFile) throw IOException("Preview file missing: ${staged.name}")
        }
        val backupDirectory = File(directory, "rollback")
        if (!backupDirectory.mkdirs()) throw IOException("Cannot create update rollback directory")
        try {
            val backups = replacements.mapIndexed { index, (_, target) ->
                File(backupDirectory, "$index.old").also { backup ->
                    if (target.isFile) Files.copy(target.toPath(), backup.toPath())
                }
            }
            try {
                replacements.forEach { (staged, target) ->
                    val parent = target.parentFile ?: throw IOException("Preview target has no parent")
                    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create source directory")
                    val temporary = File.createTempFile("${target.name}.", ".new", parent)
                    try {
                        Files.copy(staged.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        moveAtomically(temporary, target)
                    } finally { temporary.delete() }
                }
            } catch (error: Exception) {
                replacements.forEachIndexed { index, (_, target) ->
                    runCatching {
                        val backup = backups[index]
                        if (backup.isFile) Files.copy(backup.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING)
                        else Files.deleteIfExists(target.toPath())
                    }.onFailure(error::addSuppressed)
                }
                throw error
            }
        } finally { backupDirectory.deleteRecursively() }
    }

    internal fun cachedSourceForQuery(context: Context, url: String): File = sourceFile(context, url, true)

    internal fun countEffectiveRules(lines: Sequence<String>): Int =
        lines.count(::isEffectiveHostsRule)

    private fun isEffectiveHostsRule(line: String): Boolean {
        val fields = hostsFields(line)
        if (fields.size < 2 || !isNumericIpAddress(fields.first())) return false
        return fields.drop(1).any { hostname -> hostname.lowercase() !in LOCAL_HOST_NAMES }
    }

    private fun isHostsFileEntry(line: String): Boolean {
        val fields = hostsFields(line)
        return fields.size >= 2 && isNumericIpAddress(fields.first())
    }

    private fun hostsFields(line: String): List<String> =
        line.substringBefore('#').trim().split(WHITESPACE_REGEX).filter(String::isNotEmpty)

    internal fun isNumericIpAddress(value: String): Boolean {
        if (value.contains(':')) {
            return value.count { it == ':' } >= 2 && IPV6_ADDRESS_REGEX.matches(value)
        }
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 &&
                octet.all(Char::isDigit) && octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun downloadSourceToFile(urlString: String, target: File) {
        val parent = target.parentFile ?: throw IOException("Subscription path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Cannot create subscription directory")
        }
        val temporary = File.createTempFile("${target.name}.", ".download", parent)
        var connection: HttpURLConnection? = null

        try {
            val activeConnection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ADhosts")
            }
            connection = activeConnection
            val responseCode = activeConnection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            activeConnection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        if (totalBytes > MAX_REMOTE_FILE_BYTES) {
                            throw IOException(
                                "Subscription exceeds ${MAX_REMOTE_FILE_BYTES / (1024 * 1024)} MB"
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            validateSourceFile(temporary)
            moveAtomically(temporary, target)
        } finally {
            connection?.disconnect()
            temporary.takeIf(File::exists)?.delete()
        }
    }

    private fun validateSourceFile(file: File) {
        var hasMeaningfulLine = false
        var hasHostsEntry = false
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
                hasMeaningfulLine = true
                if (isHostsFileEntry(trimmed)) hasHostsEntry = true
            }
        }
        if (hasMeaningfulLine && !hasHostsEntry) {
            throw IOException("Subscription contains no valid hosts rules")
        }
    }

    private fun mergeSourceFiles(
        context: Context,
        subscriptions: List<Subscription>,
        sourceFiles: List<File>,
        manualRules: List<ManualHostsRule>,
        target: File = File(context.filesDir, MERGED_HOSTS_NAME)
    ): Int {
        val cacheDirectory = context.cacheDir
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("Cannot create merge cache directory")
        }
        val workspace = Files.createTempDirectory(cacheDirectory.toPath(), "hosts_merge_").toFile()
        try {
            val bucketFiles = List(MERGE_BUCKET_COUNT) { index ->
                File(workspace, "bucket_${index.toString().padStart(2, '0')}.tmp")
            }
            val manualHostnames = manualRules.mapTo(HashSet(manualRules.size)) { it.hostname }
            val bucketWriters = mutableListOf<BufferedWriter>()
            try {
                bucketFiles.forEach { bucketWriters += it.bufferedWriter(Charsets.UTF_8) }
                sourceFiles.forEach { source ->
                    bucketSourceFile(source, bucketWriters, manualHostnames)
                }
            } finally {
                bucketWriters.forEach { writer -> runCatching { writer.close() } }
            }
            return writeMergedHosts(subscriptions, bucketFiles, manualRules, target)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun bucketSourceFile(
        source: File,
        bucketWriters: List<BufferedWriter>,
        manualHostnames: Set<String>
    ) {
        var hasMeaningfulLine = false
        var hasHostsEntry = false
        source.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                hasMeaningfulLine = true
                val fields = hostsFields(trimmed)
                if (fields.size < 2 || !isNumericIpAddress(fields.first())) continue
                hasHostsEntry = true
                val address = fields.first()
                for (rawHostname in fields.drop(1)) {
                    val normalized = rawHostname.lowercase().trimEnd('.')
                    val hostname = if (normalized.any { it.code > 127 }) {
                        runCatching { IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES) }
                            .getOrNull() ?: continue
                    } else normalized
                    if (
                        hostname.isEmpty() ||
                        hostname in LOCAL_HOST_NAMES ||
                        hostname in manualHostnames
                    ) continue
                    val bucket = (hostname.hashCode() and Int.MAX_VALUE) % MERGE_BUCKET_COUNT
                    bucketWriters[bucket]
                        .append(address)
                        .append(' ')
                        .append(hostname)
                        .appendLine()
                }
            }
        }
        if (hasMeaningfulLine && !hasHostsEntry) {
            throw IOException("Subscription contains no valid hosts rules")
        }
    }

    private fun writeMergedHosts(
        subscriptions: List<Subscription>,
        bucketFiles: List<File>,
        manualRules: List<ManualHostsRule>,
        target: File
    ): Int {
        val temporary = File.createTempFile("${target.name}.", ".merge", target.parentFile)
        var ruleCount = 0
        try {
            FileOutputStream(temporary).use { output ->
                val writer = output.bufferedWriter(Charsets.UTF_8)
                writer.appendLine("# Merged ADhosts - ${System.currentTimeMillis()}")
                subscriptions.forEach { writer.appendLine("# Source: ${it.url}") }
                writer.appendLine("127.0.0.1 localhost")
                writer.appendLine("::1 localhost")
                writer.appendLine()

                bucketFiles.forEach { bucketFile ->
                    val seenInBucket = HashSet<String>()
                    bucketFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            val separator = line.indexOf(' ')
                            if (separator <= 0 || separator == line.lastIndex) continue
                            val hostname = line.substring(separator + 1)
                            if (seenInBucket.add(hostname)) {
                                writer.appendLine(line)
                                ruleCount++
                            }
                        }
                    }
                }

                if (manualRules.isNotEmpty()) {
                    writer.appendLine()
                    writer.appendLine("# Local rules")
                    manualRules.forEach { rule ->
                        writer.appendLine(rule.asHostsLine())
                        ruleCount++
                    }
                }
                writer.flush()
                output.fd.sync()
            }
            moveAtomically(temporary, target)
            return ruleCount
        } finally {
            temporary.takeIf(File::exists)?.delete()
        }
    }

    private fun sourceFile(context: Context, url: String, enabled: Boolean): File {
        val directory = File(
            File(context.filesDir, SOURCE_DIRECTORY),
            if (enabled) ENABLED_DIRECTORY else DISABLED_DIRECTORY
        )
        return File(directory, sourceFileName(url))
    }

    private fun legacySourceFile(context: Context, url: String): File =
        File(File(context.filesDir, LEGACY_SOURCE_CACHE_DIRECTORY), sourceFileName(url))

    private fun sourceFileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$digest.hosts"
    }

    private fun reconcileSourceFile(context: Context, subscription: Subscription) {
        val target = sourceFile(context, subscription.url, subscription.enabled)
        val opposite = sourceFile(context, subscription.url, !subscription.enabled)
        val legacy = legacySourceFile(context, subscription.url)
        if (target.isFile) {
            opposite.delete()
            legacy.delete()
            return
        }
        val source = when {
            opposite.isFile -> opposite
            legacy.isFile -> legacy
            else -> return
        }
        moveAtomically(source, target)
        legacy.takeIf { it != source }?.delete()
    }

    private fun moveAtomically(source: File, target: File) {
        val parent = target.parentFile ?: throw IOException("Subscription path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Cannot create subscription directory")
        }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun subscriptionFromJson(element: JsonElement): Subscription? {
        val url = when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
            element.isJsonObject -> element.asJsonObject.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        }?.trim().orEmpty()
        if (url.isEmpty()) return null

        val name = element.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            .orEmpty()
            .ifEmpty { defaultNameForUrl(url) }
        val enabled = element.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("enabled")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: true
        return Subscription(name, url, enabled)
    }

    private fun normalizeSubscriptions(subscriptions: List<Subscription>): List<Subscription> {
        val seenUrls = mutableListOf<String>()
        return subscriptions.mapNotNull { subscription ->
            val url = subscription.url.trim()
            if (url.isEmpty() || seenUrls.any { urlsMatch(it, url) }) null else {
                seenUrls += url
                Subscription(
                    subscription.name.trim().ifEmpty { defaultNameForUrl(url) },
                    url,
                    subscription.enabled
                )
            }
        }
    }

    private fun defaultNameForUrl(url: String): String {
        return url.toSafeUri()?.host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: url.substringAfter("//", url).substringBefore('/').ifBlank { url }
    }

    private fun urlsMatch(first: String, second: String): Boolean {
        val firstUri = first.toSafeUri() ?: return first == second
        val secondUri = second.toSafeUri() ?: return first == second

        val normalizedFirst = normalizeUri(firstUri)
        val normalizedSecond = normalizeUri(secondUri)
        return normalizedFirst == normalizedSecond
    }

    private fun String.toSafeUri(): URI? {
        return try {
            URI(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUri(uri: URI): String {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val userInfo = uri.userInfo?.let { "$it@" }.orEmpty()
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val authority = if (host.isEmpty() && userInfo.isEmpty() && port.isEmpty()) {
            uri.rawAuthority?.lowercase().orEmpty()
        } else {
            "$userInfo$host$port"
        }
        return buildString {
            append(scheme)
            append(':')
            if (authority.isNotEmpty()) {
                append("//")
                append(authority)
            }
            append(path)
            append(query)
            append(fragment)
        }
    }

    private fun writeAtomically(file: File, write: (java.io.Writer) -> Unit) {
        val parent = file.parentFile ?: throw IOException("File path has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Cannot create file directory")
        }
        val tempFile = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            tempFile.bufferedWriter(Charsets.UTF_8).use(write)
            moveAtomically(tempFile, file)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val IPV6_ADDRESS_REGEX = "[0-9a-fA-F:.]+".toRegex()
    private val LOCAL_HOST_NAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback"
    )
    private const val TAG = "HostsSubscriptions"
}
