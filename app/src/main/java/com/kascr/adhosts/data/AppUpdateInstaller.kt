package com.kascr.adhosts.data

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException

enum class AppUpdateFailure {
    ROOT_REQUIRED,
    INVALID_APK,
    WRONG_PACKAGE,
    SIGNATURE_MISMATCH,
    VERSION_NOT_NEWER,
    RELEASE_MISMATCH,
    INSTALL_FAILED
}

class AppUpdateException(val reason: AppUpdateFailure, detail: String? = null) :
    IOException(detail ?: reason.name)

object AppUpdateInstaller {
    private const val DOWNLOAD_DIRECTORY = "app_updates"
    private const val STALE_AFTER_MS = 60L * 60 * 1000

    fun requireRoot() {
        if (!runCatching { Shell.getShell().isRoot }.getOrDefault(false)) {
            throw AppUpdateException(AppUpdateFailure.ROOT_REQUIRED)
        }
    }

    fun createTemporaryApk(context: Context): File {
        cleanupStaleDownloads(context)
        val directory = File(context.cacheDir, DOWNLOAD_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create update cache")
        }
        return File.createTempFile("adhosts_update_", ".apk", directory)
    }

    fun verifyApk(context: Context, release: AppRelease, apk: File) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw AppUpdateException(AppUpdateFailure.INVALID_APK)
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) {
            throw AppUpdateException(AppUpdateFailure.WRONG_PACKAGE)
        }
        if (archive.longVersionCode <= installed.longVersionCode) {
            throw AppUpdateException(AppUpdateFailure.VERSION_NOT_NEWER)
        }
        if (GithubAppUpdateManager.compareVersions(
                archive.versionName.orEmpty(), release.tag
            ) != 0) {
            throw AppUpdateException(AppUpdateFailure.RELEASE_MISMATCH)
        }
        val archiveSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val installedSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
            throw AppUpdateException(AppUpdateFailure.SIGNATURE_MISMATCH)
        }
    }

    fun installAsRoot(apk: File) {
        requireRoot()
        val quotedPath = shellQuote(apk.absolutePath)
        // The shell deletes the APK even if updating this app terminates its process.
        val command = "pm install -r $quotedPath; update_status=\$?; " +
            "rm -f $quotedPath; [ \"\$update_status\" -eq 0 ]"
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) {
            throw AppUpdateException(
                AppUpdateFailure.INSTALL_FAILED,
                (result.out + result.err).joinToString("\n").take(1000)
            )
        }
    }

    fun cleanupStaleDownloads(context: Context) {
        val directory = File(context.cacheDir, DOWNLOAD_DIRECTORY)
        val threshold = System.currentTimeMillis() - STALE_AFTER_MS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("adhosts_update_") &&
                file.name.endsWith(".apk") && file.lastModified() < threshold) {
                file.delete()
            }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
}
