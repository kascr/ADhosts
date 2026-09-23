package com.kascr.adhosts.data

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HostsUpdateManagerTest {
    @Test
    fun comparisonCountsDomainChangesWithoutHeadersOrDuplicateLines() {
        val dir = Files.createTempDirectory("hosts-preview-test").toFile()
        try {
            val old = dir.resolve("old").apply { writeText("""
                # old version
                127.0.0.1 localhost
                0.0.0.0 same.example
                0.0.0.0 changed.example
                0.0.0.0 removed.example
            """.trimIndent()) }
            val candidate = dir.resolve("candidate").apply { writeText("""
                # new version
                ::1 localhost
                0.0.0.0 same.example
                0.0.0.0 same.example
                127.0.0.1 changed.example
                0.0.0.0 added.example
            """.trimIndent()) }
            assertEquals(Triple(1, 1, 1), HostsUpdateManager.compare(old, candidate))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun lookupNormalizesInternationalDomainsAndRejectsInvalidInput() {
        assertEquals("xn--bcher-kva.example", HostsUpdateManager.normalizeDomain("BÜCHER.example."))
        assertNull(HostsUpdateManager.normalizeDomain("*.example.com"))
        assertNull(HostsUpdateManager.normalizeDomain("127.0.0.1"))
    }

    @Test
    fun previewKeepsActiveFilesUntilCommitAndExpiresWhenConfigurationChanges() {
        val context = RuntimeEnvironment.getApplication()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(start = true, isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { /* headers */ }
                    val body = "0.0.0.0 new.example\n".toByteArray()
                    connection.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                        output.flush()
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${server.localPort}/hosts"
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cached = File(context.filesDir, "hosts_sources/enabled/$hash.hosts")
        val merged = File(context.filesDir, "ADhosts")
        val config = File(context.filesDir, "hosts_subscriptions.json")
        val manual = File(context.filesDir, "manual_hosts_rules.txt")
        val pending = File(context.filesDir, "pending_hosts_update")
        val applied = File(context.filesDir, "last_applied_hosts")
        val previous = File(context.filesDir, "previous_applied_hosts")
        try {
            pending.deleteRecursively(); applied.deleteRecursively(); previous.deleteRecursively()
            manual.delete(); config.delete(); merged.delete()
            HostsSubscriptionManager.saveSubscriptions(context, listOf(Subscription("Test", url)))
            cached.parentFile?.mkdirs()
            cached.writeText("0.0.0.0 old.example\n")
            merged.writeText("# Merged ADhosts - old\n0.0.0.0 old.example\n")

            val preview = HostsUpdateManager.prepare(context)
            assertEquals(Triple(1, 1, 0), Triple(preview.added, preview.removed, preview.changed))
            assertTrue(cached.readText().contains("old.example"))
            assertTrue(merged.readText().contains("old.example"))

            ManualHostsRuleManager.saveRules(context, listOf(ManualHostsRule("0.0.0.0", "manual.example")))
            assertNull(HostsUpdateManager.pendingPreview(context))
            assertFalse(pending.exists())

            ManualHostsRuleManager.saveRules(context, emptyList())
            HostsUpdateManager.prepare(context)
            HostsUpdateManager.commit(context)
            assertTrue(cached.readText().contains("new.example"))
            assertTrue(merged.readText().contains("new.example"))
            assertFalse(pending.exists())
        } finally {
            server.close()
            serverThread.join(1000)
            pending.deleteRecursively(); applied.deleteRecursively(); previous.deleteRecursively()
            cached.delete(); merged.delete(); manual.delete(); config.delete()
        }
    }

    @Test
    fun reapplyingSameRulesDoesNotReplacePreviousVersion() {
        val context = RuntimeEnvironment.getApplication()
        val current = File(context.filesDir, "last_applied_hosts")
        val previous = File(context.filesDir, "previous_applied_hosts")
        val config = File(context.filesDir, "hosts_subscriptions.json")
        val manual = File(context.filesDir, "manual_hosts_rules.txt")
        val first = File(context.cacheDir, "first-hosts-test")
        val second = File(context.cacheDir, "second-hosts-test")
        try {
            current.deleteRecursively(); previous.deleteRecursively(); config.delete(); manual.delete()
            first.writeText("# Merged ADhosts - 1\n0.0.0.0 first.example\n")
            second.writeText("# Merged ADhosts - 2\n0.0.0.0 second.example\n")
            HostsUpdateManager.recordApplied(context, first)
            HostsUpdateManager.recordApplied(context, second)
            assertTrue(HostsUpdateManager.previousHosts(context).readText().contains("first.example"))

            second.writeText("# Merged ADhosts - 3\n0.0.0.0 second.example\n")
            HostsUpdateManager.recordApplied(context, second)
            assertTrue(HostsUpdateManager.previousHosts(context).readText().contains("first.example"))
        } finally {
            current.deleteRecursively(); previous.deleteRecursively(); config.delete(); manual.delete()
            first.delete(); second.delete()
        }
    }
}
