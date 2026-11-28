package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.AppLogger
import com.example.data.LogExportResult
import com.example.data.StoredLogSession
import com.example.data.StoredLogVersionGroup
import com.example.ui.theme.DirectCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogExportTab {
    BY_VERSION,
    BY_SESSION
}

private sealed class PendingLogExport {
    data object All : PendingLogExport()
    data class Version(val versionName: String) : PendingLogExport()
    data class Session(val session: StoredLogSession) : PendingLogExport()
}

private fun formatFileBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun buildDefaultExportFileName(target: PendingLogExport): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return when (target) {
        is PendingLogExport.All -> "ravno-logs-all-$stamp.txt"
        is PendingLogExport.Version -> "ravno-logs-v${target.versionName}-$stamp.txt"
        is PendingLogExport.Session -> {
            val cleanStart = target.session.startedAtLabel
                .replace("-", "")
                .replace(":", "")
                .replace(" ", "-")
            "ravno-log-v${target.session.versionName}-$cleanStart.txt"
        }
    }
}

private fun executeExportToStream(
    context: Context,
    target: PendingLogExport,
    output: OutputStream
): LogExportResult {
    return when (target) {
        is PendingLogExport.All -> AppLogger.writeAllLogsToStream(output, context)
        is PendingLogExport.Version -> AppLogger.writeVersionLogsToStream(target.versionName, output, context)
        is PendingLogExport.Session -> AppLogger.writeSessionLogsToStream(target.session, output)
    }
}

