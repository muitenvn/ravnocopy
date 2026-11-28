package com.example.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class AppLogLevel(val shortCode: String) {
    INFO("I"),
    SUCCESS("S"),
    WARN("W"),
    ERROR("E");

    companion object {
        fun fromShortCode(code: String): AppLogLevel = when (code.uppercase(Locale.ROOT)) {
            "S" -> SUCCESS
            "W" -> WARN
            "E" -> ERROR
            else -> INFO
        }
    }
}

@Immutable
data class AppLogEntry(
    val id: Long,
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: AppLogLevel = AppLogLevel.INFO
)

@Immutable
data class StoredLogSession(
    val id: String,
    val versionName: String,
    val versionCode: Long,
    val startedAtLabel: String,
    val pid: Int,
    val file: File,
    val sizeBytes: Long,
    val isCurrentSession: Boolean
)

@Immutable
data class StoredLogVersionGroup(
    val versionName: String,
    val sessionCount: Int,
    val totalSizeBytes: Long,
    val latestStartedAtLabel: String,
    val sessions: List<StoredLogSession>
)

data class LogExportResult(
    val sessionCount: Int,
    val lineCount: Long,
    val byteCount: Long
)

/**
 * Persistent application logger backed by app-private internal storage (`filesDir`):
 * - Redacts sensitive tokens, cookies, credentials, emails, and auth URLs before RAM or disk storage.
 * - Keeps up to 1,500 recent lines (512 KB) in RAM for fast UI viewing, while persisting full unlimited
 *   per-launch session files grouped by app version under `filesDir/app_logs/v<version>/<session>.log`
 *   (safe archive cap: 32 MB / 120 oldest sessions) so file exports never truncate at 500 lines.
 * - Uses compact single-line format (`HH:mm:ss.SSS I/TAG message`) and deduplicates rapid spam within 1.5s.
 */
object AppLogger {

    const val MAX_LOGS = 1500
    const val MAX_PERF_LOGS = 100
    const val MAX_TOTAL_BYTES = 512 * 1024 // 512 KB RAM ring buffer
    const val PERSISTENT_LOG_FILE_NAME = "cloudplay_activity_log.txt"
    const val LOG_SESSIONS_DIR_NAME = "app_logs"
    const val MAX_PERSISTENT_FILE_BYTES = 512 * 1024 // 512 KB rolling mirror
    private const val ROTATED_KEEP_FILE_BYTES = 384 * 1024
    private const val MAX_ARCHIVE_TOTAL_BYTES = 32L * 1024L * 1024L // 32 MB archive safety cap
    private const val MAX_ARCHIVE_SESSIONS = 120
    private const val DUPLICATE_SUPPRESS_WINDOW_MS = 1500L

    const val STORAGE_LOCATION_DESC_VI = "Lưu theo phiên bản & phiên mở trong bộ nhớ nội bộ (filesDir/app_logs, giữ đầy đủ để xuất file .txt, tự xóa khi bấm Xóa log, không ghi cookie/token)"
    const val STORAGE_LOCATION_DESC_EN = "Persisted per app version & launch session in internal storage (filesDir/app_logs, full history for .txt export, deleted on Clear Log, no cookies/tokens)"

    private const val BATCH_FLUSH_INTERVAL_MS = 250L

    private class RawLogRecord(
        val id: Long,
        val timestamp: String,
        val tag: String,
        val rawMessage: String?,
        val lazyMessage: (() -> String)?,
        val level: AppLogLevel,
        val createdAtMs: Long
    )

    private val idGenerator = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fullDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val fileLock = Any()
    private val bufferLock = Any()

    private val pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<RawLogRecord>()
    private val ringBuffer = java.util.ArrayDeque<AppLogEntry>(MAX_LOGS + 16)
    private var ringBufferBytes: Int = 0
    private var ringBufferPerfCount: Int = 0
    private val pendingDiskEntries = java.util.ArrayList<AppLogEntry>(64)
    private val workerScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val delayedFlushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private var lastNonErrTag: String = ""
    private var lastNonErrLevel: AppLogLevel = AppLogLevel.INFO
    private var lastNonErrMessage: String = ""
    private var lastNonErrAtMs: Long = 0L

    @Volatile
    private var lastUiPublishAtMs: Long = 0L

    @Volatile
    private var lastDiskFlushAtMs: Long = 0L

    @Volatile
    private var unPublishedUiChanges: Boolean = false

    @Volatile
    private var baseFilesDir: File? = null

    @Volatile
    private var persistentLogFile: File? = null

    @Volatile
    private var activeSessionFile: File? = null

    @Volatile
    var currentAppVersionName: String = "unknown"
        private set

    @Volatile
    var currentAppVersionCode: Long = 0L
        private set

    @Volatile
    private var processInitialized = false

