package com.kascr.adhosts.data

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Recovery data is disposable only after its active directory name has been retired. */
internal object CompletedJournalCleanup {
    fun discardCompleted(journal: File) {
        completedDirectory(journal).deleteRecursively()
    }

    fun finish(journal: File, afterRetirement: (() -> Unit)? = null) {
        val parent = journal.parentFile ?: throw IOException("Journal has no parent")
        val completed = completedDirectory(journal)
        if (completed.exists() && !completed.deleteRecursively()) {
            throw IOException("Cannot clear completed journal")
        }
        if (!journal.renameTo(completed)) throw IOException("Cannot finish recovery journal")
        val descriptor = Os.open(parent.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        afterRetirement?.invoke()
        // A killed cleanup can leave arbitrary files here; recovery ignores this inactive name.
        completed.deleteRecursively()
    }

    private fun completedDirectory(journal: File) = File(journal.parentFile, "${journal.name}.completed")
}
