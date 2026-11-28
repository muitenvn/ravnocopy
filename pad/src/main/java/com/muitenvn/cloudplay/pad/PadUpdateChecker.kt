package com.muitenvn.cloudplay.pad

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class PadApkMirrorNode(
    val id: String,
    val name: String,
    val host: String,
    val isDirectGitHub: Boolean = false
) {
    fun buildDownloadUrl(directGitHubUrl: String): String {
        val cleanDirect = directGitHubUrl.trim()
        if (!cleanDirect.startsWith("https://github.com/")) return cleanDirect
        return when (id) {
            "gh_proxy_com" -> "https://gh-proxy.com/$cleanDirect"
            "ghproxy_net" -> "https://ghproxy.net/$cleanDirect"
            "gh_llkk_cc" -> "https://gh.llkk.cc/$cleanDirect"
            "ghproxy_cc" -> "https://ghproxy.cc/$cleanDirect"
            "gh_ddlc_top" -> "https://gh.ddlc.top/$cleanDirect"
            "ghfast_top" -> "https://ghfast.top/$cleanDirect"
            "mirror_ghproxy" -> "https://mirror.ghproxy.com/$cleanDirect"
            "kkgithub" -> cleanDirect.replaceFirst("https://github.com/", "https://kkgithub.com/")
            "gh_con_sh" -> "https://gh.con.sh/$cleanDirect"
            "github_moeyy" -> "https://github.moeyy.xyz/$cleanDirect"
            else -> cleanDirect
        }
    }
}

sealed class PadUpdateCheckState {
    data object Idle : PadUpdateCheckState()
    data object Checking : PadUpdateCheckState()
    data class UpToDate(val currentVersion: String) : PadUpdateCheckState()
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val releaseNotes: String,
        val apkDirectUrl: String,
        val officialSha256: String = "",
        val sha256AssetUrl: String = ""
    ) : PadUpdateCheckState()
    data class CheckFailed(val currentVersion: String) : PadUpdateCheckState()
}

sealed class PadApkDownloadState {
    data object Idle : PadApkDownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val activeMirrorName: String,
        val mirrorIndex: Int,
        val totalMirrors: Int
    ) : PadApkDownloadState()
    data object Verifying : PadApkDownloadState()
    data class VerifiedReadyToInstall(val apkFile: File) : PadApkDownloadState()
    data object DownloadError : PadApkDownloadState()
}

/**
 * Bộ kiểm tra & tự động tải bản cập nhật cho Ravno Pad:
 * - Tự kiểm tra mỗi lần mở/vào lại ứng dụng (không chờ 24 giờ, dùng ETag + chống gọi lặp 15 giây).
 * - Chỉ chọn tệp APK của Pad (chấp nhận cả CloudPlay-Pad-v*.apk lẫn Ravno-Pad-v*.apk).
 * - Tự động tải qua chuỗi máy chủ phụ + GitHub gốc, kiểm tra ZIP magic, tên gói và mã SHA-256 trước khi mở trình cài đặt.
 */
object PadUpdateChecker {
    private const val PREFS_NAME = "cloudplay_pad_update_checker_store"
    private const val KEY_CACHED_LATEST_VER = "cached_latest_version"
    private const val KEY_CACHED_RELEASE_URL = "cached_release_url"
    private const val KEY_CACHED_RELEASE_NOTES = "cached_release_notes"
    private const val KEY_CACHED_APK_DIRECT_URL = "cached_pad_apk_direct_url"
    private const val KEY_CACHED_OFFICIAL_SHA256 = "cached_pad_official_sha256"
    private const val KEY_CACHED_SHA256_ASSET_URL = "cached_pad_sha256_asset_url"
    private const val KEY_CACHED_ETAG = "cached_github_api_etag"
    private const val KEY_LAST_WORKING_MIRROR_ID = "last_working_mirror_id"
    private const val KEY_TERMS_ACCEPTED = "pad_user_accepted_terms_v1"