@Composable
internal fun AdaptivePopupScope.AppLogExportPanel(
    refreshKey: Int,
    minH: Dp,
    maxH: Dp
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(LogExportTab.BY_VERSION) }
    var versionGroups by remember { mutableStateOf<List<StoredLogVersionGroup>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<StoredLogSession>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<PendingLogExport?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    val shareTitle = stringResource(R.string.log_export_share_title)
    val exportFailedMsg = stringResource(R.string.log_export_failed)

    LaunchedEffect(refreshKey) {
        val loadedVersions = withContext(Dispatchers.IO) {
            AppLogger.listStoredVersions(context)
        }
        versionGroups = loadedVersions
        sessions = loadedVersions.flatMap { it.sessions }.sortedWith(
            compareByDescending<StoredLogSession> { it.isCurrentSession }
                .thenByDescending { it.startedAtLabel }
        )
    }

    val shareViaFileProviderFallback: (PendingLogExport, String) -> Unit = { target, fileName ->
        coroutineScope.launch {
            isExporting = true
            try {
                val (file, result) = withContext(Dispatchers.IO) {
                    AppLogger.exportToCacheFile(context, fileName) { out ->
                        executeExportToStream(context, target, out)
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, shareTitle))
                val msg = context.getString(
                    R.string.log_export_success,
                    result.lineCount,
                    formatFileBytes(result.byteCount)
                )
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(context, exportFailedMsg, Toast.LENGTH_SHORT).show()
            } finally {
                isExporting = false
            }
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isExporting = true
            try {
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        executeExportToStream(context, target, out)
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                val msg = context.getString(
                    R.string.log_export_success,
                    result.lineCount,
                    formatFileBytes(result.byteCount)
                )
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(context, exportFailedMsg, Toast.LENGTH_SHORT).show()
            } finally {
                isExporting = false
            }
        }
    }

    val triggerExport: (PendingLogExport) -> Unit = { target ->
        if (!isExporting) {
            val suggestedName = buildDefaultExportFileName(target)
            pendingExport = target
            try {
                createDocumentLauncher.launch(suggestedName)
            } catch (_: ActivityNotFoundException) {
                pendingExport = null
                shareViaFileProviderFallback(target, suggestedName)
            } catch (_: Throwable) {
                pendingExport = null
                shareViaFileProviderFallback(target, suggestedName)
            }
        }
    }

    val totalBytes = remember(sessions) { sessions.sumOf { it.sizeBytes } }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minH, max = maxH),
        shape = RoundedCornerShape(sectionCorner),
        color = XboxSurface,
        border = BorderStroke(borderThin, XboxCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padInner),
            verticalArrangement = Arrangement.spacedBy(gapSmall)
        ) {
            // 1. Export All button (always one tap away)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(chipCorner))
                    .gamepadClickable(cornerRadius = chipCorner) {
                        if (sessions.isNotEmpty()) triggerExport(PendingLogExport.All)
                    }
                    .testTag("export_all_logs_btn"),
                shape = RoundedCornerShape(chipCorner),
                color = Color(0xFF16352A),
                border = BorderStroke(borderThin, XboxNeonGreen.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = padInner, vertical = rDp(8f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gapSmall)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        tint = XboxNeonGreen,
                        modifier = Modifier.size(rDp(18f))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.log_export_all_btn),
                            color = XboxNeonGreen,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = rSp(12f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.log_export_all_meta,
                                versionGroups.size,
                                sessions.size,
                                formatFileBytes(totalBytes)
                            ),
                            color = TextSecondary,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = rSp(9.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.log_export_all_btn),
                        tint = XboxNeonGreen,
                        modifier = Modifier.size(rDp(16f))
                    )
                }
            }

            // 2. Mode tabs: By Version (default) vs By Session
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                val tabs = listOf(
                    LogExportTab.BY_VERSION to stringResource(R.string.log_export_tab_version),
                    LogExportTab.BY_SESSION to stringResource(R.string.log_export_tab_session)
                )
                tabs.forEach { (tab, label) ->
                    val selected = activeTab == tab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(chipCorner))
                            .gamepadClickable(cornerRadius = chipCorner) { activeTab = tab }
                            .testTag("export_tab_${tab.name.lowercase(Locale.ROOT)}"),
                        shape = RoundedCornerShape(chipCorner),
                        color = if (selected) DirectCyan else Color(0xFF142019),
                        border = BorderStroke(
                            borderThin,
                            if (selected) DirectCyan else XboxCardBorder
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = rDp(8f), vertical = rDp(6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) Color(0xFF041417) else TextSecondary,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                fontSize = rSp(10.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 3. List of versions or launch sessions
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rDp(90f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.log_export_empty),
                        color = TextSecondary,
                        fontSize = rSp(11f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(gapSmall)
                ) {
                    if (activeTab == LogExportTab.BY_VERSION) {
                        items(versionGroups, key = { "ver_${it.versionName}" }) { group ->
                            val hasCurrent = group.sessions.any { it.isCurrentSession }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(chipCorner))
                                    .background(if (hasCurrent) Color(0xFF142019) else Color(0xFF0E1712))
                                    .border(
                                        borderThin,
                                        if (hasCurrent) DirectCyan.copy(alpha = 0.55f) else XboxCardBorder,
                                        RoundedCornerShape(chipCorner)
                                    )
                                    .gamepadClickable(cornerRadius = chipCorner) {
                                        triggerExport(PendingLogExport.Version(group.versionName))
                                    }
                                    .padding(horizontal = rDp(10f), vertical = rDp(8f))
                                    .testTag("export_version_${group.versionName}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(gapSmall)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(rDp(6f))
                                    ) {
                                        Text(
                                            text = "v${group.versionName}",
                                            color = TextPrimary,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = rSp(12f),
                                            maxLines = 1
                                        )
                                        if (hasCurrent) {
                                            Text(
                                                text = stringResource(R.string.log_export_current_badge),
                                                color = XboxNeonGreen,
                                                fontFamily = SpaceGroteskFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = rSp(9f),
                                                modifier = Modifier
                                                    .background(
                                                        XboxNeonGreen.copy(alpha = 0.16f),
                                                        RoundedCornerShape(rDp(4f))
                                                    )
                                                    .padding(horizontal = rDp(5f), vertical = rDp(1f))
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.log_export_version_sessions,
                                            group.sessionCount,
                                            formatFileBytes(group.totalSizeBytes)
                                        ),
                                        color = TextSecondary,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = rSp(9.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "v${group.versionName}",
                                    tint = DirectCyan,
                                    modifier = Modifier.size(rDp(16f))
                                )
                            }
                        }
                    } else {
                        items(sessions, key = { "sess_${it.id}" }) { session ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(chipCorner))
                                    .background(if (session.isCurrentSession) Color(0xFF142019) else Color(0xFF0E1712))
                                    .border(
                                        borderThin,
                                        if (session.isCurrentSession) XboxNeonGreen.copy(alpha = 0.55f) else XboxCardBorder,
                                        RoundedCornerShape(chipCorner)
                                    )
                                    .gamepadClickable(cornerRadius = chipCorner) {
                                        triggerExport(PendingLogExport.Session(session))
                                    }
                                    .padding(horizontal = rDp(10f), vertical = rDp(7.5f))
                                    .testTag("export_session_${session.pid}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(gapSmall)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = if (session.isCurrentSession) XboxNeonGreen else TextSecondary,
                                    modifier = Modifier.size(rDp(15f))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(rDp(5f))
                                    ) {
                                        Text(
                                            text = "v${session.versionName}",
                                            color = DirectCyan,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = rSp(10.5f),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = session.startedAtLabel,
                                            color = TextPrimary,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = rSp(10.5f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (session.isCurrentSession) {
                                            Text(
                                                text = stringResource(R.string.log_export_current_badge),
                                                color = XboxNeonGreen,
                                                fontFamily = SpaceGroteskFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = rSp(8.5f),
                                                modifier = Modifier
                                                    .background(
                                                        XboxNeonGreen.copy(alpha = 0.16f),
                                                        RoundedCornerShape(rDp(4f))
                                                    )
                                                    .padding(horizontal = rDp(4f), vertical = rDp(1f))
                                            )
                                        }
                                    }
                                    Text(
                                        text = "PID ${session.pid} · ${formatFileBytes(session.sizeBytes)}",
                                        color = TextSecondary,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = rSp(9.2f),
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.width(rDp(2f)))
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = session.startedAtLabel,
                                    tint = DirectCyan,
                                    modifier = Modifier.size(rDp(15f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
