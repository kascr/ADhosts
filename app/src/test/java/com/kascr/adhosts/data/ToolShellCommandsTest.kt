package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ToolShellCommandsTest {
    @Test
    fun scriptExitDoesNotTerminateParentOrSkipNextCommand() {
        val result = runCommands(listOf("printf 'first\\n'; exit 0", "printf 'second\\n'"))
        assertEquals(result.second, 0, result.first)
        assertTrue(result.second.contains("first\nsecond\nparent-alive"))
    }

    @Test
    fun earlierFailureStopsBatchAndPreservesExitCode() {
        val result = runCommands(listOf("printf 'first\\n'", "exit 7", "printf 'unexpected\\n'"))
        assertEquals(result.second, 7, result.first)
        assertTrue(result.second.contains("parent-alive"))
        assertFalse(result.second.contains("unexpected"))
    }

    @Test
    fun apostrophesAndMultilineScriptsSurviveBothQuotingLayers() {
        val result = runCommands(listOf("printf \"it's safe\\n\"\nprintf 'next line\\n'\nexit 0"))
        assertEquals(result.second, 0, result.first)
        assertTrue(result.second.contains("it's safe\nnext line\nparent-alive"))
    }

    private fun runCommands(commands: List<String>): Pair<Int, String> {
        val pathShells = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .flatMap { directory ->
                listOf(File(directory, "sh.exe"), File(File(directory).parentFile, "bin/bash.exe"))
            }
        val shell = (listOf(
            File("/bin/sh"),
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/bin/bash.exe"),
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
        ) + pathShells).firstOrNull { it.isFile }
        assumeNotNull(shell)
        val script = "PATH=/usr/bin:/bin:${'$'}PATH\n" + ToolShellCommands.buildCommand(commands) +
            "\nresult=${'$'}?\nprintf 'parent-alive\\n'\nexit ${'$'}result"
        // stdin avoids Windows command-line quoting changing the POSIX script under test.
        val process = ProcessBuilder(shell!!.absolutePath, "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(script) }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Tool command did not finish")
        }
        return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    }
}
