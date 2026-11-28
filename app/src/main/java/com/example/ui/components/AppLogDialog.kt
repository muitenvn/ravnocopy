package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.R
import com.example.data.AppLogLevel
import com.example.data.AppLogger
import com.example.data.PerformanceMonitor
import com.example.ui.theme.DirectCyan
import com.example.ui.theme.JapanRed
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import com.example.vpn.VpnManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppLogDialog(
    isEn: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs by AppLogger.logs.collectAsState()
    var selectedFilter by remember { mutableStateOf("ALL") }
    var showExportMode by remember { mutableStateOf(false) }

    BackHandler(enabled = showExportMode) {
        showExportMode = false
    }

    val (errorCount, warnCount) = remember(logs) {
        var errs = 0
        var warns = 0
        for (entry in logs) {
            if (entry.level == AppLogLevel.ERROR) errs++
            else if (entry.level == AppLogLevel.WARN) warns++
        }
        errs to warns
    }

    val filteredLogs = remember(logs, selectedFilter) {
        val reversed = logs.asReversed()
        when (selectedFilter) {
            "ERRORS" -> reversed.filter { it.level == AppLogLevel.ERROR || it.level == AppLogLevel.WARN }
            "UI" -> reversed.filter { it.tag == "UI" || it.tag == "WEBVIEW" }
            "PROBE" -> reversed.filter { it.tag == "PROBE" || it.tag == "PING" }
            "VPN" -> reversed.filter { it.tag == "VPN" || it.tag == "UNLOCK" || it.tag == "SMARTSELECT" }
            "STREAM" -> reversed.filter { it.tag == "STREAM" }
            "PERF" -> reversed.filter { it.tag == "PERF" || it.tag == "PERF-INIT" }
            else -> reversed
        }
    }

    val closeFocusRequester = remember { FocusRequester() }
    val exportFocusRequester = remember { FocusRequester() }
    val copyFocusRequester = remember { FocusRequester() }
    val clearFocusRequester = remember { FocusRequester() }
    val retestFocusRequester = remember { FocusRequester() }
    val filterKeys = remember { listOf("ALL", "ERRORS", "UI", "PROBE", "VPN", "STREAM", "PERF") }

    val issueTotal = errorCount + warnCount
    val filters = remember(logs.size, issueTotal, isEn) {
        listOf(
            "ALL" to if (isEn) "All (${logs.size})" else "Tất cả (${logs.size})",
            "ERRORS" to if (isEn) "Errors ($issueTotal)" else "Chỉ lỗi ($issueTotal)",
            "UI" to if (isEn) "Actions [UI]" else "Thao tác [UI]",
            "PROBE" to if (isEn) "Probe & Ping" else "Trạm đo & Ping",
            "VPN" to if (isEn) "VPN & Unlock" else "VPN & Vùng",
            "STREAM" to "Stream",
            "PERF" to if (isEn) "Perf [PERF]" else "Hiệu năng [PERF]"
        )
    }

    val copyLogsAction: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val exportedText = if (selectedFilter == "PERF") {
            PerformanceMonitor.buildShortPerfReport(isEn)
        } else {
            AppLogger.exportAsText() + "\n\n" + PerformanceMonitor.buildShortPerfReport(isEn)
        }
        clipboard?.setPrimaryClip(ClipData.newPlainText("Ravno Log", exportedText))
        Toast.makeText(
            context,
            if (isEn) "Copied logs to clipboard" else "Đã sao chép nhật ký vào bộ nhớ tạm",
            Toast.LENGTH_SHORT
        ).show()
    }

    val retestPingAction: () -> Unit = {
        AppLogger.info("UI", "Retest ping from log")
        VpnManager.testAllServers(
            autoPickBest = false,
            context = context,
            reason = "log-retest"
        )
    }

    val appVerLabel = "v${AppLogger.currentAppVersionName}"

    AdaptivePopupFrame(
        onDismissRequest = onDismiss,
        dialogTestTag = "app_log_dialog",
        defaultFocusRequester = closeFocusRequester,
        wideWidthFraction = 0.94f,
        portraitWidthFraction = 0.94f,
        onPreviousTab = if (!showExportMode) {
            {
                val curIdx = filterKeys.indexOf(selectedFilter).coerceAtLeast(0)
                selectedFilter = filterKeys[(curIdx - 1 + filterKeys.size) % filterKeys.size]
            }
        } else null,
        onNextTab = if (!showExportMode) {
            {
                val curIdx = filterKeys.indexOf(selectedFilter).coerceAtLeast(0)
                selectedFilter = filterKeys[(curIdx + 1) % filterKeys.size]
            }
        } else null,
        onBackPress = {
            if (showExportMode) showExportMode = false else onDismiss()
        }
    ) {
        @Composable
        fun HeaderSection() {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gapMed)
            ) {
                Box(
                    modifier = Modifier
                        .size(iconBoxSize)
                        .clip(RoundedCornerShape(btnCorner))
                        .background(
                            if (showExportMode) DirectCyan.copy(alpha = 0.16f)
                            else if (errorCount > 0) JapanRed.copy(alpha = 0.20f)
                            else XboxNeonGreen.copy(alpha = 0.16f)
                        )
                        .border(
                            borderThin,
                            if (showExportMode) DirectCyan.copy(alpha = 0.65f)
                            else if (errorCount > 0) JapanRed.copy(alpha = 0.75f)
                            else XboxNeonGreen.copy(alpha = 0.55f),
                            RoundedCornerShape(btnCorner)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (showExportMode) Icons.Default.Download else Icons.Default.Terminal,
                        contentDescription = null,
                        tint = if (showExportMode) DirectCyan else if (errorCount > 0) JapanRed else XboxNeonGreen,
                        modifier = Modifier.size(iconSize)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(rDp(2f))) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(rDp(6f))
                    ) {
                        Text(
                            text = if (showExportMode) {
                                stringResource(R.string.log_export_title)
                            } else if (isEn) {
                                "System Activity Log"
                            } else {
                                "Nhật ký hệ thống (App Log)"
                            },
                            color = TextPrimary,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = rSp(15f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = appVerLabel,
                            color = DirectCyan,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(9.5f),
                            modifier = Modifier
                                .background(DirectCyan.copy(alpha = 0.14f), RoundedCornerShape(rDp(4f)))
                                .padding(horizontal = rDp(5f), vertical = rDp(1.5f))
                        )
                    }
                    Text(
                        text = if (showExportMode) {
                            stringResource(R.string.log_export_subtitle)
                        } else if (isEn) {
                            "${logs.size}/${AppLogger.MAX_LOGS} events · $errorCount errors, $warnCount warnings"
                        } else {
                            "${logs.size}/${AppLogger.MAX_LOGS} bản ghi · $errorCount lỗi, $warnCount cảnh báo"
                        },
                        color = when {
                            showExportMode -> TextSecondary
                            errorCount > 0 -> JapanRed
                            warnCount > 0 -> WarningAmber
                            else -> TextSecondary
                        },
                        fontWeight = if (!showExportMode && (errorCount > 0 || warnCount > 0)) FontWeight.Bold else FontWeight.Normal,
                        fontSize = rSp(10.5f),
                        lineHeight = rSp(13.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        @Composable
        fun FilterChipItem(key: String, label: String) {
            val isSelected = selectedFilter == key
            val isErrorChip = key == "ERRORS"
            val chipActiveColor = if (isErrorChip && errorCount > 0) JapanRed else XboxNeonGreen
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(chipCorner))
                    .gamepadClickable(cornerRadius = chipCorner) { selectedFilter = key }
                    .testTag("app_log_filter_$key"),
                shape = RoundedCornerShape(chipCorner),
                color = when {
                    isSelected -> chipActiveColor
                    isErrorChip && errorCount > 0 -> Color(0xFF2B1115)
                    isErrorChip && warnCount > 0 -> Color(0xFF261E0D)
                    else -> XboxSurface
                },
                border = BorderStroke(
                    borderThin,
                    when {
                        isSelected -> chipActiveColor
                        isErrorChip && errorCount > 0 -> JapanRed.copy(alpha = 0.7f)
                        isErrorChip && warnCount > 0 -> WarningAmber.copy(alpha = 0.65f)
                        else -> XboxCardBorder
                    }
                )
            ) {
                Text(
                    text = label,
                    color = when {
                        isSelected -> if (isErrorChip && errorCount > 0) Color.White else Color.Black
                        isErrorChip && errorCount > 0 -> Color(0xFFFF8A80)
                        isErrorChip && warnCount > 0 -> WarningAmber
                        else -> TextSecondary
                    },
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = if (isSelected || (isErrorChip && issueTotal > 0)) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = rSp(10.5f),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = padInner, vertical = rDp(5f))
                )
            }
        }

        @Composable
        fun LogListContainer(minH: Dp, maxH: Dp) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minH, max = maxH),
                shape = RoundedCornerShape(sectionCorner),
                color = XboxSurface,
                border = BorderStroke(borderThin, XboxCardBorder)
            ) {
                if (filteredLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(minH)
                            .padding(padInner),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (selectedFilter == "ERRORS") {
                                if (isEn) "No errors or warnings recorded." else "Không có lỗi hoặc cảnh báo nào."
                            } else if (isEn) {
                                "No log entries in this filter yet."
                            } else {
                                "Chưa có dòng nhật ký nào trong mục lọc này."
                            },
                            color = TextSecondary,
                            fontSize = rSp(11.5f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(padInner),
                        verticalArrangement = Arrangement.spacedBy(gapSmall)
                    ) {
                        items(filteredLogs, key = { it.id }, contentType = { "log_entry_row" }) { entry ->
                            val isError = entry.level == AppLogLevel.ERROR
                            val isWarn = entry.level == AppLogLevel.WARN
                            val badgeColor = when (entry.level) {
                                AppLogLevel.SUCCESS -> XboxNeonGreen
                                AppLogLevel.WARN -> WarningAmber
                                AppLogLevel.ERROR -> JapanRed
                                AppLogLevel.INFO -> DirectCyan
                            }
                            val rowBgColor = when (entry.level) {
                                AppLogLevel.ERROR -> Color(0xFF2B1115)
                                AppLogLevel.WARN -> Color(0xFF241C0C)
                                else -> Color(0xFF0E1712)
                            }
                            val rowBorderColor = when (entry.level) {
                                AppLogLevel.ERROR -> JapanRed.copy(alpha = 0.80f)
                                AppLogLevel.WARN -> WarningAmber.copy(alpha = 0.65f)
                                else -> Color.Transparent
                            }
                            val msgColor = when (entry.level) {
                                AppLogLevel.ERROR -> Color(0xFFFFB4AB)
                                AppLogLevel.WARN -> Color(0xFFFFE082)
                                else -> TextPrimary
                            }
                            val levelIcon = when (entry.level) {
                                AppLogLevel.ERROR -> "✕ "
                                AppLogLevel.WARN -> "! "
                                AppLogLevel.SUCCESS -> "✓ "
                                AppLogLevel.INFO -> ""
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(chipCorner))
                                    .background(rowBgColor)
                                    .then(
                                        if (isError || isWarn) {
                                            Modifier.border(borderThin, rowBorderColor, RoundedCornerShape(chipCorner))
                                        } else {
                                            Modifier
                                        }
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isError || isWarn) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = rDp(4f), height = rDp(36f))
                                            .background(badgeColor)
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = rDp(8f), vertical = rDp(5f)),
                                    verticalArrangement = Arrangement.spacedBy(rDp(2f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(gapSmall)
                                    ) {
                                        Text(
                                            text = entry.timestamp,
                                            color = if (isError) Color(0xFFFF8A80) else TextSecondary,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = rSp(9.2f)
                                        )
                                        Text(
                                            text = "${entry.level.shortCode}/${entry.tag}",
                                            color = badgeColor,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = rSp(8.5f),
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier
                                                .background(
                                                    badgeColor.copy(alpha = if (isError) 0.28f else 0.18f),
                                                    RoundedCornerShape(rDp(4f))
                                                )
                                                .border(
                                                    borderThin,
                                                    badgeColor.copy(alpha = 0.75f),
                                                    RoundedCornerShape(rDp(4f))
                                                )
                                                .padding(horizontal = rDp(4f), vertical = rDp(1f))
                                        )
                                    }
                                    Text(
                                        text = "$levelIcon${entry.message}",
                                        color = msgColor,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = rSp(10.2f),
                                        maxLines = 8,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        @Composable
        fun ActionButtonsSection() {
            if (showExportMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gapSmall)
                ) {
                    AdaptiveFlatActionButton(
                        onClick = { showExportMode = false },
                        isPrimary = false,
                        containerColor = XboxSurface,
                        contentColor = DirectCyan,
                        borderColor = DirectCyan.copy(alpha = 0.65f),
                        focusedBorderColor = DirectCyan,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_back_to_logs_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = DirectCyan,
                            modifier = Modifier.size(rDp(14f))
                        )
                        Spacer(modifier = Modifier.width(rDp(4f)))
                        Text(
                            text = stringResource(R.string.log_export_back),
                            color = DirectCyan,
                            fontSize = rSp(11f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    AdaptiveFlatActionButton(
                        onClick = onDismiss,
                        isPrimary = true,
                        gamepadHint = "B",
                        focusRequester = closeFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("close_app_log_btn")
                    ) {
                        Text(
                            text = if (isEn) "Close" else "Đóng",
                            color = Color(0xFF04150B),
                            fontSize = rSp(11.5f),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
                return
            }

            Column(verticalArrangement = Arrangement.spacedBy(gapSmall)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gapSmall)
                ) {
                    AdaptiveFlatActionButton(
                        onClick = { showExportMode = true },
                        isPrimary = false,
                        containerColor = Color(0xFF16352A),
                        contentColor = XboxNeonGreen,
                        borderColor = XboxNeonGreen.copy(alpha = 0.7f),
                        focusedBorderColor = XboxNeonGreen,
                        focusRequester = exportFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_app_log_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = XboxNeonGreen,
                            modifier = Modifier.size(rDp(14f))
                        )
                        Spacer(modifier = Modifier.width(rDp(4f)))
                        Text(
                            text = stringResource(R.string.log_export_btn),
                            color = XboxNeonGreen,
                            fontSize = rSp(11f),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    AdaptiveFlatActionButton(
                        onClick = copyLogsAction,
                        isPrimary = false,
                        containerColor = XboxSurface,
                        contentColor = DirectCyan,
                        borderColor = DirectCyan.copy(alpha = 0.65f),
                        focusedBorderColor = DirectCyan,
                        focusRequester = copyFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_app_log_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = DirectCyan,
                            modifier = Modifier.size(rDp(14f))
                        )
                        Spacer(modifier = Modifier.width(rDp(4f)))
                        Text(
                            text = if (isEn) "Copy" else "Sao chép",
                            color = DirectCyan,
                            fontSize = rSp(11f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gapSmall)
                ) {
                    AdaptiveFlatActionButton(
                        onClick = retestPingAction,
                        isPrimary = false,
                        containerColor = XboxSurface,
                        contentColor = XboxNeonGreen,
                        borderColor = XboxNeonGreen.copy(alpha = 0.55f),
                        focusedBorderColor = XboxNeonGreen,
                        focusRequester = retestFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("retest_ping_from_log_btn")
                    ) {
                        Text(
                            text = if (isEn) "Ping Test" else "Đo ping lại",
                            color = XboxNeonGreen,
                            fontSize = rSp(10.5f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    AdaptiveFlatActionButton(
                        onClick = { AppLogger.clear() },
                        isPrimary = false,
                        containerColor = XboxSurface,
                        contentColor = JapanRed,
                        borderColor = JapanRed.copy(alpha = 0.65f),
                        focusedBorderColor = JapanRed,
                        focusRequester = clearFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("clear_app_log_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = JapanRed,
                            modifier = Modifier.size(rDp(13f))
                        )
                        Spacer(modifier = Modifier.width(rDp(3f)))
                        Text(
                            text = if (isEn) "Clear" else "Xóa log",
                            color = JapanRed,
                            fontSize = rSp(10.5f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    AdaptiveFlatActionButton(
                        onClick = onDismiss,
                        isPrimary = true,
                        gamepadHint = "B",
                        focusRequester = closeFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("close_app_log_btn")
                    ) {
                        Text(
                            text = if (isEn) "Close" else "Đóng",
                            color = Color(0xFF04150B),
                            fontSize = rSp(11f),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        AdaptiveTwoColumnLayout(
            leftWeight = 0.85f,
            rightWeight = 1.25f,
            leftColumn = {
                HeaderSection()
                if (!showExportMode) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gapSmall),
                        verticalArrangement = Arrangement.spacedBy(gapSmall)
                    ) {
                        filters.forEach { (key, label) ->
                            FilterChipItem(key = key, label = label)
                        }
                    }
                }
                ActionButtonsSection()
            },
            rightColumn = {
                if (showExportMode) {
                    AppLogExportPanel(
                        refreshKey = logs.size,
                        minH = winH * 0.66f,
                        maxH = winH * 0.78f
                    )
                } else {
                    LogListContainer(minH = winH * 0.66f, maxH = winH * 0.78f)
                }
            },
            portraitColumn = {
                HeaderSection()
                if (showExportMode) {
                    AppLogExportPanel(
                        refreshKey = logs.size,
                        minH = winH * 0.40f,
                        maxH = winH * 0.56f
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(gapSmall)) {
                        items(filters) { (key, label) ->
                            FilterChipItem(key = key, label = label)
                        }
                    }
                    LogListContainer(minH = winH * 0.34f, maxH = winH * 0.48f)
                }
                ActionButtonsSection()
            }
        )
    }
}