    private val diskExecutor: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "CloudPlay-AppLogger-IO").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }

    // Matches compact format: 13:16:00.526 I/VPN Check IP
    private val COMPACT_LINE_REGEX = Regex("^(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([ISWE])/([^\\s]+)\\s+(.*)$")
    // Matches legacy format: [13:16:00.526] [VPN] [INFO] ...
    private val LEGACY_LINE_REGEX = Regex("^\\[([^\\]]+)]\\s+\\[([^\\]]+)](?:\\s+\\[(INFO|SUCCESS|WARN|ERROR)])?\\s+(.*)$")
    private val SESSION_FILENAME_REGEX = Regex("^(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(?:_\\d{3})?_pid(\\d+)_c(\\d+)\\.log$")

    private val SENSITIVE_JSON_KEYS = setOf(
        "token", "access_token", "refresh_token", "id_token", "auth", "authorization",
        "cookie", "set-cookie", "cookies", "password", "passwd", "pwd", "secret",
        "client_secret", "apikey", "api_key", "email", "user_email", "session",
        "session_id", "sessionid", "credential", "credentials", "bearer"
    )

    private val BEARER_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*")
    private val HEADER_KV_REGEX = Regex(
        "(?i)\\b(cookie|set-cookie|authorization|x-api-key|api_key|apikey|access_token|refresh_token|id_token|token|password|passwd|pwd|secret|client_secret)\\s*([:=])\\s*(\"[^\"]*\"|'[^']*'|[^\\s,;\"'}]+)"
    )
    private val URL_USERINFO_REGEX = Regex("(?i)(https?://)([^/\\s:@]+:[^/\\s@]+@)")
    private val URL_QUERY_SECRET_REGEX = Regex(
        "(?i)([?&](?:token|access_token|refresh_token|id_token|code|key|api_key|apikey|sig|signature|password|passwd|secret|auth|cookie)=)[^&\\s#\"']+"
    )
    private val EMAIL_REGEX = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val JWT_REGEX = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")

    private val _logs = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logs: StateFlow<List<AppLogEntry>>
        get() {
            if (pendingQueue.isNotEmpty() || unPublishedUiChanges) {
                val isRealMainThread = android.os.Looper.getMainLooper()?.thread === Thread.currentThread() &&
                    !Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
                if (isRealMainThread) {
                    scheduleWorkerDrain(flushImmediately = true)
                } else {
                    processPendingLogsLocked(publishUiNow = true, flushDiskNow = false)
                }
            }
            return _logs.asStateFlow()
        }

    private fun entryByteCost(item: AppLogEntry): Int {
        return (item.timestamp.length + item.tag.length + item.message.length + 12) * 2
    }

    private fun formatEntryCompact(entry: AppLogEntry): String {
        val singleLineMsg = entry.message
            .replace("\r\n", " ⏎ ")
            .replace('\n', ' ')
        return "${entry.timestamp} ${entry.level.shortCode}/${entry.tag} $singleLineMsg"
    }

    private fun rebuildRingBufferFromListLocked(entries: List<AppLogEntry>) {
        ringBuffer.clear()
        ringBufferBytes = 0
        ringBufferPerfCount = 0
        val cappedPerf = capPerfEntries(entries).takeLast(MAX_LOGS)
        val trimmed = trimEntriesByBytes(cappedPerf)
        for (e in trimmed) {
            ringBuffer.addLast(e)
            ringBufferBytes += entryByteCost(e)
            if (e.tag == "PERF") ringBufferPerfCount++
        }
    }

    private fun appendToRingBufferLocked(entry: AppLogEntry) {
        if (entry.tag == "PERF") {
            while (ringBufferPerfCount >= MAX_PERF_LOGS) {
                val iter = ringBuffer.iterator()
                var removed = false
                while (iter.hasNext()) {
                    val old = iter.next()
                    if (old.tag == "PERF") {
                        iter.remove()
                        ringBufferBytes = (ringBufferBytes - entryByteCost(old)).coerceAtLeast(0)
                        ringBufferPerfCount = (ringBufferPerfCount - 1).coerceAtLeast(0)
                        removed = true
                        break
                    }
                }
                if (!removed) break
            }
        }

        while (ringBuffer.size >= MAX_LOGS && ringBuffer.isNotEmpty()) {
            val iter = ringBuffer.iterator()
            var removedEntry: AppLogEntry? = null
            while (iter.hasNext()) {
                val candidate = iter.next()
                if (candidate.tag != "PERF-INIT") {
                    iter.remove()
                    removedEntry = candidate
                    break
                }
            }
            val removed = removedEntry ?: ringBuffer.removeFirst()
            ringBufferBytes = (ringBufferBytes - entryByteCost(removed)).coerceAtLeast(0)
            if (removed.tag == "PERF") {
                ringBufferPerfCount = (ringBufferPerfCount - 1).coerceAtLeast(0)
            }
        }

        val cost = entryByteCost(entry)
        ringBuffer.addLast(entry)
        ringBufferBytes += cost
        if (entry.tag == "PERF") {
            ringBufferPerfCount++
        }

        while (ringBufferBytes > MAX_TOTAL_BYTES && ringBuffer.size > 1) {
            val iter = ringBuffer.iterator()
            var removedEntry: AppLogEntry? = null
            while (iter.hasNext()) {
                val candidate = iter.next()
                if (candidate.tag != "PERF-INIT") {
                    iter.remove()
                    removedEntry = candidate
                    break
                }
            }
            val removed = removedEntry ?: ringBuffer.removeFirst()
            ringBufferBytes = (ringBufferBytes - entryByteCost(removed)).coerceAtLeast(0)
            if (removed.tag == "PERF") {
                ringBufferPerfCount = (ringBufferPerfCount - 1).coerceAtLeast(0)
            }
        }
    }

    private fun nowString(): String {
        return synchronized(timeFormat) {
            timeFormat.format(Date())
        }
    }

    private fun nowFullDateTimeString(): String {
        return synchronized(fullDateTimeFormat) {
            fullDateTimeFormat.format(Date())
        }
    }

    private fun nowFileStampString(): String {
        return synchronized(fileStampFormat) {
            fileStampFormat.format(Date())
        }
    }

    private fun resolveAppVersion(context: Context): Pair<String, Long> {
        return try {
            val pm = context.packageManager
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val name = pi.versionName?.trim()?.removePrefix("v")?.removePrefix("V").orEmpty().ifBlank { "unknown" }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            name to code
        } catch (_: Throwable) {
            "unknown" to 0L
        }
    }

    private fun sanitizeVersionFolderName(versionName: String): String {
        val clean = versionName.trim().removePrefix("v").removePrefix("V")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "unknown" }
        return "v$clean"
    }

    private val asyncInitScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun init(context: Context) {
        if (processInitialized || !asyncInitScheduled.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        val filesDir = appCtx.filesDir
        val (verName, verCode) = resolveAppVersion(appCtx)
        currentAppVersionName = verName
        currentAppVersionCode = verCode
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        if (isRobolectric) {
            initWithDirectory(filesDir, appVersionName = verName, appVersionCode = verCode)
        } else {
            try {
                diskExecutor.execute {
                    initWithDirectory(filesDir, appVersionName = verName, appVersionCode = verCode)
                }
            } catch (_: Throwable) {
                initWithDirectory(filesDir, appVersionName = verName, appVersionCode = verCode)
            }
        }
    }

    fun initWithDirectory(
        filesDir: File,
        pidOverride: Int? = null,
        forceReinit: Boolean = false,
        appVersionName: String? = null,
        appVersionCode: Long? = null
    ) {
        if (processInitialized && !forceReinit) return
        processPendingLogsLocked(publishUiNow = true, flushDiskNow = false)
        val pid = pidOverride ?: runCatching { android.os.Process.myPid() }.getOrDefault(0)
        val resolvedVer = appVersionName?.trim()?.removePrefix("v")?.removePrefix("V")
            ?.takeIf { it.isNotBlank() } ?: currentAppVersionName
        val resolvedCode = appVersionCode ?: currentAppVersionCode
        currentAppVersionName = resolvedVer
        currentAppVersionCode = resolvedCode

        synchronized(fileLock) {
            if (processInitialized && !forceReinit) return
            try {
                if (!filesDir.exists()) {
                    filesDir.mkdirs()
                }
                baseFilesDir = filesDir
                val rollingFile = File(filesDir, PERSISTENT_LOG_FILE_NAME)
                persistentLogFile = rollingFile

                val versionDir = File(File(filesDir, LOG_SESSIONS_DIR_NAME), sanitizeVersionFolderName(resolvedVer))
                if (!versionDir.exists()) {
                    versionDir.mkdirs()
                }
                val sessionFile = File(versionDir, "${nowFileStampString()}_pid${pid}_c${resolvedCode}.log")
                activeSessionFile = sessionFile

                val loadedEntries = loadEntriesFromDiskLocked(rollingFile)
                var unsavedEarly: List<AppLogEntry> = emptyList()
                synchronized(bufferLock) {
                    val current = if (forceReinit) emptyList() else ringBuffer.toList()
                    if (forceReinit) {
                        pendingDiskEntries.clear()
                    }
                    rebuildRingBufferFromListLocked(loadedEntries + current)
                    val snapshot = ArrayList(ringBuffer)
                    _logs.value = snapshot
                    unPublishedUiChanges = false
                    lastUiPublishAtMs = System.currentTimeMillis()
                    if (!forceReinit) {
                        val maxLoadedId = loadedEntries.lastOrNull()?.id ?: 0L
                        unsavedEarly = snapshot.filter { it.id > maxLoadedId }
                        pendingDiskEntries.clear()
                    }
                }

                if (!forceReinit && unsavedEarly.isNotEmpty()) {
                    writeEntriesToFilesLocked(rollingFile, sessionFile, unsavedEarly)
                }
                pruneOldArchiveSessionsLocked(filesDir, keepFile = sessionFile)
            } catch (_: Throwable) {
            }
            processInitialized = true
        }

        val startTimestamp = nowFullDateTimeString()
        info("SYSTEM", "=== START v$resolvedVer ($resolvedCode) | $startTimestamp | PID $pid ===")
    }

    private fun pruneOldArchiveSessionsLocked(filesDir: File, keepFile: File?) {
        try {
            val sessionsRoot = File(filesDir, LOG_SESSIONS_DIR_NAME)
            if (!sessionsRoot.exists() || !sessionsRoot.isDirectory) return
            val allFiles = mutableListOf<File>()
            sessionsRoot.listFiles()?.forEach { verDir ->
                if (verDir.isDirectory) {
                    verDir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".log")) {
                            allFiles.add(f)
                        }
                    }
                }
            }
            if (allFiles.isEmpty()) return
            // Sort oldest first so we prune oldest sessions only when over safety cap
            allFiles.sortWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            var totalBytes = allFiles.sumOf { it.length() }
            var remainingCount = allFiles.size
            for (f in allFiles) {
                if (remainingCount <= MAX_ARCHIVE_SESSIONS && totalBytes <= MAX_ARCHIVE_TOTAL_BYTES) break
                if (keepFile != null && f.absolutePath == keepFile.absolutePath) continue
                val len = f.length()
                if (f.delete()) {
                    totalBytes = (totalBytes - len).coerceAtLeast(0L)
                    remainingCount--
                }
            }
            // Remove empty version folders
            sessionsRoot.listFiles()?.forEach { verDir ->
                if (verDir.isDirectory && verDir.listFiles().isNullOrEmpty()) {
                    verDir.delete()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun parseSinglePersistedLine(rawLine: String): AppLogEntry? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val compactMatch = COMPACT_LINE_REGEX.matchEntire(line)
        if (compactMatch != null) {
            val ts = compactMatch.groupValues[1]
            val level = AppLogLevel.fromShortCode(compactMatch.groupValues[2])
            val tag = compactMatch.groupValues[3].trim().uppercase(Locale.ROOT)
            val msg = compactMatch.groupValues[4].replace(" ⏎ ", "\n")
            return AppLogEntry(
                id = idGenerator.getAndIncrement(),
                timestamp = ts,
                tag = tag,
                message = msg,
                level = level
            )
        }
        val legacyMatch = LEGACY_LINE_REGEX.matchEntire(line)
        if (legacyMatch != null) {
            val ts = legacyMatch.groupValues[1]
            val tag = legacyMatch.groupValues[2].trim().uppercase(Locale.ROOT)
            val levelStr = legacyMatch.groupValues[3]
            val msg = legacyMatch.groupValues[4].replace(" ⏎ ", "\n")
            val level = runCatching {
                if (levelStr.isNotBlank()) AppLogLevel.valueOf(levelStr) else AppLogLevel.INFO
            }.getOrDefault(AppLogLevel.INFO)
            return AppLogEntry(
                id = idGenerator.getAndIncrement(),
                timestamp = ts,
                tag = tag,
                message = msg,
                level = level
            )
        }
        return AppLogEntry(
            id = idGenerator.getAndIncrement(),
            timestamp = nowString(),
            tag = "LOG",
            message = sanitizeMessage(line),
            level = AppLogLevel.INFO
        )
    }

    private fun loadEntriesFromDiskLocked(file: File): List<AppLogEntry> {
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val rawBytes = file.readBytes()
            val cappedBytes = if (rawBytes.size > MAX_PERSISTENT_FILE_BYTES) {
                keepNewestLinesBytes(rawBytes, ROTATED_KEEP_FILE_BYTES)
            } else {
                rawBytes
            }
            val content = String(cappedBytes, Charsets.UTF_8)
            val parsed = mutableListOf<AppLogEntry>()
            for (rawLine in content.lineSequence()) {
                parseSinglePersistedLine(rawLine)?.let { parsed.add(it) }
            }
            parsed.takeLast(MAX_LOGS)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeEntriesToFilesLocked(rollingFile: File?, sessionFile: File?, entries: List<AppLogEntry>) {
        if (entries.isEmpty()) return
        synchronized(fileLock) {
            try {
                val sb = StringBuilder(entries.size * 72)
                for (entry in entries) {
                    sb.append(formatEntryCompact(entry)).append('\n')
                }
                val lineBytes = sb.toString().toByteArray(Charsets.UTF_8)

                // 1. Append full unlimited history to the active version/session log file
                if (sessionFile != null && activeSessionFile == sessionFile) {
                    sessionFile.parentFile?.let { parent ->
                        if (!parent.exists()) parent.mkdirs()
                    }
                    FileOutputStream(sessionFile, true).use { fos ->
                        fos.write(lineBytes)
                    }
                }

                // 2. Append to rolling mirror file for quick RAM preload across restarts
                if (rollingFile != null && persistentLogFile == rollingFile) {
                    FileOutputStream(rollingFile, true).use { fos ->
                        fos.write(lineBytes)
                    }
                    if (rollingFile.length() > MAX_PERSISTENT_FILE_BYTES) {
                        val allBytes = rollingFile.readBytes()
                        val rotated = keepNewestLinesBytes(allBytes, ROTATED_KEEP_FILE_BYTES)
                        rollingFile.writeBytes(rotated)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun keepNewestLinesBytes(bytes: ByteArray, targetMaxBytes: Int): ByteArray {
        if (bytes.size <= targetMaxBytes) return bytes
        var startIdx = bytes.size - targetMaxBytes
        while (startIdx < bytes.size && bytes[startIdx] != '\n'.code.toByte()) {
            startIdx++
        }
        if (startIdx < bytes.size && bytes[startIdx] == '\n'.code.toByte()) {
            startIdx++
        }
        return if (startIdx < bytes.size) {
            bytes.copyOfRange(startIdx, bytes.size)
        } else {
            bytes.copyOfRange(bytes.size - targetMaxBytes, bytes.size)
        }
    }

    private fun trimEntriesByBytes(entries: List<AppLogEntry>): List<AppLogEntry> {
        val trimmedByBytes = mutableListOf<AppLogEntry>()
        var runningBytes = 0
        for (i in entries.indices.reversed()) {
            val item = entries[i]
            val itemBytes = entryByteCost(item)
            if (runningBytes + itemBytes > MAX_TOTAL_BYTES && trimmedByBytes.isNotEmpty()) {
                break
            }
            runningBytes += itemBytes
            trimmedByBytes.add(item)
        }
        return trimmedByBytes.asReversed()
    }

    private fun capPerfEntries(entries: List<AppLogEntry>): List<AppLogEntry> {
        val perfCount = entries.count { it.tag == "PERF" }
        if (perfCount <= MAX_PERF_LOGS) return entries
        var toRemove = perfCount - MAX_PERF_LOGS
        val result = ArrayList<AppLogEntry>(entries.size - toRemove)
        for (entry in entries) {
            if (entry.tag == "PERF" && toRemove > 0) {
                toRemove--
            } else {
                result.add(entry)
            }
        }
        return result
    }

    private fun scheduleWorkerDrain(flushImmediately: Boolean) {
        if (flushImmediately) {
            try {
                diskExecutor.execute {
                    processPendingLogsLocked(publishUiNow = true, flushDiskNow = true)
                }
            } catch (_: Throwable) {
            }
            return
        }
        if (workerScheduled.compareAndSet(false, true)) {
            try {
                diskExecutor.execute {
                    try {
                        processPendingLogsLocked(publishUiNow = false, flushDiskNow = false)
                    } finally {
                        workerScheduled.set(false)
                        if (pendingQueue.isNotEmpty()) {
                            scheduleWorkerDrain(flushImmediately = false)
                        }
                    }
                }
            } catch (_: Throwable) {
                workerScheduled.set(false)
            }
        }
    }

    private fun scheduleDelayedBatchFlushIfNeeded() {
        if (delayedFlushScheduled.compareAndSet(false, true)) {
            try {
                diskExecutor.schedule(
                    {
                        try {
                            processPendingLogsLocked(publishUiNow = true, flushDiskNow = true)
                        } finally {
                            delayedFlushScheduled.set(false)
                        }
                    },
                    BATCH_FLUSH_INTERVAL_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            } catch (_: Throwable) {
                delayedFlushScheduled.set(false)
            }
        }
    }

    private fun processPendingLogsLocked(publishUiNow: Boolean, flushDiskNow: Boolean) {
        var diskBatchToWrite: List<AppLogEntry>? = null
        var targetRollingFile: File? = null
        var targetSessionFile: File? = null
        var needsDelayedFlush = false

        synchronized(bufferLock) {
            var hasError = false
            var drainedAny = false
            while (true) {
                val record = pendingQueue.poll() ?: break
                drainedAny = true
                val rawMsg = record.rawMessage
                    ?: runCatching { record.lazyMessage?.invoke().orEmpty() }.getOrDefault("")
                val safeMessage = sanitizeMessage(rawMsg)

                // Deduplicate identical consecutive non-ERROR spam within 1.5s
                if (record.level != AppLogLevel.ERROR &&
                    record.tag == lastNonErrTag &&
                    record.level == lastNonErrLevel &&
                    safeMessage == lastNonErrMessage &&
                    (record.createdAtMs - lastNonErrAtMs) in 0L..DUPLICATE_SUPPRESS_WINDOW_MS
                ) {
                    continue
                }
                if (record.level != AppLogLevel.ERROR) {
                    lastNonErrTag = record.tag
                    lastNonErrLevel = record.level
                    lastNonErrMessage = safeMessage
                    lastNonErrAtMs = record.createdAtMs
                }

                val entry = AppLogEntry(
                    id = record.id,
                    timestamp = record.timestamp,
                    tag = record.tag,
                    message = safeMessage,
                    level = record.level
                )
                appendToRingBufferLocked(entry)
                unPublishedUiChanges = true
                if (persistentLogFile != null || activeSessionFile != null) {
                    pendingDiskEntries.add(entry)
                }
                if (record.level == AppLogLevel.ERROR) {
                    hasError = true
                }
                try {
                    when (record.level) {
                        AppLogLevel.ERROR, AppLogLevel.WARN -> Log.w("Ravno-${record.tag}", safeMessage)
                        else -> Log.d("Ravno-${record.tag}", safeMessage)
                    }
                } catch (_: Exception) {
                }
            }

            val nowMs = System.currentTimeMillis()
            val shouldPublishUi = unPublishedUiChanges &&
                (publishUiNow || hasError || (nowMs - lastUiPublishAtMs) >= BATCH_FLUSH_INTERVAL_MS)
            if (shouldPublishUi) {
                _logs.value = ArrayList(ringBuffer)
                unPublishedUiChanges = false
                lastUiPublishAtMs = nowMs
            }

            val shouldFlushDisk = pendingDiskEntries.isNotEmpty() &&
                (flushDiskNow || hasError || (nowMs - lastDiskFlushAtMs) >= BATCH_FLUSH_INTERVAL_MS)
            if (shouldFlushDisk) {
                targetRollingFile = persistentLogFile
                targetSessionFile = activeSessionFile
                if (targetRollingFile != null || targetSessionFile != null) {
                    diskBatchToWrite = ArrayList(pendingDiskEntries)
                }
                pendingDiskEntries.clear()
                lastDiskFlushAtMs = nowMs
            }

            if (unPublishedUiChanges || pendingDiskEntries.isNotEmpty()) {
                needsDelayedFlush = true
            }
            if (!drainedAny && !shouldPublishUi && !shouldFlushDisk) {
                return
            }
        }

        if (diskBatchToWrite != null && (targetRollingFile != null || targetSessionFile != null)) {
            writeEntriesToFilesLocked(targetRollingFile, targetSessionFile, diskBatchToWrite!!)
        }
        if (needsDelayedFlush) {
            scheduleDelayedBatchFlushIfNeeded()
        }
    }

    fun flushDiskWritesForTest() {
        processPendingLogsLocked(publishUiNow = true, flushDiskNow = true)
        try {
            diskExecutor.submit {
                processPendingLogsLocked(publishUiNow = true, flushDiskNow = true)
            }.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /**
     * Flush pending RAM entries to disk synchronously (call on IO thread before exporting files).
     */
    fun flushToDiskSync() {
        processPendingLogsLocked(publishUiNow = true, flushDiskNow = true)
    }

    /**
     * Returns all persisted launch sessions sorted newest-first.
     */
    fun listStoredSessions(context: Context? = null): List<StoredLogSession> {
        flushToDiskSync()
        val filesDir = baseFilesDir ?: context?.applicationContext?.filesDir ?: return emptyList()
        val activePath = activeSessionFile?.absolutePath
        val result = mutableListOf<StoredLogSession>()

        synchronized(fileLock) {
            val sessionsRoot = File(filesDir, LOG_SESSIONS_DIR_NAME)
            if (sessionsRoot.exists() && sessionsRoot.isDirectory) {
                sessionsRoot.listFiles()?.forEach { verDir ->
                    if (!verDir.isDirectory) return@forEach
                    val versionName = verDir.name.removePrefix("v").ifBlank { "unknown" }
                    verDir.listFiles()?.forEach { f ->
                        if (!f.isFile || !f.name.endsWith(".log") || f.length() <= 0L) return@forEach
                        val match = SESSION_FILENAME_REGEX.matchEntire(f.name)
                        val startedLabel = if (match != null) {
                            "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]} " +
                                "${match.groupValues[4]}:${match.groupValues[5]}:${match.groupValues[6]}"
                        } else {
                            synchronized(fullDateTimeFormat) { fullDateTimeFormat.format(Date(f.lastModified())) }
                        }
                        val pid = match?.groupValues?.getOrNull(7)?.toIntOrNull() ?: 0
                        val verCode = match?.groupValues?.getOrNull(8)?.toLongOrNull() ?: 0L
                        result.add(
                            StoredLogSession(
                                id = "${verDir.name}/${f.name}",
                                versionName = versionName,
                                versionCode = verCode,
                                startedAtLabel = startedLabel,
                                pid = pid,
                                file = f,
                                sizeBytes = f.length(),
                                isCurrentSession = (activePath != null && f.absolutePath == activePath)
                            )
                        )
                    }
                }
            }

            // Fallback to legacy single file if no versioned sessions exist yet
            if (result.isEmpty()) {
                val legacyFile = File(filesDir, PERSISTENT_LOG_FILE_NAME)
                if (legacyFile.exists() && legacyFile.isFile && legacyFile.length() > 0L) {
                    val label = synchronized(fullDateTimeFormat) {
                        fullDateTimeFormat.format(Date(legacyFile.lastModified()))
                    }
                    result.add(
                        StoredLogSession(
                            id = PERSISTENT_LOG_FILE_NAME,
                            versionName = currentAppVersionName,
                            versionCode = currentAppVersionCode,
                            startedAtLabel = label,
                            pid = 0,
                            file = legacyFile,
                            sizeBytes = legacyFile.length(),
                            isCurrentSession = true
                        )
                    )
                }
            }
        }

        return result.sortedWith(
            compareByDescending<StoredLogSession> { it.isCurrentSession }
                .thenByDescending { it.startedAtLabel }
                .thenByDescending { it.file.name }
        )
    }

    /**
     * Groups stored launch sessions by app version (newest version / latest activity first).
     */
    fun listStoredVersions(context: Context? = null): List<StoredLogVersionGroup> {
        val sessions = listStoredSessions(context)
        if (sessions.isEmpty()) return emptyList()
        return sessions.groupBy { it.versionName }.map { (ver, list) ->
            StoredLogVersionGroup(
                versionName = ver,
                sessionCount = list.size,
                totalSizeBytes = list.sumOf { it.sizeBytes },
                latestStartedAtLabel = list.first().startedAtLabel,
                sessions = list
            )
        }.sortedWith(
            compareByDescending<StoredLogVersionGroup> { group -> group.sessions.any { it.isCurrentSession } }
                .thenByDescending { it.latestStartedAtLabel }
        )
    }

    fun writeAllLogsToStream(
        output: OutputStream,
        context: Context? = null,
        includePerfSummary: Boolean = true
    ): LogExportResult {
        val sessions = listStoredSessions(context).sortedWith(
            compareBy<StoredLogSession> { it.startedAtLabel }.thenBy { it.file.name }
        )
        return writeSessionsToStream(
            scopeTitle = "ALL (${sessions.size} sessions)",
            sessions = sessions,
            output = output,
            includePerfSummary = includePerfSummary
        )
    }

    fun writeVersionLogsToStream(
        versionName: String,
        output: OutputStream,
        context: Context? = null,
        includePerfSummary: Boolean = true
    ): LogExportResult {
        val cleanVer = versionName.trim().removePrefix("v").removePrefix("V")
        val sessions = listStoredSessions(context)
            .filter { it.versionName.equals(cleanVer, ignoreCase = true) }
            .sortedWith(compareBy<StoredLogSession> { it.startedAtLabel }.thenBy { it.file.name })
        val includesCurrent = includePerfSummary && sessions.any { it.isCurrentSession }
        return writeSessionsToStream(
            scopeTitle = "VERSION v$cleanVer (${sessions.size} sessions)",
            sessions = sessions,
            output = output,
            includePerfSummary = includesCurrent
        )
    }

    fun writeSessionLogsToStream(
        session: StoredLogSession,
        output: OutputStream,
        includePerfSummary: Boolean = true
    ): LogExportResult {
        flushToDiskSync()
        return writeSessionsToStream(
            scopeTitle = "SESSION v${session.versionName} @ ${session.startedAtLabel} (PID ${session.pid})",
            sessions = listOf(session),
            output = output,
            includePerfSummary = includePerfSummary && session.isCurrentSession
        )
    }

    private fun writeSessionsToStream(
        scopeTitle: String,
        sessions: List<StoredLogSession>,
        output: OutputStream,
        includePerfSummary: Boolean
    ): LogExportResult {
        var totalLines = 0L
        var totalBytes = 0L
        val bos = BufferedOutputStream(output, 16 * 1024)
        val header = buildString {
            appendLine("# Ravno Activity Log Export")
            appendLine("# Scope: $scopeTitle")
            appendLine("# Current App: v$currentAppVersionName ($currentAppVersionCode)")
            appendLine("# Exported: ${nowFullDateTimeString()}")
            appendLine()
        }.toByteArray(Charsets.UTF_8)
        bos.write(header)
        totalBytes += header.size

        val buf = ByteArray(16 * 1024)
        synchronized(fileLock) {
            for (session in sessions) {
                val f = session.file
                if (!f.exists() || !f.isFile) continue
                val sectionHeader = "--- v${session.versionName} (${session.versionCode}) | ${session.startedAtLabel} | PID ${session.pid} ---\n"
                    .toByteArray(Charsets.UTF_8)
                bos.write(sectionHeader)
                totalBytes += sectionHeader.size
                FileInputStream(f).use { fis ->
                    while (true) {
                        val read = fis.read(buf)
                        if (read <= 0) break
                        bos.write(buf, 0, read)
                        totalBytes += read
                        for (i in 0 until read) {
                            if (buf[i] == '\n'.code.toByte()) totalLines++
                        }
                    }
                }
                bos.write('\n'.code)
                totalBytes++
            }
        }

        if (includePerfSummary) {
            val perfTail = ("\n" + PerformanceMonitor.buildShortPerfReport(isEn = true) + "\n")
                .toByteArray(Charsets.UTF_8)
            bos.write(perfTail)
            totalBytes += perfTail.size
        }

        bos.flush()
        return LogExportResult(
            sessionCount = sessions.size,
            lineCount = totalLines,
            byteCount = totalBytes
        )
    }

    fun exportToCacheFile(
        context: Context,
        fileName: String,
        writer: (OutputStream) -> LogExportResult
    ): Pair<File, LogExportResult> {
        val dir = File(context.applicationContext.cacheDir, "exported_logs").apply {
            if (!exists()) mkdirs()
        }
        // Keep cache tidy by removing older temporary export files
        dir.listFiles()?.forEach { old ->
            if (old.isFile && System.currentTimeMillis() - old.lastModified() > 30 * 60 * 1000L) {
                old.delete()
            }
        }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(dir, safeName)
        val result = FileOutputStream(outFile).use { fos ->
            writer(fos)
        }
        return outFile to result
    }

    /**
     * Redacts sensitive data in nested JSON structures and regex patterns before storing.
     */
    fun sanitizeMessage(raw: String): String {
        if (raw.isBlank()) return raw
        var text = raw.trim()

        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                val obj = JSONObject(text)
                sanitizeJsonObject(obj)
                text = obj.toString()
            } catch (_: Exception) {
            }
        } else if (text.startsWith("[") && text.endsWith("]")) {
            try {
                val arr = JSONArray(text)
                sanitizeJsonArray(arr)
                text = arr.toString()
            } catch (_: Exception) {
            }
        } else {
            val firstBrace = text.indexOf('{')
            val lastBrace = text.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace + 1) {
                val jsonCandidate = text.substring(firstBrace, lastBrace + 1)
                try {
                    val obj = JSONObject(jsonCandidate)
                    sanitizeJsonObject(obj)
                    text = text.substring(0, firstBrace) + obj.toString() + text.substring(lastBrace + 1)
                } catch (_: Exception) {
                }
            }
        }

        text = JWT_REGEX.replace(text, "[REDACTED_JWT]")
        text = BEARER_REGEX.replace(text, "Bearer [REDACTED]")
        text = URL_USERINFO_REGEX.replace(text, "$1[REDACTED]@")
        text = URL_QUERY_SECRET_REGEX.replace(text, "$1[REDACTED]")
        text = HEADER_KV_REGEX.replace(text) { match ->
            val key = match.groupValues[1]
            val sep = match.groupValues[2]
            "$key$sep[REDACTED]"
        }
        text = EMAIL_REGEX.replace(text, "[REDACTED_EMAIL]")
        return text
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.trim().lowercase(Locale.ROOT)
        return SENSITIVE_JSON_KEYS.any { lower == it || lower.contains(it) }
    }

    private fun sanitizeJsonObject(obj: JSONObject) {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.opt(key)
            if (isSensitiveKey(key)) {
                obj.put(key, "[REDACTED]")
            } else {
                when (value) {
                    is JSONObject -> sanitizeJsonObject(value)
                    is JSONArray -> sanitizeJsonArray(value)
                    is String -> obj.put(key, sanitizeMessage(value))
                }
            }
        }
    }

    private fun sanitizeJsonArray(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            when (val value = arr.opt(i)) {
                is JSONObject -> sanitizeJsonObject(value)
                is JSONArray -> sanitizeJsonArray(value)
                is String -> arr.put(i, sanitizeMessage(value))
            }
        }
    }

    fun log(tag: String, message: String, level: AppLogLevel = AppLogLevel.INFO) {
        val cleanTag = tag.trim().uppercase(Locale.ROOT)
        val record = RawLogRecord(
            id = idGenerator.getAndIncrement(),
            timestamp = nowString(),
            tag = cleanTag,
            rawMessage = message,
            lazyMessage = null,
            level = level,
            createdAtMs = System.currentTimeMillis()
        )
        pendingQueue.offer(record)
        scheduleWorkerDrain(flushImmediately = (level == AppLogLevel.ERROR))
    }

    fun logLazy(tag: String, level: AppLogLevel = AppLogLevel.INFO, messageBuilder: () -> String) {
        val cleanTag = tag.trim().uppercase(Locale.ROOT)
        val record = RawLogRecord(
            id = idGenerator.getAndIncrement(),
            timestamp = nowString(),
            tag = cleanTag,
            rawMessage = null,
            lazyMessage = messageBuilder,
            level = level,
            createdAtMs = System.currentTimeMillis()
        )
        pendingQueue.offer(record)
        scheduleWorkerDrain(flushImmediately = (level == AppLogLevel.ERROR))
    }

    fun info(tag: String, message: String) = log(tag, message, AppLogLevel.INFO)

    fun success(tag: String, message: String) = log(tag, message, AppLogLevel.SUCCESS)

    fun warn(tag: String, message: String) = log(tag, message, AppLogLevel.WARN)

    fun error(tag: String, message: String) = log(tag, message, AppLogLevel.ERROR)

    fun clear() {
        synchronized(bufferLock) {
            pendingQueue.clear()
            ringBuffer.clear()
            ringBufferBytes = 0
            ringBufferPerfCount = 0
            pendingDiskEntries.clear()
            lastNonErrTag = ""
            lastNonErrMessage = ""
            lastNonErrAtMs = 0L
            unPublishedUiChanges = false
            _logs.value = emptyList()
        }
        val deleteAllFiles = {
            synchronized(fileLock) {
                try {
                    persistentLogFile?.delete()
                } catch (_: Throwable) {
                }
                try {
                    val root = baseFilesDir?.let { File(it, LOG_SESSIONS_DIR_NAME) }
                    if (root != null && root.exists()) {
                        root.deleteRecursively()
                    }
                } catch (_: Throwable) {
                }
            }
        }
        deleteAllFiles()
        try {
            diskExecutor.execute(deleteAllFiles)
        } catch (_: Throwable) {
        }
    }

    fun exportAsText(): String {
        processPendingLogsLocked(publishUiNow = true, flushDiskNow = false)
        val snapshot = _logs.value
        if (snapshot.isEmpty()) return "No logs recorded (App v$currentAppVersionName)."
        val header = "# Ravno v$currentAppVersionName ($currentAppVersionCode) | ${snapshot.size} lines\n"
        return header + snapshot.joinToString(separator = "\n") { entry ->
            formatEntryCompact(entry)
        }
    }

    fun safeUrlHostPath(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return "(empty)"
        val trimmed = rawUrl.trim()
        return try {
            val noQuery = trimmed.substringBefore('?').substringBefore('#')
            val uri = java.net.URI(noQuery)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: "https"
            val host = uri.host?.lowercase(Locale.ROOT)
            val path = uri.path?.ifBlank { "/" } ?: "/"
            if (!host.isNullOrBlank()) {
                "$scheme://$host$path"
            } else {
                noQuery.replace(URL_USERINFO_REGEX, "$1[REDACTED]@").take(120)
            }
        } catch (_: Throwable) {
            trimmed.substringBefore('?').substringBefore('#')
                .replace(URL_USERINFO_REGEX, "$1[REDACTED]@")
                .take(120)
        }
    }

    fun shortThrowable(t: Throwable?): String {
        if (t == null) return "UnknownError"
        val cls = t.javaClass.simpleName.ifBlank { "Exception" }
        val msg = t.message?.replace("\r\n", " ")?.replace('\n', ' ')?.trim().orEmpty()
        return if (msg.isNotBlank()) {
            "$cls: ${sanitizeMessage(msg).take(140)}"
        } else {
            cls
        }
    }

    // =========================================================================
    // CRASH REPORT & DIAGNOSTIC SUMMARY
    // =========================================================================
    const val LAST_CRASH_FILE_NAME = "last_crash_report.txt"
    const val MAX_CRASH_FILE_BYTES = 20 * 1024 // ~20 KB
    private val IPV4_REDACT_REGEX = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")

    @Volatile
    private var crashHandlerInstalled = false

    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        synchronized(this) {
            if (crashHandlerInstalled) return
            val appCtx = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    saveCrashToInternalFile(appCtx, thread.name, throwable)
                } catch (_: Throwable) {
                } finally {
                    previousHandler?.uncaughtException(thread, throwable)
                }
            }
            crashHandlerInstalled = true
        }
    }

    fun saveCrashToInternalFile(
        context: Context,
        threadName: String,
        throwable: Throwable
    ) {
        try {
            processPendingLogsLocked(publishUiNow = true, flushDiskNow = true)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val rawTrace = Log.getStackTraceString(throwable).ifBlank {
                "${throwable.javaClass.name}: ${throwable.message.orEmpty()}"
            }
            val header = "Time: $timestamp | App: v$currentAppVersionName ($currentAppVersionCode) | Thread: $threadName\nException: ${throwable.javaClass.name}\n"
            val sanitized = IPV4_REDACT_REGEX.replace(sanitizeMessage(header + rawTrace), "[REDACTED_IP]")
            val bytes = sanitized.toByteArray(Charsets.UTF_8)
            val truncatedBytes = if (bytes.size > MAX_CRASH_FILE_BYTES) {
                bytes.copyOf(MAX_CRASH_FILE_BYTES)
            } else {
                bytes
            }
            val file = File(context.filesDir, LAST_CRASH_FILE_NAME)
            file.writeBytes(truncatedBytes)
        } catch (_: Throwable) {
        }
    }

    fun readLastCrashLog(context: Context): String {
        return try {
            val file = File(context.filesDir, LAST_CRASH_FILE_NAME)
            if (!file.exists() || !file.isFile) return ""
            val bytes = file.readBytes()
            val capped = if (bytes.size > MAX_CRASH_FILE_BYTES) {
                bytes.copyOf(MAX_CRASH_FILE_BYTES)
            } else {
                bytes
            }
            val content = String(capped, Charsets.UTF_8).trim()
            IPV4_REDACT_REGEX.replace(sanitizeMessage(content), "[REDACTED_IP]")
        } catch (_: Throwable) {
            ""
        }
    }

    fun buildDiagnosticReport(
        context: Context,
        config: AppConfig = SettingsRepository.config.value,
        vpnState: com.example.vpn.VpnConnectionState = com.example.vpn.VpnManager.state.value,
        networkStatus: com.example.cloudplay.NetworkOptimizerStatus? = null,
        latestSession: PlaySessionRecord? = SessionHistoryRepository.sessions.value.firstOrNull()
    ): String {
        val isEn = config.isSettingsEnglishUI()
        val appVersion = UpdateChecker.getCurrentVersionName(context)
        val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val netType = when {
            networkStatus?.isWifi == true -> "Wi-Fi"
            networkStatus?.isCellular == true -> if (isEn) "Mobile Data (4G/5G)" else "Dữ liệu di động (4G/5G)"
            else -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
                when {
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                        if (isEn) "Mobile Data (4G/5G)" else "Dữ liệu di động (4G/5G)"
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> if (isEn) "Offline / Other" else "Ngoại tuyến / Khác"
                }
            }
        }
        val xboxMarket = config.getEffectiveLocale()
        val resolutionLabel = config.cellularMode.localizedTitle(isEn)
        val fpsLabel = "${config.streamFpsCap.fps} FPS"
        val vpnEnabledLabel = when (vpnState.mode) {
            com.example.vpn.NetworkMode.VPN_JAPAN -> if (isEn) "ON (${vpnState.engine.name} • ${vpnState.currentServer.countryCode})" else "BẬT (${vpnState.engine.name} • ${vpnState.currentServer.countryCode})"
            com.example.vpn.NetworkMode.DIRECT_NETWORK -> if (isEn) "OFF (Direct ISP)" else "TẮT (Mạng trực tiếp)"
            com.example.vpn.NetworkMode.DISCONNECTED -> if (isEn) "OFF (Idle)" else "TẮT (Chờ)"
        }

        val sessionSummary = if (latestSession != null) {
            if (isEn) {
                "Duration: ${latestSession.durationSeconds}s | Avg FPS: ${latestSession.avgFps} | Avg Ping: ${latestSession.avgPingMs}ms | Jitter: ${latestSession.jitterMs}ms | Decode: ${latestSession.avgDecodeMs}ms | Dropped: ${latestSession.framesDropped} | Freezes: ${latestSession.freezeCount}"
            } else {
                "Thời lượng: ${latestSession.durationSeconds}s | FPS TB: ${latestSession.avgFps} | Ping TB: ${latestSession.avgPingMs}ms | Jitter: ${latestSession.jitterMs}ms | Giải mã: ${latestSession.avgDecodeMs}ms | Rớt khung: ${latestSession.framesDropped} | Đứng hình: ${latestSession.freezeCount}"
            }
        } else {
            if (isEn) "No recorded session yet" else "Chưa có phiên chơi nào"
        }

        val lastCrash = readLastCrashLog(context).ifBlank {
            if (isEn) "None" else "Không có"
        }

        return buildString {
            appendLine("=== RAVNO DIAGNOSTIC REPORT ===")
            appendLine(if (isEn) "App Version: v$appVersion" else "Phiên bản ứng dụng: v$appVersion")
            appendLine(if (isEn) "Android Version: $androidVer" else "Phiên bản Android: $androidVer")
            appendLine(if (isEn) "Device Model: $deviceModel" else "Tên máy: $deviceModel")
            appendLine(if (isEn) "Network Type: $netType" else "Loại mạng: $netType")
            appendLine(if (isEn) "Xbox Market: $xboxMarket" else "Thị trường Xbox đang chọn: $xboxMarket")
            appendLine(if (isEn) "Main Settings: Resolution=$resolutionLabel | FPS=$fpsLabel | VPN=$vpnEnabledLabel" else "Cài đặt chính: Độ phân giải=$resolutionLabel | FPS=$fpsLabel | VPN=$vpnEnabledLabel")
            appendLine(if (isEn) "Latest Session Telemetry: $sessionSummary" else "Tóm tắt telemetry phiên gần nhất: $sessionSummary")
            appendLine(if (isEn) "--- Latest Crash Log ---" else "--- Crash gần nhất ---")
            append(lastCrash)
        }
    }
}
