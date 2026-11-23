package com.cadence.shifts.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.pm.PackageInfoCompat
import com.cadence.shifts.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

enum class InstallSource(val label: String, @StringRes val labelRes: Int) {
    FDROID("F-Droid", R.string.install_source_fdroid),
    GITHUB("GitHub", R.string.install_source_github),
    DIRECT_OR_UNKNOWN("Direct APK", R.string.install_source_direct)
}

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long,
    val installSource: InstallSource,
    val primaryInstallerPackage: String?
)

data class RemoteReleaseInfo(
    val versionName: String,
    val versionCode: Long? = null,
    val releaseNotes: String = "",
    val downloadPageUrl: String,
    val checkedFromSource: InstallSource
)

data class UpdateCheckResult(
    val installed: AppVersionInfo,
    val latest: RemoteReleaseInfo,
    val updateAvailable: Boolean
)

object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"

    const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/borisli713/Cadence-Mobile/releases/latest"
    const val GITHUB_RELEASES_PAGE_URL =
        "https://github.com/borisli713/Cadence-Mobile/releases/latest"
    const val FDROID_REPO_PAGE_URL =
        "https://borisli713.github.io/Cadence-Mobile/"
    const val FDROID_INDEX_V2_URL =
        "https://borisli713.github.io/Cadence-Mobile/repo/index-v2.json"
    const val FDROID_INDEX_V1_URL =
        "https://borisli713.github.io/Cadence-Mobile/repo/index-v1.json"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    private val FDROID_CLIENT_PACKAGES = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "org.fdroid.fdroid.privileged",
        "com.looker.droidify",
        "com.machiav3lli.fdroid",
        "nya.kitsunyan.foxydroid",
        "com.aurora.adroid"
    )

    private val GITHUB_CLIENT_PACKAGES = setOf(
        "com.github.android"
    )

    fun getInstalledAppInfo(context: Context): AppVersionInfo {
        val pm = context.packageManager
        val pkgName = context.packageName

        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, 0)
            }
        }.getOrNull()

        val versionName = pkgInfo?.versionName?.trim().orEmpty().ifBlank { "1.0.0" }
        val versionCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 1L
        val installers = detectInstallerPackages(pm, pkgName)
        val source = classifyInstallSource(installers)

        return AppVersionInfo(
            versionName = versionName.removePrefix("v").removePrefix("V"),
            versionCode = versionCode,
            installSource = source,
            primaryInstallerPackage = installers.firstOrNull()
        )
    }

    private fun detectInstallerPackages(pm: PackageManager, packageName: String): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val info = pm.getInstallSourceInfo(packageName)
                listOfNotNull(
                    info.initiatingPackageName,
                    info.originatingPackageName,
                    info.installingPackageName
                ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
            }.getOrElse { emptyList() }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                listOfNotNull(pm.getInstallerPackageName(packageName))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }.getOrElse { emptyList() }
        }
    }

    internal fun classifyInstallSource(installerPackages: List<String>): InstallSource {
        val normalized = installerPackages.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (normalized.any { pkg ->
                pkg in FDROID_CLIENT_PACKAGES ||
                    pkg.contains("fdroid") ||
                    pkg.contains("droidify") ||
                    pkg.contains("neostore")
            }
        ) {
            return InstallSource.FDROID
        }
        if (normalized.any { pkg ->
                pkg in GITHUB_CLIENT_PACKAGES || pkg.contains("github")
            }
        ) {
            return InstallSource.GITHUB
        }
        return InstallSource.DIRECT_OR_UNKNOWN
    }

    suspend fun checkForUpdate(context: Context): UpdateCheckResult? = withContext(Dispatchers.IO) {
        val installed = getInstalledAppInfo(context)
        val packageName = context.packageName

        val remote = try {
            when (installed.installSource) {
                InstallSource.FDROID -> {
                    fetchLatestFromFdroid(packageName)
                        ?: fetchLatestFromGitHub()
                }
                InstallSource.GITHUB -> {
                    fetchLatestFromGitHub()
                        ?: fetchLatestFromFdroid(packageName)
                }
                InstallSource.DIRECT_OR_UNKNOWN -> {
                    val gh = fetchLatestFromGitHub()
                    val fd = if (gh == null) fetchLatestFromFdroid(packageName) else null
                    gh ?: fd
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        } ?: return@withContext null

        val targetUrl = when (installed.installSource) {
            InstallSource.FDROID -> FDROID_REPO_PAGE_URL
            InstallSource.GITHUB -> {
                if (remote.checkedFromSource == InstallSource.GITHUB && remote.downloadPageUrl.isNotBlank()) {
                    remote.downloadPageUrl
                } else {
                    GITHUB_RELEASES_PAGE_URL
                }
            }
            InstallSource.DIRECT_OR_UNKNOWN -> remote.downloadPageUrl
        }

        val resolvedRemote = remote.copy(downloadPageUrl = targetUrl)
        val available = isUpdateAvailable(
            currentVersionName = installed.versionName,
            currentVersionCode = installed.versionCode,
            latestVersionName = resolvedRemote.versionName,
            latestVersionCode = resolvedRemote.versionCode
        )

        UpdateCheckResult(
            installed = installed,
            latest = resolvedRemote,
            updateAvailable = available
        )
    }

    private fun fetchLatestFromGitHub(): RemoteReleaseInfo? {
        val json = httpGetText(
            urlString = GITHUB_RELEASES_API_URL,
            acceptHeader = "application/vnd.github+json"
        ) ?: return null
        return parseGitHubLatestReleaseJson(json)
    }

    private fun fetchLatestFromFdroid(packageName: String): RemoteReleaseInfo? {
        val v2Text = httpGetText(FDROID_INDEX_V2_URL, "application/json")
        if (!v2Text.isNullOrBlank()) {
            parseFdroidIndexV2Json(v2Text, packageName)?.let { return it }
        }

        val v1Text = httpGetText(FDROID_INDEX_V1_URL, "application/json")
        if (!v1Text.isNullOrBlank()) {
            parseFdroidIndexV1Json(v1Text, packageName)?.let { return it }
        }

        val htmlText = httpGetText(FDROID_REPO_PAGE_URL, "text/html")
        if (!htmlText.isNullOrBlank()) {
            parsePagesIndexHtmlFallback(htmlText)?.let { return it }
        }
        return null
    }

    private fun httpGetText(urlString: String, acceptHeader: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", "Cadence-Android")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "HTTP $code from $urlString")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Request failed for $urlString: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseGitHubLatestReleaseJson(jsonText: String): RemoteReleaseInfo? {
        return runCatching {
            val root = JSONObject(jsonText)
            if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
                return@runCatching null
            }
            val rawTag = root.optString("tag_name", "").trim()
            val cleanVer = normalizeVersionName(rawTag)
            if (cleanVer.isBlank()) return@runCatching null

            val htmlUrl = root.optString("html_url", "").trim()
                .ifBlank { GITHUB_RELEASES_PAGE_URL }
            val body = cleanReleaseNotes(root.optString("body", ""))

            RemoteReleaseInfo(
                versionName = cleanVer,
                versionCode = null,
                releaseNotes = body,
                downloadPageUrl = htmlUrl,
                checkedFromSource = InstallSource.GITHUB
            )
        }.getOrNull()
    }

    internal fun parseFdroidIndexV2Json(jsonText: String, packageName: String): RemoteReleaseInfo? {
        return runCatching {
            val root = JSONObject(jsonText)
            val packages = root.optJSONObject("packages") ?: return@runCatching null
            val pkgObj = packages.optJSONObject(packageName) ?: return@runCatching null
            val versions = pkgObj.optJSONObject("versions") ?: return@runCatching null

            var best: RemoteReleaseInfo? = null
            val keys = versions.keys()
            while (keys.hasNext()) {
                val entry = versions.optJSONObject(keys.next()) ?: continue
                val manifest = entry.optJSONObject("manifest") ?: continue
                val verName = normalizeVersionName(manifest.optString("versionName", ""))
                val verCode = manifest.optLong("versionCode", -1L).takeIf { it > 0L }
                if (verName.isBlank() && verCode == null) continue

                val whatsNewObj = entry.optJSONObject("whatsNew")
                val notes = whatsNewObj?.let { obj ->
                    obj.optString("en-US", "").ifBlank {
                        val firstKey = if (obj.keys().hasNext()) obj.keys().next() else null
                        if (firstKey != null) obj.optString(firstKey, "") else ""
                    }
                }.orEmpty()

                val candidate = RemoteReleaseInfo(
                    versionName = verName.ifBlank { verCode.toString() },
                    versionCode = verCode,
                    releaseNotes = cleanReleaseNotes(notes),
                    downloadPageUrl = FDROID_REPO_PAGE_URL,
                    checkedFromSource = InstallSource.FDROID
                )
                if (best == null || isRemoteNewer(candidate, best)) {
                    best = candidate
                }
            }
            best
        }.getOrNull()
    }

    internal fun parseFdroidIndexV1Json(jsonText: String, packageName: String): RemoteReleaseInfo? {
        return runCatching {
            val root = JSONObject(jsonText)
            val packages = root.optJSONObject("packages") ?: return@runCatching null
            val versions = packages.optJSONArray(packageName) ?: return@runCatching null

            var best: RemoteReleaseInfo? = null
            for (i in 0 until versions.length()) {
                val item = versions.optJSONObject(i) ?: continue
                val verName = normalizeVersionName(item.optString("versionName", ""))
                val verCode = item.optLong("versionCode", -1L).takeIf { it > 0L }
                if (verName.isBlank() && verCode == null) continue

                val candidate = RemoteReleaseInfo(
                    versionName = verName.ifBlank { verCode.toString() },
                    versionCode = verCode,
                    releaseNotes = "",
                    downloadPageUrl = FDROID_REPO_PAGE_URL,
                    checkedFromSource = InstallSource.FDROID
                )
                if (best == null || isRemoteNewer(candidate, best)) {
                    best = candidate
                }
            }
            best
        }.getOrNull()
    }

    internal fun parsePagesIndexHtmlFallback(htmlText: String): RemoteReleaseInfo? {
        val latestMatch = Regex("""Latest Release\s*•\s*v?(\d+(?:\.\d+)*)""").find(htmlText)
            ?: Regex("""Cadence-Mobile-v?(\d+(?:\.\d+)*)\.apk""").find(htmlText)
            ?: return null
        val version = normalizeVersionName(latestMatch.groupValues[1])
        if (version.isBlank()) return null
        return RemoteReleaseInfo(
            versionName = version,
            versionCode = null,
            releaseNotes = "",
            downloadPageUrl = FDROID_REPO_PAGE_URL,
            checkedFromSource = InstallSource.FDROID
        )
    }

    private fun isRemoteNewer(candidate: RemoteReleaseInfo, currentBest: RemoteReleaseInfo): Boolean {
        val nameCmp = compareVersionNames(candidate.versionName, currentBest.versionName)
        if (nameCmp != 0) return nameCmp > 0
        val candCode = candidate.versionCode ?: 0L
        val bestCode = currentBest.versionCode ?: 0L
        return candCode > bestCode
    }

    fun isUpdateAvailable(
        currentVersionName: String,
        currentVersionCode: Long,
        latestVersionName: String,
        latestVersionCode: Long?
    ): Boolean {
        val cmp = compareVersionNames(latestVersionName, currentVersionName)
        if (cmp > 0) return true
        if (cmp < 0) return false
        return latestVersionCode != null && latestVersionCode > currentVersionCode
    }

    fun compareVersionNames(versionA: String, versionB: String): Int {
        val partsA = parseVersionParts(versionA)
        val partsB = parseVersionParts(versionB)
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val a = partsA.getOrElse(i) { 0 }
            val b = partsB.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun normalizeVersionName(raw: String): String {
        val stripped = raw.trim().removePrefix("v").removePrefix("V")
        return if (stripped.any { it.isDigit() }) stripped else ""
    }

    private fun parseVersionParts(version: String): List<Int> {
        val core = normalizeVersionName(version)
            .substringBefore('-')
            .substringBefore('+')
        if (core.isBlank()) return emptyList()
        return core.split('.').map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun cleanReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("## ") &&
                    !line.startsWith("**Full Changelog**")
            }
            .map { line ->
                line.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
            }
            .filter { it.isNotBlank() }
        val joined = lines.take(4).joinToString("\n• ", prefix = if (lines.isNotEmpty()) "• " else "")
        return if (joined.length > 260) joined.take(257).trimEnd() + "…" else joined
    }

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure { error ->
            Log.w(TAG, "Unable to open update URL $url: ${error.message}")
        }
    }
}
