package com.kascr.adhosts.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One rollback boundary for configuration, source caches, snapshots and Root writes. */
internal object HostsOperationJournal {
    private const val NAME = "hosts_operation_journal"
    private val names = listOf("hosts_subscriptions.json", "manual_hosts_rules.txt", "ADhosts",
        "hosts_sources", "hosts_source_cache", "last_applied_hosts", "previous_applied_hosts",
        "pending_hosts_update")
    @Volatile private var active: File? = null
    private val completedActions = mutableListOf<() -> Unit>()

    suspend fun <T> run(context: Context, operation: suspend () -> T): T {
        withContext(Dispatchers.IO) { begin(context) }
        return try {
            val result = operation()
            withContext(Dispatchers.IO) { commit(context) }
            withContext(Dispatchers.IO) {
                val actions = synchronized(this@HostsOperationJournal) {
                    completedActions.toList().also { completedActions.clear() }
                }
                actions.forEach { action -> runCatching(action).onFailure { Log.w(NAME, "Post-commit cleanup deferred", it) } }
            }
            result
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { recover(context) }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    @Synchronized fun ensureRecovered(context: Context) {
        if (active == null && File(context.filesDir, NAME).exists()) recover(context)
    }

    @Synchronized fun begin(context: Context) {
        recover(context)
        if (!RootHostsStore.write("")) throw IOException("Root recovery is pending")
        HostsSubscriptionManager.getSubscriptions(context)
        HostsUpdateManager.recoverAppliedSnapshots(context)
        val preparing = File(context.filesDir, "$NAME.new")
        preparing.deleteRecursively()
        if (!preparing.mkdirs()) throw IOException("Cannot create operation journal")
        try {
            names.forEach { name -> copy(File(context.filesDir, name), File(preparing, name)) }
            RootHostsStore.snapshot(File(preparing, "root"))
            marker(File(preparing, "ready"))
            syncDirectory(preparing)
            val journal = File(context.filesDir, NAME)
            if (!preparing.renameTo(journal)) throw IOException("Cannot activate operation journal")
            syncDirectory(context.filesDir)
            active = journal
        } finally { preparing.deleteRecursively() }
    }

    @Synchronized fun beforeRootWrite() { active?.let { marker(File(it, "root-touched")) } }
    @Synchronized fun afterCommit(action: () -> Unit) {
        check(active != null)
        completedActions.add(action)
    }

    @Synchronized fun commit(context: Context) {
        val journal = active ?: return
        // Also sync the changed files before making the entire operation durable.
        names.forEach { syncTree(File(context.filesDir, it)) }
        marker(File(journal, "committed"))
        syncDirectory(journal)
        active = null
        finishJournal(context, journal)
    }

    @Synchronized fun recover(context: Context) {
        completedActions.clear()
        val journal = File(context.filesDir, NAME)
        File(context.filesDir, "$NAME.completed").deleteRecursively()
        if (!journal.exists()) { active = null; return }
        if (!File(journal, "ready").isFile) throw IOException("Incomplete operation journal")
        if (!File(journal, "committed").isFile) {
            // Remove inner journals first; their partial generation must not replay later.
            listOf("hosts_update_commit_journal", "applied_snapshot_journal").forEach { name ->
                if (!File(context.filesDir, name).deleteRecursively()) throw IOException("Cannot clear nested journal")
            }
            names.forEach { name ->
                val target = File(context.filesDir, name)
                if (target.exists() && !target.deleteRecursively()) throw IOException("Cannot restore $name")
                copy(File(journal, name), target)
            }
            if (File(journal, "root-touched").isFile) RootHostsStore.restore(File(journal, "root"))
            syncDirectory(context.filesDir)
        }
        active = null
        finishJournal(context, journal)
    }

    private fun finishJournal(context: Context, journal: File) {
        // Never delete the recovery markers while the journal still has its active name.
        val completed = File(context.filesDir, "$NAME.completed")
        if (completed.exists() && !completed.deleteRecursively()) throw IOException("Cannot clear completed journal")
        if (!journal.renameTo(completed)) throw IOException("Cannot finish operation journal")
        syncDirectory(context.filesDir)
        completed.deleteRecursively()
    }

    private fun copy(source: File, target: File) {
        if (!source.exists()) return
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) throw IOException("Cannot create backup directory")
            (source.listFiles() ?: throw IOException("Cannot read backup directory")).forEach {
                copy(it, File(target, it.name))
            }
            syncDirectory(target)
        } else {
            FileOutputStream(target).use { output ->
                source.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
        }
    }

    private fun syncTree(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach(::syncTree)
            syncDirectory(file)
        } else if (file.isFile) FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun marker(file: File) { FileOutputStream(file).use { it.write(1); it.fd.sync() }; syncDirectory(file.parentFile!!) }
    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
