package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubAppUpdateManagerTest {

    @Test
    fun comparesPublishedAndInstalledVersions() {
        assertEquals(1, GithubAppUpdateManager.compareVersions("v2.3.0", "2.2.0"))
        assertEquals(0, GithubAppUpdateManager.compareVersions("v2.2", "2.2.0"))
        assertEquals(-1, GithubAppUpdateManager.compareVersions("v1.1.8", "2.2.0"))
        assertNull(GithubAppUpdateManager.compareVersions("latest", "2.2.0"))
    }

    @Test
    fun parsesSingleUploadedApkFromRepositoryRelease() {
        val release = GithubAppUpdateManager.parseRelease(releaseJson("""
            {"name":"ADhosts_v2.3.0.apk","state":"uploaded",
             "digest":"sha256:${"a".repeat(64)}",
             "browser_download_url":"https://github.com/kascr/ADhosts/releases/download/v2.3.0/ADhosts_v2.3.0.apk"}
        """))

        assertEquals("v2.3.0", release.tag)
        assertEquals("Changes", release.notes)
        assertEquals("https://github.com/kascr/ADhosts/releases/tag/v2.3.0", release.pageUrl)
        assertEquals("https://github.com/kascr/ADhosts/releases/download/v2.3.0/ADhosts_v2.3.0.apk", release.apkUrl)
        assertEquals("a".repeat(64), release.apkSha256)
    }

    @Test
    fun multipleApksUseReleasePageForSelection() {
        val asset = """{"name":"ADhosts.apk","state":"uploaded",
            "browser_download_url":"https://github.com/kascr/ADhosts/releases/download/v2.3.0/ADhosts.apk"}"""
        assertNull(GithubAppUpdateManager.parseRelease(releaseJson("$asset,$asset")).apkUrl)
    }

    @Test
    fun ignoresExternalApkLink() {
        val release = GithubAppUpdateManager.parseRelease(releaseJson("""
            {"name":"ADhosts.apk","state":"uploaded",
             "browser_download_url":"https://example.com/ADhosts.apk"}
        """))
        assertNull(release.apkUrl)
    }

    private fun releaseJson(assets: String) = """
        {"tag_name":"v2.3.0","body":"Changes",
         "html_url":"https://github.com/kascr/ADhosts/releases/tag/v2.3.0",
         "assets":[$assets]}
    """
}
