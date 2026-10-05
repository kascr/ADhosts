package com.kascr.adhosts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Uses fresh native resolver processes, including the system Hosts file, without changing DNS. */
internal object LocalDomainLookup {
    enum class Status { RESOLVED, UNRESOLVED, TIMEOUT, FAILED, UNAVAILABLE }
    data class Answer(val status: Status, val addresses: List<String> = emptyList())
    data class Result(val ipv4: Answer, val ipv6: Answer)

    private const val QUERY_TIMEOUT_MS = 5_000L
    private const val MAX_OUTPUT_BYTES = 16 * 1024
    private val pingHeader = Regex(
        "^PING[ \\t]+([^\\s()]+)[ \\t]*\\(([^()]+)\\):?[ \\t]+" +
            "[0-9]{1,5}(?:\\([0-9]{1,5}\\))?[ \\t]+(?:bytes of data|data bytes)\\.?$"
    )
    private val scopePattern = Regex("[a-zA-Z0-9_.-]{1,64}")
    private val resolutionErrors = listOf(
        "unknown host", "bad address", "name or service not known",
        "temporary failure in name resolution", "no address associated with hostname",
        "nodename nor servname provided", "cannot resolve", "unable to resolve"
    )

    suspend fun lookup(domain: String): Result = withContext(Dispatchers.IO) {
        val normalized = HostsRuleValidator.normalizeHostname(domain)
        require(normalized != null) { "Invalid hostname" }
        val ipv4 = async { execute(command(normalized, ipv6 = false), ipv6 = false) }
        val ipv6 = async { execute(command(normalized, ipv6 = true), ipv6 = true) }
        Result(ipv4.await(), ipv6.await())
    }

    internal fun command(
        domain: String,
        ipv6: Boolean,
        ping6Exists: Boolean = File("/system/bin/ping6").exists()
    ): List<String> {
        val normalized = HostsRuleValidator.normalizeHostname(domain)
        require(normalized != null) { "Invalid hostname" }
        val executable = if (ipv6 && ping6Exists) "/system/bin/ping6" else "/system/bin/ping"
        val family = if (ipv6 && !ping6Exists) listOf("-6") else emptyList()
        return listOf(executable) + family + listOf("-n", "-c1", "-W1", "-w3", normalized)
    }

    internal fun parseOutput(output: String, ipv6: Boolean, timedOut: Boolean = false): Answer {
        for (line in output.lineSequence()) {
            val match = pingHeader.matchEntire(line.trim()) ?: continue
            val displayedHost = match.groupValues[1]
            if (HostsRuleValidator.normalizeHostname(displayedHost) == null &&
                numericAddress(displayedHost, ipv6 = null) == null
            ) continue
            // Native ping can print a CNAME instead of the original query hostname.
            val address = numericAddress(match.groupValues[2], ipv6) ?: continue
            return Answer(Status.RESOLVED, listOf(address))
        }
        val unresolved = output.lineSequence().any { line ->
            resolutionErrors.any { phrase ->
                line.trim().startsWith(phrase, ignoreCase = true) ||
                    (line.trimStart().startsWith("ping", ignoreCase = true) &&
                        line.contains(phrase, ignoreCase = true))
            }
        }
        if (unresolved) return Answer(Status.UNRESOLVED)
        val unsupported = output.lineSequence().any { line ->
            line.trimStart().startsWith("ping", ignoreCase = true) &&
                listOf("invalid option", "unknown option", "unrecognized option", "bad option")
                    .any { line.contains(it, ignoreCase = true) }
        }
        if (unsupported) return Answer(Status.UNAVAILABLE)
        return Answer(if (timedOut) Status.TIMEOUT else Status.FAILED)
    }

    private fun numericAddress(value: String, ipv6: Boolean?): String? {
        if (value.length > 128) return null
        val address = when {
            value.startsWith('[') && value.endsWith(']') -> value.substring(1, value.lastIndex)
            '[' in value || ']' in value -> return null
            else -> value
        }
        val base = address.substringBefore('%')
        val scope = address.substringAfter('%', missingDelimiterValue = "")
        val isIpv6 = ':' in base
        if (ipv6 != null && isIpv6 != ipv6) return null
        if ('%' in address && (!isIpv6 || !scopePattern.matches(scope))) return null
        if (!HostsRuleValidator.isNumericIpAddress(base)) return null
        return base.lowercase(Locale.ROOT) + if ('%' in address) "%$scope" else ""
    }

    /** Reads are bounded and cancellable; ICMP delivery and process exit do not decide resolution. */
    internal suspend fun execute(
        command: List<String>,
        ipv6: Boolean,
        timeoutMillis: Long = QUERY_TIMEOUT_MS,
        startProcess: (List<String>) -> Process = {
            ProcessBuilder(it).redirectErrorStream(true).start()
        }
    ): Answer = withContext(Dispatchers.IO) {
        val observedAnswer = AtomicReference<Answer?>()
        withTimeoutOrNull(timeoutMillis.coerceIn(1L, QUERY_TIMEOUT_MS)) {
            suspendCancellableCoroutine { continuation ->
                val process = try {
                    startProcess(command)
                } catch (_: IOException) {
                    continuation.resume(Answer(Status.UNAVAILABLE))
                    return@suspendCancellableCoroutine
                } catch (_: SecurityException) {
                    continuation.resume(Answer(Status.UNAVAILABLE))
                    return@suspendCancellableCoroutine
                }
                val deadline = System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceIn(1L, QUERY_TIMEOUT_MS))
                val worker = Thread({
                    try {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        var completedAnswer: Answer? = null
                        while (continuation.isActive) {
                            val count = process.inputStream.read(buffer)
                            if (count < 0) break
                            if (count == 0) {
                                Thread.sleep(10)
                                continue
                            }
                            val keep = count.coerceAtMost(MAX_OUTPUT_BYTES - output.size())
                            if (keep > 0) output.write(buffer, 0, keep)
                            val answer = parseOutput(output.toString(Charsets.UTF_8.name()), ipv6)
                            if (answer.status == Status.RESOLVED || answer.status == Status.UNRESOLVED ||
                                answer.status == Status.UNAVAILABLE
                            ) {
                                observedAnswer.set(answer)
                                completedAnswer = answer
                                break
                            }
                        }
                        if (continuation.isActive) {
                            val answer = completedAnswer ?: run {
                                val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
                                val exited = process.waitFor(remaining, TimeUnit.NANOSECONDS)
                                parseOutput(output.toString(Charsets.UTF_8.name()), ipv6, timedOut = !exited)
                            }
                            if (continuation.isActive) continuation.resume(answer)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        if (continuation.isActive) continuation.resume(Answer(Status.FAILED))
                    } catch (_: IOException) {
                        if (continuation.isActive) continuation.resume(Answer(Status.FAILED))
                    } catch (_: RuntimeException) {
                        if (continuation.isActive) continuation.resume(Answer(Status.FAILED))
                    } finally {
                        stopProcess(process)
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                        runCatching { process.outputStream.close() }
                    }
                }, "ADhosts-local-resolver").apply { isDaemon = true }
                continuation.invokeOnCancellation {
                    // Killing the child unblocks its pipe read; stream cleanup stays off the UI thread.
                    stopProcess(process)
                    worker.interrupt()
                }
                worker.start()
            }
        } ?: observedAnswer.get() ?: Answer(Status.TIMEOUT)
    }

    private fun stopProcess(process: Process) {
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
    }
}
