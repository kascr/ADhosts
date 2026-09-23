package com.kascr.adhosts.data

import android.content.Context
import java.io.File
import java.net.IDN
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
    private const val MAX_RULES = 10_000
    private val lock = Any()

    fun getRules(context: Context): List<ManualHostsRule> = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return@synchronized emptyList()
        runCatching { parseEditorText(file.readText(Charsets.UTF_8)).rules }
            .getOrDefault(emptyList())
    }

    fun getEditorText(context: Context): String =
        getRules(context).joinToString("\n", transform = ManualHostsRule::asHostsLine)

    fun saveRules(context: Context, rules: List<ManualHostsRule>) = synchronized(lock) {
        val target = File(context.filesDir, FILE_NAME)
        val temporary = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                rules.forEach { rule ->
                    writer.appendLine(rule.asHostsLine())
                }
            }
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

    internal fun parseEditorText(text: String): ManualRulesParseResult {
        val rules = mutableListOf<ManualHostsRule>()
        val invalidLines = linkedSetOf<Int>()
        val seenHosts = hashSetOf<String>()

        text.lineSequence().forEachIndexed { index, originalLine ->
            val lineNumber = index + 1
            val content = originalLine.substringBefore('#').trim()
            if (content.isEmpty()) return@forEachIndexed

            val fields = content.split(WHITESPACE_REGEX).filter(String::isNotEmpty)
            val address: String
            val hostFields: List<String>
            if (fields.size == 1) {
                address = DEFAULT_BLOCK_ADDRESS
                hostFields = fields
            } else if (isNumericIpAddress(fields.first())) {
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
            if (normalizedHosts.any { it in seenHosts }) {
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
        val trimmed = value.trim().trimEnd('.').lowercase()
        if (trimmed.isEmpty() || '*' in trimmed || trimmed in LOCAL_HOST_NAMES ||
            isNumericIpAddress(trimmed)
        ) return null
        val ascii = runCatching { IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
            ?: return null
        if (ascii.length > 253 || !HOSTNAME_REGEX.matches(ascii)) return null
        return ascii
    }

    private fun isNumericIpAddress(value: String): Boolean {
        if (value.contains(':')) {
            return value.count { it == ':' } >= 2 && IPV6_ADDRESS_REGEX.matches(value)
        }
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 && octet.all(Char::isDigit) &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val IPV6_ADDRESS_REGEX = "[0-9a-fA-F:.]+".toRegex()
    private val HOSTNAME_REGEX = Regex(
        "(?=.{1,253}${'$'})(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*" +
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    )
    private val LOCAL_HOST_NAMES = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback"
    )
}