    private const val AUTO_CHECK_DEBOUNCE_MS = 15_000L
    private const val TIMEOUT_MS = 8_000
    private const val MIRROR_CONNECT_TIMEOUT_MS = 6_000
    private const val MIRROR_READ_TIMEOUT_MS = 12_000
    private const val GITHUB_DOWNLOAD_TIMEOUT_MS = 20_000
    private const val MAX_APK_SIZE_BYTES = 100L * 1024L * 1024L
    private const val MIN_VALID_APK_BYTES = 64L * 1024L
    private const val MAX_REDIRECT_HOPS = 8
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/muitenvn/Ravno-App/releases/latest"
    private const val LATEST_RELEASE_WEB_URL = "https://github.com/muitenvn/Ravno-App/releases/latest"
    const val FALLBACK_RELEASES_URL = "https://github.com/muitenvn/Ravno-App/releases"
    const val TERMS_WEB_URL = "https://muitenvn.github.io/Ravno-App/terms.html"

    private val FAST_MIRROR_NODES: List<PadApkMirrorNode> = listOf(
        PadApkMirrorNode(id = "gh_proxy_com", name = "gh-proxy.com", host = "gh-proxy.com"),
        PadApkMirrorNode(id = "ghproxy_net", name = "ghproxy.net", host = "ghproxy.net"),
        PadApkMirrorNode(id = "gh_llkk_cc", name = "gh.llkk.cc", host = "gh.llkk.cc"),
        PadApkMirrorNode(id = "ghproxy_cc", name = "ghproxy.cc", host = "ghproxy.cc"),
        PadApkMirrorNode(id = "gh_ddlc_top", name = "gh.ddlc.top", host = "gh.ddlc.top"),
        PadApkMirrorNode(id = "ghfast_top", name = "ghfast.top", host = "ghfast.top"),
        PadApkMirrorNode(id = "mirror_ghproxy", name = "mirror.ghproxy.com", host = "mirror.ghproxy.com"),
        PadApkMirrorNode(id = "kkgithub", name = "kkgithub.com", host = "kkgithub.com"),
        PadApkMirrorNode(id = "gh_con_sh", name = "gh.con.sh", host = "gh.con.sh"),
        PadApkMirrorNode(id = "github_moeyy", name = "github.moeyy.xyz", host = "github.moeyy.xyz"),
        PadApkMirrorNode(id = "github_direct", name = "GitHub Direct", host = "github.com", isDirectGitHub = true)
    )

    private val ALLOWED_MIRROR_HOSTS: Set<String> = FAST_MIRROR_NODES
        .filter { !it.isDirectGitHub }
        .map { it.host.lowercase() }
        .toSet()

