package com.kascr.adhosts.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppUpdateInstallerTest {

    @Test
    fun removesStaleUpdateApksButKeepsRecentAndOtherFiles() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "app_updates").apply { mkdirs() }
        val stale = File(directory, "adhosts_update_stale.apk").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 2L * 60 * 60 * 1000)
        }
        val recent = File(directory, "adhosts_update_recent.apk").apply { writeText("new") }
        val unrelated = File(directory, "other.apk").apply {
            writeText("keep")
            setLastModified(System.currentTimeMillis() - 2L * 60 * 60 * 1000)
        }
        try {
            AppUpdateInstaller.cleanupStaleDownloads(context)
            assertFalse(stale.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
        } finally {
            stale.delete()
            recent.delete()
            unrelated.delete()
        }
    }
}
