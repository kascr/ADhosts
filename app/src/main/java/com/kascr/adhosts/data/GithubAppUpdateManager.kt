package com.kascr.adhosts.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class AppRelease(
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSha256: String? = null
)

object GithubAppUpdateManager {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/kascr/ADhosts/releases/latest"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    private const val MAX_APK_BYTES = 250L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MS = 15_000
    private val versionPattern = Regex("^[vV]?(\\d+(?:\\.\\d+){0,3})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

    /** The latest endpoint excludes drafts and prereleases. Null means no published release. */
    fun fetchLatestRelease(): AppRelease? {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "ADhosts-Android")
        try {
            return when (val status = connection.responseCode) {
                404 -> null
                200 -> parseRelease(readLimited(connection))
                else -> throw IOException("GitHub HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRelease(json: String): AppRelease {
        val release = JsonParser.parseString(json).asJsonObject
        val tag = release.string("tag_name")?.takeIf { parseVersion(it) != null }
            ?: throw IOException("Invalid release version")
        val page = release.string("html_url")?.takeIf(::isReleasePageUrl)
            ?: throw IOException("Invalid release page")
        val apks = release.getAsJsonArray("assets")?.mapNotNull { element ->
            val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = asset.string("name") ?: return@mapNotNull null
            val url = asset.string("browser_download_url") ?: return@mapNotNull null
            if (name.endsWith(".apk", ignoreCase = true) &&
                asset.string("state") == "uploaded" && isApkAssetUrl(url)) {
                val digest = asset.string("digest")
                    ?.takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }
                    ?.substringAfter(':')
                url to digest
            } else null
        }.orEmpty()
        // Multiple APKs may target different devices. Let GitHub present the selection.
        val apk = apks.singleOrNull()
        return AppRelease(tag, release.string("body").orEmpty(), page, apk?.first, apk?.second)
    }

    /** Downloads only the APK selected from this repository's release metadata. */
    fun downloadApk(release: AppRelease, target: File, onProgress: (Int) -> Unit = {}) {
        val apkUrl = release.apkUrl?.takeIf(::isApkAssetUrl)
            ?: throw IOException("No valid APK asset")
        var current = URL(apkUrl)
        var connection: HttpsURLConnection? = null
        try {
            for (redirect in 0..MAX_REDIRECTS) {
                if (!isAllowedDownloadUrl(current)) throw IOException("Untrusted APK download URL")
                connection = (current.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "ADhosts-Android")
                }
                val response = connection.responseCode
                if (response in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("APK redirect has no location")
                    current = URL(current, location)
                    connection.disconnect()
                    connection = null
                    continue
                }
                if (response != 200) throw IOException("APK HTTP $response")
                val length = connection.contentLengthLong
                if (length > MAX_APK_BYTES) throw IOException("APK exceeds size limit")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES) throw IOException("APK exceeds size limit")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (length > 0) {
                                val percent = (total * 100 / length).toInt().coerceAtMost(100)
                                if (percent >= lastPercent + 5 || percent == 100) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total == 0L || (length >= 0 && total != length)) {
                    throw IOException("Incomplete APK download")
                }
                val actualDigest = digest.digest().joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                if (release.apkSha256 != null &&
                    !actualDigest.equals(release.apkSha256, ignoreCase = true)) {
                    throw IOException("APK digest mismatch")
                }
                return
            }
            throw IOException("Too many APK redirects")
        } finally {
            connection?.disconnect()
        }
    }

    /** Positive if remote is newer, zero if equal, negative if installed is newer. */
    internal fun compareVersions(remote: String, installed: String): Int? {
        val first = parseVersion(remote) ?: return null
        val second = parseVersion(installed) ?: return null
        for (index in 0 until maxOf(first.first.size, second.first.size)) {
            val comparison = first.first.getOrElse(index) { 0L }
                .compareTo(second.first.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return when {
            first.second == null && second.second != null -> 1
            first.second != null && second.second == null -> -1
            else -> 0
        }
    }

    private fun parseVersion(value: String): Pair<List<Long>, String?>? {
        val match = versionPattern.matchEntire(value.trim()) ?: return null
        val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
        return numbers to match.groupValues[2].ifEmpty { null }
    }

    private fun isReleasePageUrl(value: String): Boolean = isGithubReleaseUrl(value, "/releases/tag/")
    private fun isApkAssetUrl(value: String): Boolean =
        isGithubReleaseUrl(value, "/releases/download/") &&
            URI(value).path.endsWith(".apk", ignoreCase = true)

    private fun isGithubReleaseUrl(value: String, suffix: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawPath?.startsWith("/kascr/ADhosts$suffix") == true
    }

    private fun isAllowedDownloadUrl(url: URL): Boolean =
        url.protocol.equals("https", ignoreCase = true) && url.port == -1 &&
            (url.host.equals("github.com", ignoreCase = true) ||
                url.host.equals("release-assets.githubusercontent.com", ignoreCase = true) ||
                url.host.equals("objects.githubusercontent.com", ignoreCase = true))

    private fun readLimited(connection: HttpsURLConnection): String {
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) throw IOException("Release response is too large")
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()
}
