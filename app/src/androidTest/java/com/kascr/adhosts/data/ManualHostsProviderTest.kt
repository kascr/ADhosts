package com.kascr.adhosts.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualHostsProviderTest {
    @Test fun writableDraftIsIsolatedFromCommittedRules() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "manual_hosts_editor/document")
        directory.mkdirs()
        val draft = File(directory, "provider-test.txt")
        try {
            draft.writeText("# Initial comment\n")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.manualrules", draft)
            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write("# Saved in external editor\n0.0.0.0 edited.example\n".toByteArray())
            }
            val parsed = ManualHostsRuleManager.parseEditorText(draft.readText())
            assertTrue(parsed.isValid)
            assertEquals(listOf(ManualHostsRule("0.0.0.0", "edited.example")), parsed.rules)
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.manualrules",
                    File(context.filesDir, "manual_hosts_rules.txt"))
            }
        } finally { draft.delete() }
    }
}
