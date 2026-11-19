package com.cadence.shifts.util

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateCheckerTest {

    @Test
    fun classifyInstallSource_identifiesFdroidGithubAndDirectInstalls() {
        assertEquals(
            InstallSource.FDROID,
            AppUpdateChecker.classifyInstallSource(listOf("com.google.android.packageinstaller", "org.fdroid.fdroid"))
        )
        assertEquals(
            InstallSource.FDROID,
            AppUpdateChecker.classifyInstallSource(listOf("org.fdroid.basic"))
        )
        assertEquals(
            InstallSource.FDROID,
            AppUpdateChecker.classifyInstallSource(listOf("com.looker.droidify"))
        )
        assertEquals(
            InstallSource.GITHUB,
            AppUpdateChecker.classifyInstallSource(listOf("com.github.android"))
        )
        assertEquals(
            InstallSource.DIRECT_OR_UNKNOWN,
            AppUpdateChecker.classifyInstallSource(listOf("com.android.chrome"))
        )
        assertEquals(
            InstallSource.DIRECT_OR_UNKNOWN,
            AppUpdateChecker.classifyInstallSource(emptyList())
        )
    }

    @Test
    fun compareVersionNamesAndIsUpdateAvailable_handlesNumericSegmentsAndVersionCode() {
        assertTrue(AppUpdateChecker.compareVersionNames("1.1.10", "1.1.9") > 0)
        assertTrue(AppUpdateChecker.compareVersionNames("v1.2.0", "1.1.99") > 0)
        assertEquals(0, AppUpdateChecker.compareVersionNames("v1.1.6", "1.1.6"))
        assertTrue(AppUpdateChecker.compareVersionNames("1.1.5", "1.1.6") < 0)

        assertTrue(
            AppUpdateChecker.isUpdateAvailable(
                currentVersionName = "1.1.6",
                currentVersionCode = 10,
                latestVersionName = "1.1.7",
                latestVersionCode = 11
            )
        )
        // Same versionName, higher versionCode triggers update
        assertTrue(
            AppUpdateChecker.isUpdateAvailable(
                currentVersionName = "1.1.6",
                currentVersionCode = 10,
                latestVersionName = "1.1.6",
                latestVersionCode = 11
            )
        )
        // Already up-to-date
        assertFalse(
            AppUpdateChecker.isUpdateAvailable(
                currentVersionName = "1.1.7",
                currentVersionCode = 11,
                latestVersionName = "1.1.7",
                latestVersionCode = 11
            )
        )
        // Local unreleased build ahead of remote does not prompt
        assertFalse(
            AppUpdateChecker.isUpdateAvailable(
                currentVersionName = "1.2.0",
                currentVersionCode = 20,
                latestVersionName = "1.1.9",
                latestVersionCode = 19
            )
        )
    }

    @Test
    fun parseGitHubLatestReleaseJson_extractsTagUrlAndNotes() {
        val json = """
            {
              "tag_name": "v1.1.8",
              "html_url": "https://github.com/borisli713/Cadence-Mobile/releases/tag/v1.1.8",
              "draft": false,
              "prerelease": false,
              "body": "## What's Changed\n- Fix background notification delivery\n- Add shift history view\n\n**Full Changelog**: https://github.com/..."
            }
        """.trimIndent()

        val parsed = AppUpdateChecker.parseGitHubLatestReleaseJson(json)
        assertNotNull(parsed)
        assertEquals("1.1.8", parsed?.versionName)
        assertEquals("https://github.com/borisli713/Cadence-Mobile/releases/tag/v1.1.8", parsed?.downloadPageUrl)
        assertTrue(parsed?.releaseNotes.orEmpty().contains("Fix background notification delivery"))

        val draftJson = """{"tag_name": "v1.1.9", "draft": true, "prerelease": false}"""
        assertNull(AppUpdateChecker.parseGitHubLatestReleaseJson(draftJson))
    }

    @Test
    fun parseFdroidIndexV2AndV1_picksHighestVersionForPackage() {
        val v2Json = """
            {
              "packages": {
                "com.cadence.shifts": {
                  "versions": {
                    "hash1": {
                      "manifest": { "versionName": "1.1.5", "versionCode": 8 }
                    },
                    "hash2": {
                      "manifest": { "versionName": "1.1.7", "versionCode": 12 },
                      "whatsNew": { "en-US": "• Improved background alert reliability" }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val v2 = AppUpdateChecker.parseFdroidIndexV2Json(v2Json, "com.cadence.shifts")
        assertNotNull(v2)
        assertEquals("1.1.7", v2?.versionName)
        assertEquals(12L, v2?.versionCode)
        assertEquals(AppUpdateChecker.FDROID_REPO_PAGE_URL, v2?.downloadPageUrl)

        val v1Json = """
            {
              "packages": {
                "com.cadence.shifts": [
                  { "versionName": "1.1.4", "versionCode": 7 },
                  { "versionName": "1.1.6", "versionCode": 11 }
                ]
              }
            }
        """.trimIndent()

        val v1 = AppUpdateChecker.parseFdroidIndexV1Json(v1Json, "com.cadence.shifts")
        assertNotNull(v1)
        assertEquals("1.1.6", v1?.versionName)
        assertEquals(11L, v1?.versionCode)
        assertEquals(AppUpdateChecker.FDROID_REPO_PAGE_URL, v1?.downloadPageUrl)
    }

    @Test
    fun parsePagesIndexHtmlFallback_extractsLatestReleaseFromSiteHtml() {
        val html = """
            <div class="status-pill"><span>Latest Release • v1.1.9</span></div>
            <a class="btn btn-primary" href="repo/Cadence-Mobile-v1.1.9.apk">Download</a>
        """.trimIndent()

        val parsed = AppUpdateChecker.parsePagesIndexHtmlFallback(html)
        assertNotNull(parsed)
        assertEquals("1.1.9", parsed?.versionName)
        assertEquals(AppUpdateChecker.FDROID_REPO_PAGE_URL, parsed?.downloadPageUrl)
    }

    @Test
    fun getInstalledAppInfo_detectsFdroidInstallerOnPackageManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        context.packageManager.setInstallerPackageName(context.packageName, "org.fdroid.fdroid")
        val info = AppUpdateChecker.getInstalledAppInfo(context)
        assertNotNull(info.versionName)
        assertTrue(info.versionCode >= 1L)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || info.primaryInstallerPackage == "org.fdroid.fdroid") {
            assertEquals(InstallSource.FDROID, info.installSource)
        }
    }
}
