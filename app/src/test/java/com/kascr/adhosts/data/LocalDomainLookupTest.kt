package com.kascr.adhosts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class LocalDomainLookupTest {
    @Test fun oldIputilsHeaderResolvesBeforeAnyIcmpReply() = runBlocking {
        val process = FakeProcess("PING ads.example (127.0.0.1) 56(84) bytes of data.\n", exitCode = 1)
        val result = LocalDomainLookup.execute(listOf("ping"), ipv6 = false) { process }
        assertEquals(LocalDomainLookup.Status.RESOLVED, result.status)
        assertEquals(listOf("127.0.0.1"), result.addresses)
        awaitCleanup(process)
    }

    @Test fun cnamePrintedInTheHeaderRemainsAValidResolution() {
        val answer = LocalDomainLookup.parseOutput(
            "PING www.wshifen.com (103.235.46.96) 56(84) bytes of data.\n" +
                "\n--- www.wshifen.com ping statistics ---\n" +
                "1 packets transmitted, 0 received, 100% packet loss\n",
            ipv6 = false
        )
        assertEquals(LocalDomainLookup.Status.RESOLVED, answer.status)
        assertEquals(listOf("103.235.46.96"), answer.addresses)
    }

    @Test fun zeroAndLoopbackAddressesAreReturnedWithoutInterpretingIcmp() {
        for (address in listOf("0.0.0.0", "127.0.0.1", "127.8.9.10")) {
            assertEquals(listOf(address), LocalDomainLookup.parseOutput(
                "PING ads.example ($address): 56 data bytes", ipv6 = false
            ).addresses)
        }
    }

    @Test fun legacyPing6HeaderWithoutASpaceAndCompressedAddressesAreAccepted() {
        assertEquals(listOf("::1"), LocalDomainLookup.parseOutput(
            "PING localhost(::1) 56 data bytes", ipv6 = true
        ).addresses)
        assertEquals(listOf("2001:db8::ab"), LocalDomainLookup.parseOutput(
            "PING example.com(2001:DB8::AB) 56 data bytes", ipv6 = true
        ).addresses)
        assertEquals(listOf("::"), LocalDomainLookup.parseOutput(
            "PING ads.example(::) 56 data bytes", ipv6 = true
        ).addresses)
    }

    @Test fun scopedAndBracketedIpv6TargetsAreValidatedAndPreserved() {
        assertEquals(listOf("fe80::1%wlan0"), LocalDomainLookup.parseOutput(
            "PING example.com ([fe80::1%wlan0]): 56 data bytes", ipv6 = true
        ).addresses)
        assertEquals(listOf("fe80::1%42"), LocalDomainLookup.parseOutput(
            "PING example.com(fe80::1%42) 56 data bytes", ipv6 = true
        ).addresses)
    }

    @Test fun unrelatedMessagesMalformedTargetsAndUnsafeHeadersNeverBecomeAnswers() {
        val badHeaders = listOf(
            "message: PING example.com (1.2.3.4) 56 data bytes",
            "PING example.com (999.2.3.4) 56 data bytes",
            "PING example.com (other.example.com) 56 data bytes",
            "PING example.com (:::1) 56 data bytes",
            "PING example.com (fe80::1%wlan0;command) 56 data bytes",
            "PING -c100 (1.2.3.4) 56 data bytes",
            "PING x;command (1.2.3.4) 56 data bytes",
            "PING example.com (1.2.3.4) 56 data bytes; command",
            "64 bytes from example.com (1.2.3.4): icmp_seq=1"
        )
        for (header in badHeaders) {
            assertEquals(header, LocalDomainLookup.Status.FAILED,
                LocalDomainLookup.parseOutput(header, ipv6 = false).status)
            assertEquals(header, LocalDomainLookup.Status.FAILED,
                LocalDomainLookup.parseOutput(header, ipv6 = true).status)
        }
        assertEquals(LocalDomainLookup.Status.FAILED,
            LocalDomainLookup.parseOutput("PING localhost(::1) 56 data bytes", ipv6 = false).status)
    }

    @Test fun resolutionErrorsTimeoutsAndUnsupportedToolsHaveSeparateStatuses() {
        for (message in listOf(
            "unknown host", "unknown host example.com", "ping6: unknown host example.com", "ping: bad address 'example.com'",
            "ping: example.com: Name or service not known"
        )) assertEquals(LocalDomainLookup.Status.UNRESOLVED,
            LocalDomainLookup.parseOutput(message, ipv6 = false).status)
        assertEquals(LocalDomainLookup.Status.TIMEOUT,
            LocalDomainLookup.parseOutput("", ipv6 = false, timedOut = true).status)
        assertEquals(LocalDomainLookup.Status.FAILED,
            LocalDomainLookup.parseOutput("ping: socket: Operation not permitted", ipv6 = false).status)
        assertEquals(LocalDomainLookup.Status.UNAVAILABLE,
            LocalDomainLookup.parseOutput("ping: invalid option -- '6'", ipv6 = true).status)
        assertEquals(LocalDomainLookup.Status.RESOLVED,
            LocalDomainLookup.parseOutput("PING example.com (1.2.3.4) 56 data bytes", false, timedOut = true).status)
    }

    @Test fun commandsAvoidUnsupportedFamilyFlagsAndOnlyFallbackWhenPing6IsMissing() {
        assertEquals(listOf("/system/bin/ping", "-n", "-c1", "-W1", "-w3", "example.com"),
            LocalDomainLookup.command("EXAMPLE.COM.", ipv6 = false, ping6Exists = true))
        assertEquals(listOf("/system/bin/ping6", "-n", "-c1", "-W1", "-w3", "example.com"),
            LocalDomainLookup.command("example.com", ipv6 = true, ping6Exists = true))
        assertEquals(listOf("/system/bin/ping", "-6", "-n", "-c1", "-W1", "-w3", "example.com"),
            LocalDomainLookup.command("example.com", ipv6 = true, ping6Exists = false))
    }

    @Test fun malformedHostnamesAreRejectedBeforeStartingAnyProcess() {
        for (domain in listOf("-c100", "example.com;id", "example.com\nlocalhost", "$(id)", "https://example.com")) {
            assertThrows(domain, IllegalArgumentException::class.java) {
                LocalDomainLookup.command(domain, ipv6 = false, ping6Exists = false)
            }
        }
    }

    @Test fun missingExecutableIsUnavailable() = runBlocking {
        val answer = LocalDomainLookup.execute(listOf("missing"), ipv6 = false) { throw IOException("missing") }
        assertEquals(LocalDomainLookup.Status.UNAVAILABLE, answer.status)
    }

    @Test fun blockedOutputReadTimesOutAndDestroysTheProcessAndStreams() = runBlocking {
        val process = FakeProcess(output = null)
        val answer = LocalDomainLookup.execute(listOf("ping"), ipv6 = false, timeoutMillis = 100) { process }
        assertEquals(LocalDomainLookup.Status.TIMEOUT, answer.status)
        awaitCleanup(process)
    }

    @Test fun callerCancellationDestroysTheProcessInsteadOfReturningAQueryFailure() = runBlocking {
        val process = FakeProcess(output = null)
        val lookup = async(Dispatchers.Default) {
            LocalDomainLookup.execute(listOf("ping"), ipv6 = false) { process }
        }
        assertTrue(process.readStarted.await(2, TimeUnit.SECONDS))
        lookup.cancel()
        lookup.join()
        assertTrue(lookup.isCancelled)
        awaitCleanup(process)
    }

    private fun awaitCleanup(process: FakeProcess) {
        assertTrue("Process must be destroyed", process.destroyed.await(2, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!process.inputClosed.get() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Process stream must close", process.inputClosed.get())
    }

    private class FakeProcess(output: String?, private val exitCode: Int = 0) : Process() {
        val destroyed = CountDownLatch(1)
        val readStarted = CountDownLatch(1)
        val inputClosed = AtomicBoolean()
        private val bytes = output?.let { ByteArrayInputStream(it.toByteArray()) }
        private val stream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readStarted.countDown()
                if (bytes != null) return bytes.read(buffer, offset, length)
                try {
                    destroyed.await()
                } catch (_: InterruptedException) {
                    throw IOException("cancelled")
                }
                return -1
            }
            override fun close() { inputClosed.set(true) }
        }
        override fun getInputStream(): InputStream = stream
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = bytes != null || destroyed.count == 0L
        override fun exitValue(): Int = exitCode
        override fun destroy() { destroyed.countDown() }
    }
}
