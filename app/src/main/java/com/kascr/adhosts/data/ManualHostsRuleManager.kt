package com.kascr.adhosts.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ManualHostsRule(
    val address: String,
    val hostname: String
) {
    fun asHostsLine(): String = "$address $hostname"
}

data class ManualRulesParseResult(
    val rules: List<ManualHostsRule>,
    val invalidLines: List<Int>
) {
    val isValid: Boolean get() = invalidLines.isEmpty()
}

object ManualHostsRuleManager {

    private const val FILE_NAME = "manual_hosts_rules.txt"
    private const val DEFAULT_BLOCK_ADDRESS = "0.0.0.0"
    internal const val MAX_RULES = 10_000
    // 10,000 maximum-length rules need about 3 MiB. Keep room for editing help and comments.
    internal const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    private val lock = Any()

    fun getRules(context: Context): List<ManualHostsRule> = synchronized(lock) {
        HostsOperationJournal.ensureRecovered(context)
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return@synchronized emptyList()
        runCatching { parseEditorText(readTextWithLimit(file)).rules }
            .getOrDefault(emptyList())
    }

    fun getEditorText(context: Context): String =
        getRules(context).joinToString("\n", transform = ManualHostsRule::asHostsLine)

    fun saveRules(context: Context, rules: List<ManualHostsRule>) = synchronized(lock) {
        require(rules.size <= MAX_RULES) { "Too many manual rules" }
        val text = rules.joinToString("\n", transform = ManualHostsRule::asHostsLine)
            .let { if (it.isEmpty()) it else "$it\n" }
        val parsed = parseEditorText(text)
        require(parsed.isValid && parsed.rules == rules) { "Invalid manual rules" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Manual rules are too large" }
        val target = File(context.filesDir, FILE_NAME)
        val temporary = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporary.takeIf(File::exists)?.delete()
        }
    }

    internal fun readTextWithLimit(file: File): String {
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_TEXT_BYTES) throw IOException("Manual rules are too large")
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    internal fun parseEditorText(text: String): ManualRulesParseResult {
        val rules = mutableListOf<ManualHostsRule>()
        val invalidLines = linkedSetOf<Int>()
        val seenHosts = hashSetOf<String>()

        text.lineSequence().forEachIndexed { index, originalLine ->
            val lineNumber = index + 1
            val content = originalLine.removePrefix("\uFEFF").substringBefore('#').trim()
            if (content.isEmpty()) return@forEachIndexed

            val fields = content.split(WHITESPACE_REGEX).filter(String::isNotEmpty)
            val address: String
            val hostFields: List<String>
            if (fields.size == 1) {
                address = DEFAULT_BLOCK_ADDRESS
                hostFields = fields
            } else if (HostsRuleValidator.isNumericIpAddress(fields.first())) {
                address = fields.first()
                hostFields = fields.drop(1)
            } else {
                invalidLines += lineNumber
                return@forEachIndexed
            }

            val normalizedHosts = hostFields.mapNotNull(::normalizeHostname)
            if (normalizedHosts.size != hostFields.size || normalizedHosts.isEmpty()) {
                invalidLines += lineNumber
                return@forEachIndexed
            }
            if (normalizedHosts.toSet().size != normalizedHosts.size || normalizedHosts.any { it in seenHosts }) {
                invalidLines += lineNumber
                return@forEachIndexed
            }
            normalizedHosts.forEach { hostname ->
                seenHosts += hostname
                rules += ManualHostsRule(address, hostname)
            }
            if (rules.size > MAX_RULES) invalidLines += lineNumber
        }

        return ManualRulesParseResult(rules.take(MAX_RULES), invalidLines.toList())
    }

    private fun normalizeHostname(value: String): String? {
        val hostname = HostsRuleValidator.normalizeHostname(value.trim()) ?: return null
        return hostname.takeUnless(HostsRuleValidator::isLocalHostname)
    }

    private val WHITESPACE_REGEX = "\\s+".toRegex()
}
