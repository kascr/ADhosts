package com.kascr.adhosts.data

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManualHostsEditorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val first = listOf(ManualHostsRule("0.0.0.0", "first.example"))

    @Before fun reset() {
        ManualHostsEditor.discard(context)
        ManualHostsRuleManager.saveRules(context, first)
    }

    @After fun cleanup() {
        ManualHostsEditor.discard(context)
        File(context.filesDir, "manual_hosts_rules.txt").delete()
    }

    @Test fun allLocalizedHelpAndExamplesAreComments() {
        for (tag in listOf("zh", "en", "ja", "ko", "de", "fr", "es", "pt", "ru", "vi", "id", "th")) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val template = ManualHostsEditor.template(context.createConfigurationContext(config))
            val parsed = ManualHostsRuleManager.parseEditorText(template)
            assertTrue(tag, parsed.isValid)
            assertTrue(tag, parsed.rules.isEmpty())
            assertEquals(tag, 0, HostsSubscriptionManager.countEffectiveRules(template.lineSequence()))
            assertTrue(tag, template.lineSequence().filter(String::isNotBlank).all { it.startsWith("#") })
        }
    }

    @Test fun changedDraftSurvivesResumeWithoutOverwritingActiveRules() {
        ManualHostsEditor.begin(context)
        val draft = ManualHostsEditor.document(context)
        draft.appendText("0.0.0.0 second.example # saved in MT\n")
        ManualHostsEditor.begin(context)
        val pending = ManualHostsEditor.pending(context)!!
        assertTrue(pending.parsed.isValid)
        assertEquals(2, pending.parsed.rules.size)
        assertEquals(first, ManualHostsRuleManager.getRules(context))
        ManualHostsRuleManager.saveRules(context, pending.parsed.rules)
        assertTrue(ManualHostsEditor.complete(context, pending.hash))
        assertNull(ManualHostsEditor.pending(context))
    }

    @Test fun commentOnlyChangesNeverTriggerAnImport() {
        ManualHostsEditor.begin(context)
        ManualHostsEditor.document(context).appendText("\n# notes: ignore.example\n")
        assertNull(ManualHostsEditor.pending(context))
        assertEquals(first, ManualHostsRuleManager.getRules(context))
    }

    @Test fun invalidDraftIsPreservedAndReviewedOnlyUntilNextEdit() {
        ManualHostsEditor.begin(context)
        val draft = ManualHostsEditor.document(context)
        draft.appendText("::: invalid.example\n")
        val pending = ManualHostsEditor.pending(context)!!
        assertFalse(pending.parsed.isValid)
        ManualHostsEditor.markReviewed(context, pending.hash)
        assertNull(ManualHostsEditor.pending(context))
        ManualHostsEditor.begin(context)
        assertEquals(pending.hash, ManualHostsEditor.pending(context)!!.hash)
        assertEquals(first, ManualHostsRuleManager.getRules(context))
        assertTrue(draft.readText().contains(":::"))
    }

    @Test fun detectsConcurrentChangesToActiveRules() {
        ManualHostsEditor.begin(context)
        ManualHostsEditor.document(context).appendText("second.example\n")
        ManualHostsRuleManager.saveRules(context, listOf(ManualHostsRule("0.0.0.0", "imported.example")))
        assertTrue(ManualHostsEditor.pending(context)!!.conflicting)
    }

    @Test fun commentsOnlyDraftRepresentsAnExplicitClear() {
        ManualHostsEditor.begin(context)
        ManualHostsEditor.document(context).writeText(ManualHostsEditor.template(context))
        val pending = ManualHostsEditor.pending(context)!!
        assertTrue(pending.parsed.isValid)
        assertTrue(pending.parsed.rules.isEmpty())
        assertEquals(first, ManualHostsRuleManager.getRules(context))
    }

    @Test fun saveDuringImportRemainsPending() {
        ManualHostsEditor.begin(context)
        val draft = ManualHostsEditor.document(context)
        draft.appendText("second.example\n")
        val importing = ManualHostsEditor.pending(context)!!
        ManualHostsRuleManager.saveRules(context, importing.parsed.rules)
        draft.appendText("third.example\n")
        assertFalse(ManualHostsEditor.complete(context, importing.hash))
        val next = ManualHostsEditor.pending(context)!!
        assertFalse(next.conflicting)
        assertEquals(3, next.parsed.rules.size)
    }

    @Test fun targetsEachMtTextEditorWithReadWritePermissions() {
        ManualHostsEditor.begin(context)
        val uri = Uri.parse("content://${context.packageName}.manualrules/manual_hosts/hosts.txt")
        for (name in ManualHostsEditor.packages) {
            val intent = ManualHostsEditor.editIntent(name, uri)
            assertEquals("$name.OpenTextActivity", intent.component!!.className)
            assertEquals(name, intent.component!!.packageName)
            assertEquals(Intent.ACTION_EDIT, intent.action)
            assertEquals("text/plain", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        }
    }

    @Test fun detectsBothInstalledPackagesAndFiltersDisabledApps() {
        assertTrue(ManualHostsEditor.installedEditors(context).isEmpty())
        val manager = Shadows.shadowOf(context.packageManager)
        for (name in ManualHostsEditor.packages) {
            manager.installPackage(PackageInfo().apply {
                packageName = name
                versionName = "test"
                applicationInfo = ApplicationInfo().apply {
                    packageName = name
                    enabled = true
                    nonLocalizedLabel = "MT Manager"
                }
            })
        }
        assertEquals(ManualHostsEditor.packages, ManualHostsEditor.installedEditors(context).map { it.packageName })
        context.packageManager.setApplicationEnabledSetting("bin.mt.plus",
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        assertEquals(listOf("bin.mt.plus.canary"), ManualHostsEditor.installedEditors(context).map { it.packageName })
    }

    @Test fun maximumLengthRulesCanBeReopenedWithHelpAndComments() {
        val rules = (0 until ManualHostsRuleManager.MAX_RULES).map { index ->
            val hostname = index.toString().padStart(5, '0') + "a".repeat(58) + "." +
                "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
            assertEquals(253, hostname.length)
            ManualHostsRule("2001:0db8:0000:0000:0000:0000:192.168.100.200", hostname)
        }
        ManualHostsRuleManager.saveRules(context, rules)
        ManualHostsEditor.begin(context)
        val document = ManualHostsEditor.document(context)
        assertTrue(document.length() > 2 * 1024 * 1024)
        document.appendText("# 保留用户注释\n")
        ManualHostsEditor.begin(context)
        assertNull(ManualHostsEditor.pending(context))
        assertEquals(rules, ManualHostsRuleManager.getRules(context))
        assertEquals(rules, ManualHostsRuleManager.parseEditorText(document.readText()).rules)
        assertTrue(document.length() <= ManualHostsRuleManager.MAX_TEXT_BYTES)
        assertTrue(document.readText().contains("# 保留用户注释"))
    }

    @Test fun utf8CommentsUseTheSameByteLimitAsGeneratedDocuments() {
        ManualHostsEditor.begin(context)
        val document = ManualHostsEditor.document(context)
        val available = ManualHostsRuleManager.MAX_TEXT_BYTES - document.length().toInt() - 2
        document.appendText("\n#" + "界".repeat(available / 3) + " ".repeat(available % 3))
        assertEquals(ManualHostsRuleManager.MAX_TEXT_BYTES.toLong(), document.length())
        assertNull(ManualHostsEditor.pending(context))

        document.appendText("界")
        assertThrows(ManualHostsEditor.DraftRecoveryRequired::class.java) {
            ManualHostsEditor.pending(context)
        }
        // Recovery remains explicit: the oversized draft is retained and cannot change committed rules.
        assertEquals(ManualHostsRuleManager.MAX_TEXT_BYTES + 3L, document.length())
        assertEquals(first, ManualHostsRuleManager.getRules(context))
    }

    @Test fun interruptedLargeSaveCanBeRecoveredWithoutImportingIt() {
        ManualHostsEditor.begin(context)
        val document = ManualHostsEditor.document(context)
        val stage = File(document.parentFile, "save_large.txt")
        val draft = "0.0.0.0 edited.example\n#" + "x".repeat(2 * 1024 * 1024)
        stage.writeText(draft)
        val marker = File(context.filesDir, "manual_hosts_editor/writing.txt")
        marker.writeText(stage.name)
        assertThrows(ManualHostsEditor.DraftRecoveryRequired::class.java) {
            ManualHostsEditor.pending(context)
        }

        ManualHostsEditor.recoverDraft(context)
        assertEquals(draft, document.readText())
        assertFalse(marker.exists())
        assertFalse(stage.exists())
        assertNull(ManualHostsEditor.pending(context))
        assertEquals(first, ManualHostsRuleManager.getRules(context))
    }

    @Test fun longerLocalizedHelpDoesNotPreventReopeningAFullCommittedDocument() {
        val localized = listOf("zh", "en", "ja", "ko", "de", "fr", "es", "pt", "ru", "vi", "id", "th")
            .map { tag ->
                val config = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                context.createConfigurationContext(config)
            }.sortedBy { ManualHostsEditor.template(it).toByteArray(Charsets.UTF_8).size }
        val shortest = localized.first()
        val longest = localized.last()
        assertTrue(ManualHostsEditor.template(longest).toByteArray(Charsets.UTF_8).size >
            ManualHostsEditor.template(shortest).toByteArray(Charsets.UTF_8).size)
        ManualHostsEditor.begin(shortest)
        val document = ManualHostsEditor.document(shortest)
        val available = ManualHostsRuleManager.MAX_TEXT_BYTES - document.length().toInt() - 2
        document.appendText("\n#" + "x".repeat(available))
        val oldText = document.readText()
        val importedHash = MessageDigest.getInstance("SHA-256")
            .digest(oldText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertTrue(ManualHostsEditor.complete(shortest, importedHash))

        ManualHostsEditor.begin(longest)
        assertEquals(oldText, document.readText())
        assertEquals(ManualHostsRuleManager.MAX_TEXT_BYTES.toLong(), document.length())
        assertNull(ManualHostsEditor.pending(longest))
        assertEquals(first, ManualHostsRuleManager.getRules(context))
    }
}
