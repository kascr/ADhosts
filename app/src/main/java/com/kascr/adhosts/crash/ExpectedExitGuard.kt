package com.kascr.adhosts.crash

/**
 * Marks a deliberate app shutdown so it cannot be mistaken for a program crash.
 * The flag is process-local and is reset whenever startup begins again.
 */
object ExpectedExitGuard {

    @Volatile
    private var expectedExit = false

    fun markExpected() {
        expectedExit = true
    }

    fun reset() {
        expectedExit = false
    }

    fun isExpected(): Boolean = expectedExit
}
