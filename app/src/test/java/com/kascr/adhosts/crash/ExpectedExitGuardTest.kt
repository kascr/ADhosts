package com.kascr.adhosts.crash

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedExitGuardTest {

    @After
    fun tearDown() {
        ExpectedExitGuard.reset()
    }

    @Test
    fun markExpected_marksCurrentProcessExitAsDeliberate() {
        ExpectedExitGuard.reset()

        ExpectedExitGuard.markExpected()

        assertTrue(ExpectedExitGuard.isExpected())
    }

    @Test
    fun reset_allowsFutureCrashesToBeReported() {
        ExpectedExitGuard.markExpected()

        ExpectedExitGuard.reset()

        assertFalse(ExpectedExitGuard.isExpected())
    }
}