    private val ALLOWED_CDN_REDIRECT_SUFFIXES: List<String> = listOf(
        ".githubusercontent.com",
        ".amazonaws.com",
        ".r2.cloudflarestorage.com",
        ".cloudflare.com",
        ".workers.dev",
        ".pages.dev",
        ".fastly.net",
        ".akamaized.net",
        ".jsdelivr.net",
        ".gh-proxy.com",
        ".ghproxy.net",
        ".ghproxy.com",
        ".ghproxy.cc",
        ".llkk.cc",
        ".ddlc.top",
        ".ghfast.top",
        ".kkgithub.com",
        ".con.sh",
        ".moeyy.xyz"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isChecking = AtomicBoolean(false)

    @Volatile
    private var lastCheckEpochMs: Long = 0L

    @Volatile
    private var dismissedVersionInSession: String = ""

    @Volatile
    private var skipCurrentMirrorRequested = false

    @Volatile
    private var activeDownloadConnection: HttpURLConnection? = null

    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<PadUpdateCheckState>(PadUpdateCheckState.Idle)
    val state: StateFlow<PadUpdateCheckState> = _state.asStateFlow()

    private val _downloadState = MutableStateFlow<PadApkDownloadState>(PadApkDownloadState.Idle)
    val downloadState: StateFlow<PadApkDownloadState> = _downloadState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _termsAccepted = MutableStateFlow(false)
    val termsAccepted: StateFlow<Boolean> = _termsAccepted.asStateFlow()

    fun initTermsState(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _termsAccepted.value = sp.getBoolean(KEY_TERMS_ACCEPTED, false)
    }

    fun setTermsAccepted(context: Context, accepted: Boolean) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_TERMS_ACCEPTED, accepted).apply()
        _termsAccepted.value = accepted
    }

    fun getCurrentVersionName(context: Context): String = try {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        normalizeVersionString(pkgInfo.versionName ?: "1.3.0")
    } catch (_: Exception) {
        "1.3.0"
    }

    fun normalizeVersionString(raw: String): String = raw.trim().removePrefix("v").removePrefix("V").trim()

    fun isNewerVersion(current: String, latest: String): Boolean {
        val cur = parseNumericParts(current)
        val lat = parseNumericParts(latest)
        if (lat.isEmpty()) return false
        val maxLen = maxOf(cur.size, lat.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val l = lat.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun parseNumericParts(version: String): List<Int> {
        val clean = normalizeVersionString(version)
        if (clean.isBlank()) return emptyList()
        return clean.split(".").mapNotNull { seg ->
            seg.takeWhile { it.isDigit() }.toIntOrNull()
        }
    }

    fun openUpdateDialog() {
        if (_state.value is PadUpdateCheckState.UpdateAvailable) {
            _showUpdateDialog.value = true
        }
    }

    fun dismissUpdateDialog() {
        val available = _state.value as? PadUpdateCheckState.UpdateAvailable
        if (available != null) {
            dismissedVersionInSession = available.latestVersion
        }
        _showUpdateDialog.value = false
    }

    /**
     * Tự động kiểm tra cập nhật mỗi lần mở / vào lại Ravno Pad (không chờ 24 giờ).
     * Khi phát hiện bản mới hơn, tự động tải APK Pad và kiểm tra SHA-256 ngay.
     */
    fun checkOnAppEntry(context: Context, autoDownload: Boolean = true) {
        if (!_termsAccepted.value) return
        if (_downloadState.value is PadApkDownloadState.Downloading || _downloadState.value is PadApkDownloadState.Verifying) return

        val now = System.currentTimeMillis()
        if (now - lastCheckEpochMs in 1..AUTO_CHECK_DEBOUNCE_MS) return

        val appContext = context.applicationContext
        if (!isChecking.compareAndSet(false, true)) return
        scope.launch {
            try {
                performCheck(appContext, isManual = false, autoDownload = autoDownload)
            } finally {
                isChecking.set(false)
            }
        }
    }

    fun checkNowManual(context: Context, autoDownload: Boolean = true) {
        if (!isChecking.compareAndSet(false, true)) return
        dismissedVersionInSession = ""
        _state.value = PadUpdateCheckState.Checking
        val appContext = context.applicationContext
        scope.launch {
            try {
                performCheck(appContext, isManual = true, autoDownload = autoDownload)
            } finally {
                isChecking.set(false)
            }
        }
    }

    private fun performCheck(context: Context, isManual: Boolean, autoDownload: Boolean) {
        val currentVersion = getCurrentVersionName(context)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedEtag = sp.getString(KEY_CACHED_ETAG, "") ?: ""
        val cachedLatest = sp.getString(KEY_CACHED_LATEST_VER, "") ?: ""

        var conn: HttpURLConnection? = null
        try {
            conn = openVerifiedDirectConnection(
                initialUrlString = LATEST_RELEASE_API_URL,
                allowMirrors = false,
                timeoutMs = TIMEOUT_MS,
                acceptHeader = "application/vnd.github+json",
                ifNoneMatchEtag = if (cachedLatest.isNotBlank()) cachedEtag else ""
            )
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && cachedLatest.isNotBlank()) {
                lastCheckEpochMs = System.currentTimeMillis()
                applyCachedVersion(context, currentVersion, cachedLatest, isManual, autoDownload)
                return
            }
            if (code !in 200..299) {
                val redirectVer = fetchLatestVersionViaWebRedirect().ifBlank { cachedLatest }
                if (redirectVer.isNotBlank()) {
                    lastCheckEpochMs = System.currentTimeMillis()
                    applyCachedVersion(context, currentVersion, redirectVer, isManual, autoDownload)
                } else if (isManual) {
                    _state.value = PadUpdateCheckState.CheckFailed(currentVersion)
                }
                return
            }

            val newEtag = conn.getHeaderField("ETag")?.trim().orEmpty()
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val json = JSONObject(body)
            val latestVersion = normalizeVersionString(json.optString("tag_name", ""))
            if (latestVersion.isBlank() || parseNumericParts(latestVersion).isEmpty()) {
                val redirectVer = fetchLatestVersionViaWebRedirect().ifBlank { cachedLatest }
                if (redirectVer.isNotBlank()) {
                    lastCheckEpochMs = System.currentTimeMillis()
                    applyCachedVersion(context, currentVersion, redirectVer, isManual, autoDownload)
                } else if (isManual) {
                    _state.value = PadUpdateCheckState.CheckFailed(currentVersion)
                }
                return
            }

            val rawHtmlUrl = json.optString("html_url", "").trim()
            val releaseUrl = if (rawHtmlUrl.startsWith("https://github.com/")) rawHtmlUrl else FALLBACK_RELEASES_URL

            var padApkUrl = ""
            var padApkName = ""
            var officialSha256 = ""
            var shaAssetUrl = ""

            val assetsArr = json.optJSONArray("assets")
            if (assetsArr != null) {
                for (i in 0 until assetsArr.length()) {
                    val assetObj = assetsArr.optJSONObject(i) ?: continue
                    val name = assetObj.optString("name", "").trim()
                    val dlUrl = assetObj.optString("browser_download_url", "").trim()
                    val digest = assetObj.optString("digest", "").trim()
                    if (isPadApkAssetName(name) && name.endsWith(".apk", ignoreCase = true) && dlUrl.startsWith("https://github.com/")) {
                        if (name.contains("Ravno-Pad", ignoreCase = true) || name.contains("CloudPlay-Pad", ignoreCase = true) || padApkUrl.isBlank()) {
                            padApkUrl = dlUrl
                            padApkName = name
                            val extracted = extractDigestSha256(digest)
                            if (extracted.isNotBlank()) officialSha256 = extracted
                        }
                    }
                }
                for (i in 0 until assetsArr.length()) {
                    val assetObj = assetsArr.optJSONObject(i) ?: continue
                    val name = assetObj.optString("name", "").trim()
                    val dlUrl = assetObj.optString("browser_download_url", "").trim()
                    if (isPadApkAssetName(name) && name.endsWith(".sha256", ignoreCase = true) && dlUrl.startsWith("https://github.com/")) {
                        if (padApkName.isNotBlank() && name.equals("$padApkName.sha256", ignoreCase = true)) {
                            shaAssetUrl = dlUrl
                            break
                        } else if (shaAssetUrl.isBlank()) {
                            shaAssetUrl = dlUrl
                        }
                    }
                }
            }

            if (padApkUrl.isBlank()) {
                padApkUrl = "https://github.com/muitenvn/Ravno-App/releases/download/v$latestVersion/CloudPlay-Pad-v$latestVersion.apk"
            }
            if (shaAssetUrl.isBlank()) {
                shaAssetUrl = "$padApkUrl.sha256"
            }

            val newerAvailable = isNewerVersion(currentVersion, latestVersion)
            if (newerAvailable && !isValidSha256Hex(officialSha256)) {
                officialSha256 = fetchSha256FromGitHubAsset(shaAssetUrl)
            }
            val cleanNotes = sanitizeReleaseNotes(json.optString("body", "").trim())

            sp.edit()
                .putString(KEY_CACHED_LATEST_VER, latestVersion)
                .putString(KEY_CACHED_RELEASE_URL, releaseUrl)
                .putString(KEY_CACHED_RELEASE_NOTES, cleanNotes)
                .putString(KEY_CACHED_APK_DIRECT_URL, padApkUrl)
                .putString(KEY_CACHED_OFFICIAL_SHA256, officialSha256)
                .putString(KEY_CACHED_SHA256_ASSET_URL, shaAssetUrl)
                .apply { if (newEtag.isNotBlank()) putString(KEY_CACHED_ETAG, newEtag) }
                .apply()

            lastCheckEpochMs = System.currentTimeMillis()
            if (newerAvailable) {
                val info = PadUpdateCheckState.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseUrl = releaseUrl,
                    releaseNotes = cleanNotes,
                    apkDirectUrl = padApkUrl,
                    officialSha256 = officialSha256,
                    sha256AssetUrl = shaAssetUrl
                )
                _state.value = info
                val shouldOpen = isManual || !dismissedVersionInSession.equals(latestVersion, ignoreCase = true)
                if (shouldOpen) {
                    _showUpdateDialog.value = true
                }
                if (autoDownload && shouldOpen) {
                    startVerifiedApkDownload(context, info)
                }
            } else {
                _state.value = PadUpdateCheckState.UpToDate(currentVersion)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            val redirectVer = fetchLatestVersionViaWebRedirect().ifBlank { cachedLatest }
            if (redirectVer.isNotBlank()) {
                lastCheckEpochMs = System.currentTimeMillis()
                applyCachedVersion(context, currentVersion, redirectVer, isManual, autoDownload)
            } else if (isManual) {
                _state.value = PadUpdateCheckState.CheckFailed(currentVersion)
            }
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun applyCachedVersion(
        context: Context,
        currentVersion: String,
        latestVersion: String,
        isManual: Boolean,
        autoDownload: Boolean
    ) {
        val cleanLatest = normalizeVersionString(latestVersion)
        if (!isNewerVersion(currentVersion, cleanLatest)) {
            _state.value = PadUpdateCheckState.UpToDate(currentVersion)
            return
        }
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val same = (sp.getString(KEY_CACHED_LATEST_VER, "") ?: "").equals(cleanLatest, ignoreCase = true)
        val releaseUrl = if (same) {
            (sp.getString(KEY_CACHED_RELEASE_URL, FALLBACK_RELEASES_URL) ?: FALLBACK_RELEASES_URL)
                .replace("CloudPlay-App", "Ravno-App")
        } else {
            "https://github.com/muitenvn/Ravno-App/releases/tag/v$cleanLatest"
        }
        val releaseNotes = if (same) sp.getString(KEY_CACHED_RELEASE_NOTES, "") ?: "" else ""
        val apkUrl = if (same) {
            (sp.getString(KEY_CACHED_APK_DIRECT_URL, "") ?: "")
                .replace("CloudPlay-App", "Ravno-App")
                .ifBlank { "https://github.com/muitenvn/Ravno-App/releases/download/v$cleanLatest/CloudPlay-Pad-v$cleanLatest.apk" }
        } else {
            "https://github.com/muitenvn/Ravno-App/releases/download/v$cleanLatest/CloudPlay-Pad-v$cleanLatest.apk"
        }
        val sha = if (same) sp.getString(KEY_CACHED_OFFICIAL_SHA256, "") ?: "" else ""
        val shaAsset = if (same) {
            (sp.getString(KEY_CACHED_SHA256_ASSET_URL, "") ?: "").ifBlank { "$apkUrl.sha256" }
        } else {
            "$apkUrl.sha256"
        }

        val info = PadUpdateCheckState.UpdateAvailable(
            currentVersion = currentVersion,
            latestVersion = cleanLatest,
            releaseUrl = releaseUrl,
            releaseNotes = releaseNotes,
            apkDirectUrl = apkUrl,
            officialSha256 = sha,
            sha256AssetUrl = shaAsset
        )
        _state.value = info
        val shouldOpen = isManual || !dismissedVersionInSession.equals(cleanLatest, ignoreCase = true)
        if (shouldOpen) {
            _showUpdateDialog.value = true
        }
        if (autoDownload && shouldOpen) {
            startVerifiedApkDownload(context, info)
        }
    }

    fun startVerifiedApkDownload(context: Context, updateInfo: PadUpdateCheckState.UpdateAvailable) {
        if (_downloadState.value is PadApkDownloadState.Downloading || _downloadState.value is PadApkDownloadState.Verifying) return
        val appContext = context.applicationContext
        val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastWorkingMirrorId = sp.getString(KEY_LAST_WORKING_MIRROR_ID, "") ?: ""
        val updatesDir = File(appContext.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        val targetApkFile = File(updatesDir, "CloudPlay-Pad-v${updateInfo.latestVersion}.apk")

        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                // Nếu tệp APK của đúng phiên bản này đã được tải và khớp SHA-256 sẵn trong cache thì dùng lại luôn
                val preResolvedSha = updateInfo.officialSha256.takeIf { isValidSha256Hex(it) }
                    ?: fetchSha256FromGitHubAsset(updateInfo.sha256AssetUrl)
                if (targetApkFile.exists() && isValidDownloadedApkArchive(appContext, targetApkFile)) {
                    val alreadyOk = if (isValidSha256Hex(preResolvedSha)) {
                        isExactSha256Match(computeFileSha256(targetApkFile), preResolvedSha)
                    } else {
                        false
                    }
                    if (alreadyOk) {
                        _downloadState.value = PadApkDownloadState.VerifiedReadyToInstall(targetApkFile)
                        launchInstallerIfSafe(appContext, targetApkFile)
                        return@launch
                    }
                }

                val candidates = buildOrderedMirrorChain(updateInfo.apkDirectUrl, lastWorkingMirrorId)
                var officialSha = preResolvedSha
                var ok = false

                for ((idx, pair) in candidates.withIndex()) {
                    val (node, url) = pair
                    val connectTimeout = if (node.isDirectGitHub) GITHUB_DOWNLOAD_TIMEOUT_MS else MIRROR_CONNECT_TIMEOUT_MS
                    val readTimeout = if (node.isDirectGitHub) GITHUB_DOWNLOAD_TIMEOUT_MS else MIRROR_READ_TIMEOUT_MS
                    skipCurrentMirrorRequested = false
                    try {
                        _downloadState.value = PadApkDownloadState.Downloading(
                            progress = 0f,
                            downloadedBytes = 0L,
                            totalBytes = -1L,
                            activeMirrorName = node.name,
                            mirrorIndex = idx + 1,
                            totalMirrors = candidates.size
                        )
                        downloadStreamToFile(
                            downloadUrl = url,
                            destFile = targetApkFile,
                            connectTimeoutMs = connectTimeout,
                            readTimeoutMs = readTimeout,
                            activeMirrorName = node.name,
                            mirrorIndex = idx + 1,
                            totalMirrors = candidates.size
                        )

                        _downloadState.value = PadApkDownloadState.Verifying
                        if (!isValidDownloadedApkArchive(appContext, targetApkFile)) {
                            throw IllegalStateException("Invalid Ravno Pad APK package")
                        }
                        if (!isValidSha256Hex(officialSha) && updateInfo.sha256AssetUrl.isNotBlank()) {
                            officialSha = fetchSha256FromGitHubAsset(updateInfo.sha256AssetUrl)
                        }
                        if (isValidSha256Hex(officialSha)) {
                            val computed = computeFileSha256(targetApkFile)
                            if (!isExactSha256Match(computed, officialSha)) {
                                throw IllegalStateException("SHA-256 mismatch")
                            }
                        }

                        ok = true
                        if (!node.isDirectGitHub) {
                            sp.edit().putString(KEY_LAST_WORKING_MIRROR_ID, node.id).apply()
                        }
                        break
                    } catch (ce: CancellationException) {
                        targetApkFile.delete()
                        throw ce
                    } catch (_: Throwable) {
                        targetApkFile.delete()
                    }
                }

                if (!ok) {
                    _downloadState.value = PadApkDownloadState.DownloadError
                    return@launch
                }

                _downloadState.value = PadApkDownloadState.VerifiedReadyToInstall(targetApkFile)
                launchInstallerIfSafe(appContext, targetApkFile)
            } catch (ce: CancellationException) {
                targetApkFile.delete()
                _downloadState.value = PadApkDownloadState.Idle
                throw ce
            } catch (_: Throwable) {
                targetApkFile.delete()
                _downloadState.value = PadApkDownloadState.DownloadError
            } finally {
                activeDownloadConnection = null
            }
        }
    }

    fun skipToNextMirror() {
        if (_downloadState.value is PadApkDownloadState.Downloading) {
            skipCurrentMirrorRequested = true
            try { activeDownloadConnection?.disconnect() } catch (_: Throwable) {}
        }
    }

    fun cancelDownload() {
        skipCurrentMirrorRequested = false
        try { activeDownloadConnection?.disconnect() } catch (_: Throwable) {}
        activeDownloadConnection = null
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = PadApkDownloadState.Idle
    }

    private fun launchInstallerIfSafe(context: Context, apkFile: File) {
        // Không tự bật trình cài đặt khi người dùng đang cầm tay cầm chơi game
        if (PadNetworkClient.state.value.route == PadScreenRoute.GAMEPAD) return
        launchApkInstaller(context, apkFile)
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        if (!apkFile.exists()) return
        try {
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private fun isPadApkAssetName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("ravno-pad") ||
            lower.contains("cloudplay-pad") ||
            lower.contains("-pad-") ||
            lower.startsWith("pad-")
    }

    private fun buildOrderedMirrorChain(
        directGitHubUrl: String,
        lastWorkingMirrorId: String
    ): List<Pair<PadApkMirrorNode, String>> {
        val ordered = mutableListOf<PadApkMirrorNode>()
        if (lastWorkingMirrorId.isNotBlank()) {
            FAST_MIRROR_NODES.firstOrNull { it.id == lastWorkingMirrorId && !it.isDirectGitHub }?.let { ordered.add(it) }
        }
        FAST_MIRROR_NODES.forEach { node ->
            if (ordered.none { it.id == node.id }) ordered.add(node)
        }
        return ordered.map { it to it.buildDownloadUrl(directGitHubUrl) }
    }

    private fun isAllowedHttpsUrl(url: URL, allowMirrors: Boolean): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host?.lowercase()?.trim() ?: return false
        if (host == "github.com" || host == "api.github.com") return true
        if (host.endsWith(".githubusercontent.com") || host.endsWith(".amazonaws.com")) return true
        if (allowMirrors) {
            if (ALLOWED_MIRROR_HOSTS.any { h -> host == h || host.endsWith(".$h") }) return true
            if (ALLOWED_CDN_REDIRECT_SUFFIXES.any { suffix -> host.endsWith(suffix) }) return true
        }
        return false
    }

    private fun isSafeRedirectHttpsTarget(url: URL, allowMirrors: Boolean): Boolean {
        if (isAllowedHttpsUrl(url, allowMirrors)) return true
        if (!allowMirrors) return false
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host?.lowercase()?.trim() ?: return false
        if (host.isBlank() || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return false
        return host.contains(".")
    }

    private fun openVerifiedDirectConnection(
        initialUrlString: String,
        allowMirrors: Boolean,
        timeoutMs: Int,
        readTimeoutMs: Int = timeoutMs,
        acceptHeader: String = "*/*",
        ifNoneMatchEtag: String = ""
    ): HttpURLConnection {
        var currentUrl = URL(initialUrlString)
        if (!isAllowedHttpsUrl(currentUrl, allowMirrors)) {
            throw SecurityException("Blocked disallowed URL")
        }
        var redirects = 0
        while (redirects <= MAX_REDIRECT_HOPS) {
            val conn = (currentUrl.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("User-Agent", if (allowMirrors) BROWSER_USER_AGENT else "RavnoPad-Android")
                if (ifNoneMatchEtag.isNotBlank() && redirects == 0) {
                    setRequestProperty("If-None-Match", ifNoneMatchEtag)
                }
            }
            activeDownloadConnection = conn
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")?.trim().orEmpty()
                conn.disconnect()
                if (loc.isBlank()) throw IllegalStateException("Missing redirect location")
                val nextUrl = URL(currentUrl, loc)
                if (!isSafeRedirectHttpsTarget(nextUrl, allowMirrors)) {
                    throw SecurityException("Blocked unsafe redirect")
                }
                currentUrl = nextUrl
                redirects++
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun fetchLatestVersionViaWebRedirect(): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_RELEASE_WEB_URL).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("User-Agent", "RavnoPad-Android")
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")?.trim().orEmpty()
            if (code in listOf(301, 302, 303, 307, 308) && loc.contains("/releases/tag/")) {
                val rawTag = loc.substringAfter("/releases/tag/").substringBefore("?").substringBefore("#").trim()
                val clean = normalizeVersionString(rawTag)
                if (parseNumericParts(clean).isNotEmpty()) clean else ""
            } else {
                ""
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun fetchSha256FromGitHubAsset(sha256AssetUrl: String): String {
        if (sha256AssetUrl.isBlank() || !sha256AssetUrl.startsWith("https://github.com/")) return ""
        var conn: HttpURLConnection? = null
        return try {
            conn = openVerifiedDirectConnection(sha256AssetUrl, allowMirrors = false, timeoutMs = TIMEOUT_MS)
            if (conn.responseCode !in 200..299) return ""
            val content = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val buf = CharArray(256)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }
            val hex = Regex("\\b([0-9a-fA-F]{64})\\b").find(content)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
            if (isValidSha256Hex(hex)) hex else ""
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            ""
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private suspend fun downloadStreamToFile(
        downloadUrl: String,
        destFile: File,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        activeMirrorName: String,
        mirrorIndex: Int,
        totalMirrors: Int
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = openVerifiedDirectConnection(
                initialUrlString = downloadUrl,
                allowMirrors = true,
                timeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs
            )
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val contentType = conn.contentType?.lowercase().orEmpty()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IllegalStateException("Unexpected $contentType")
            }
            val contentLength = conn.contentLengthLong
            if (contentLength > MAX_APK_SIZE_BYTES) throw SecurityException("APK too large")
            if (contentLength in 1 until MIN_VALID_APK_BYTES) throw IllegalStateException("APK too small")

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    val magic = ByteArray(4)
                    var magicFilled = 0
                    var totalRead = 0L
                    while (coroutineContext.isActive) {
                        if (skipCurrentMirrorRequested) throw IllegalStateException("Mirror skipped")
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue

                        if (magicFilled < 4) {
                            val toCopy = minOf(4 - magicFilled, read)
                            System.arraycopy(buffer, 0, magic, magicFilled, toCopy)
                            magicFilled += toCopy
                            if (magicFilled == 4 && !hasValidZipMagic(magic)) {
                                throw IllegalStateException("Invalid APK ZIP header")
                            }
                        }

                        totalRead += read
                        if (totalRead > MAX_APK_SIZE_BYTES) throw SecurityException("Stream too large")
                        output.write(buffer, 0, read)
                        val progress = if (contentLength > 0L) (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f) else -1f
                        _downloadState.value = PadApkDownloadState.Downloading(
                            progress = progress,
                            downloadedBytes = totalRead,
                            totalBytes = contentLength,
                            activeMirrorName = activeMirrorName,
                            mirrorIndex = mirrorIndex,
                            totalMirrors = totalMirrors
                        )
                    }
                    if (!coroutineContext.isActive) throw CancellationException("Cancelled")
                    if (skipCurrentMirrorRequested) throw IllegalStateException("Mirror skipped")
                    output.flush()
                    if (totalRead < MIN_VALID_APK_BYTES) throw IllegalStateException("Downloaded file too small")
                    if (contentLength > 0L && totalRead != contentLength) throw IllegalStateException("Truncated download")
                }
            }
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun hasValidZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    private fun isValidDownloadedApkArchive(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() < MIN_VALID_APK_BYTES) return false
        return try {
            val archiveInfo = context.packageManager?.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo != null) archiveInfo.packageName == context.packageName else true
        } catch (_: Throwable) {
            true
        }
    }

    private fun isValidSha256Hex(raw: String): Boolean {
        val clean = raw.trim().lowercase()
        return clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun extractDigestSha256(digestField: String): String {
        val trimmed = digestField.trim()
        if (trimmed.startsWith("sha256:", ignoreCase = true)) {
            val hex = trimmed.substringAfter(":").trim().lowercase()
            if (isValidSha256Hex(hex)) return hex
        }
        return ""
    }

    private fun computeFileSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format("%02x", it) }.lowercase()
    }

    private fun isExactSha256Match(computed: String, official: String): Boolean {
        val c = computed.trim().lowercase()
        val o = official.trim().lowercase()
        if (!isValidSha256Hex(c) || !isValidSha256Hex(o) || c.length != o.length) return false
        var diff = 0
        for (i in c.indices) {
            diff = diff or (c[i].code xor o[i].code)
        }
        return diff == 0
    }

    private fun sanitizeReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val mdLink = Regex("""\[([^\]]+)]\([^)]+\)""")
        return raw.lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("""^#{1,6}\s*"""), "")
                    .removePrefix(">")
                    .replace(mdLink, "$1")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
                    .trim()
            }
            .filter { it.isNotBlank() && !it.startsWith("---") && !it.startsWith("***") }
            .take(8)
            .joinToString("\n")
    }
}
