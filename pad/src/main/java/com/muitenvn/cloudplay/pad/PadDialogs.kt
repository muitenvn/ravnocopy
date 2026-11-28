package com.muitenvn.cloudplay.pad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Hộp thoại cập nhật & tự động tải APK cho Ravno Pad (phong cách tonal flat, thích ứng mọi màn hình).
 */
@Composable
internal fun PadUpdateDialog(
    lang: PadLanguage,
    m: PadResponsiveMetrics,
    updateInfo: PadUpdateCheckState.UpdateAvailable,
    downloadState: PadApkDownloadState,
    onStartDownload: () -> Unit,
    onSkipMirror: () -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenGitHub: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 540.dp)
                .clip(RoundedCornerShape(m.cardRadius))
                .background(PadSurface)
                .border(m.strokeFocus, PadAccent, RoundedCornerShape(m.cardRadius))
                .padding(m.cardPad * 1.15f)
                .verticalScroll(rememberScrollState())
                .testTag("pad_update_dialog"),
            verticalArrangement = Arrangement.spacedBy(m.itemGap * 0.85f)
        ) {
            Text(
                text = PadStrings.updateAvailableTitle(lang, updateInfo.latestVersion),
                color = PadInk,
                fontSize = m.headingFont,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = PadStrings.updateVersionDiff(lang, updateInfo.currentVersion, updateInfo.latestVersion),
                color = PadCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = m.captionFont,
                fontWeight = FontWeight.Bold
            )

            if (updateInfo.releaseNotes.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(m.buttonRadius))
                        .background(PadSurface2)
                        .border(m.strokeThin, PadBorder, RoundedCornerShape(m.buttonRadius))
                        .padding(m.cardPad * 0.75f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = updateInfo.releaseNotes,
                        color = PadMuted,
                        fontSize = m.captionFont
                    )
                }
            }

            // Khối trạng thái tự động tải & xác thực SHA-256
            when (downloadState) {
                is PadApkDownloadState.Downloading -> {
                    val pctText = if (downloadState.progress >= 0f) {
                        "${(downloadState.progress * 100f).toInt()}%"
                    } else {
                        "${downloadState.downloadedBytes / 1024L} KB"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.buttonRadius))
                            .background(PadSurface2)
                            .border(m.strokeThin, PadBorder, RoundedCornerShape(m.buttonRadius))
                            .padding(m.cardPad * 0.75f)
                            .testTag("pad_update_progress_box"),
                        verticalArrangement = Arrangement.spacedBy(m.itemGap * 0.55f)
                    ) {
                        Text(
                            text = PadStrings.updateDownloadingStatus(lang, downloadState.activeMirrorName, pctText),
                            color = PadAccent,
                            fontSize = m.captionFont,
                            fontWeight = FontWeight.Bold
                        )
                        if (downloadState.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { downloadState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = PadAccent,
                                trackColor = PadBorder
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = PadAccent,
                                trackColor = PadBorder
                            )
                        }
                    }
                }

                PadApkDownloadState.Verifying -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.buttonRadius))
                            .background(PadSurface2)
                            .border(m.strokeThin, PadCyan, RoundedCornerShape(m.buttonRadius))
                            .padding(m.cardPad * 0.75f),
                        verticalArrangement = Arrangement.spacedBy(m.itemGap * 0.5f)
                    ) {
                        Text(
                            text = PadStrings.updateVerifyingShaStatus(lang),
                            color = PadCyan,
                            fontSize = m.captionFont,
                            fontWeight = FontWeight.Bold
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = PadCyan,
                            trackColor = PadBorder
                        )
                    }
                }

                is PadApkDownloadState.VerifiedReadyToInstall -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.buttonRadius))
                            .background(PadSelected)
                            .border(m.strokeThin, PadAccent, RoundedCornerShape(m.buttonRadius))
                            .padding(m.cardPad * 0.75f)
                    ) {
                        Text(
                            text = PadStrings.updateVerifiedReadyStatus(lang),
                            color = PadAccent,
                            fontSize = m.captionFont,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                PadApkDownloadState.DownloadError -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(m.buttonRadius))
                            .background(PadSurface2)
                            .border(m.strokeThin, PadDanger, RoundedCornerShape(m.buttonRadius))
                            .padding(m.cardPad * 0.75f)
                    ) {
                        Text(
                            text = PadStrings.updateDownloadErrorStatus(lang),
                            color = PadDanger,
                            fontSize = m.captionFont,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                PadApkDownloadState.Idle -> Unit
            }

            Spacer(modifier = Modifier.height(m.itemGap * 0.15f))

            // Hàng nút chính
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(m.itemGap * 0.75f)
            ) {
                when (downloadState) {
                    is PadApkDownloadState.VerifiedReadyToInstall -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(m.buttonRadius))
                                .background(PadAccent)
                                .clickable { onInstallApk(downloadState.apkFile) }
                                .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.75f)
                                .testTag("pad_update_install_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = PadStrings.updateInstallNowBtn(lang),
                                color = PadOnAccent,
                                fontSize = m.bodyFont,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    is PadApkDownloadState.Downloading -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(m.buttonRadius))
                                .background(PadSurface2)
                                .border(m.strokeThin, PadCyan, RoundedCornerShape(m.buttonRadius))
                                .clickable(onClick = onSkipMirror)
                                .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.75f)
                                .testTag("pad_update_skip_mirror_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = PadStrings.updateSkipMirrorBtn(lang),
                                color = PadCyan,
                                fontSize = m.bodyFont,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    PadApkDownloadState.Verifying -> Unit

                    PadApkDownloadState.Idle,
                    PadApkDownloadState.DownloadError -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(m.buttonRadius))
                                .background(PadAccent)
                                .clickable(onClick = onStartDownload)
                                .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.75f)
                                .testTag("pad_update_download_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = PadStrings.updateDownloadBtn(lang),
                                color = PadOnAccent,
                                fontSize = m.bodyFont,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(m.buttonRadius))
                        .background(PadSurface2)
                        .border(m.strokeThin, PadBorder, RoundedCornerShape(m.buttonRadius))
                        .clickable { onOpenGitHub(updateInfo.releaseUrl) }
                        .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.75f)
                        .testTag("pad_update_github_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = PadStrings.updateOpenGithubBtn(lang),
                        color = PadInk,
                        fontSize = m.bodyFont,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.buttonRadius))
                    .background(PadSurface2)
                    .border(m.strokeThin, PadBorder, RoundedCornerShape(m.buttonRadius))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.65f)
                    .testTag("pad_update_later_btn"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = PadStrings.updateLaterBtn(lang),
                    color = PadMuted,
                    fontSize = m.bodyFont,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Hộp thoại Điều khoản sử dụng & Quyền riêng tư cho Ravno Pad (đủ 12 ngôn ngữ).
 */
@Composable
internal fun PadTermsDialog(
    lang: PadLanguage,
    m: PadResponsiveMetrics,
    requireAgreement: Boolean,
    onOpenFullWeb: () -> Unit,
    onAcceptOrClose: () -> Unit,
    onDeclineExit: () -> Unit
) {
    Dialog(
        onDismissRequest = { if (!requireAgreement) onAcceptOrClose() },
        properties = DialogProperties(
            dismissOnBackPress = !requireAgreement,
            dismissOnClickOutside = !requireAgreement,
            usePlatformDefaultWidth = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 540.dp)
                .clip(RoundedCornerShape(m.cardRadius))
                .background(PadSurface)
                .border(m.strokeFocus, PadAccent, RoundedCornerShape(m.cardRadius))
                .padding(m.cardPad * 1.15f)
                .verticalScroll(rememberScrollState())
                .testTag("pad_terms_dialog"),
            verticalArrangement = Arrangement.spacedBy(m.itemGap * 0.85f)
        ) {
            Text(
                text = PadStrings.termsDialogTitle(lang),
                color = PadInk,
                fontSize = m.headingFont,
                fontWeight = FontWeight.ExtraBold
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.buttonRadius))
                    .background(PadSurface2)
                    .border(m.strokeThin, PadBorder, RoundedCornerShape(m.buttonRadius))
                    .padding(m.cardPad * 0.85f)
            ) {
                Text(
                    text = PadStrings.termsDialogBody(lang),
                    color = PadInk,
                    fontSize = m.captionFont
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(m.buttonRadius))
                    .background(PadSurface2)
                    .border(m.strokeThin, PadCyan, RoundedCornerShape(m.buttonRadius))
                    .clickable(onClick = onOpenFullWeb)
                    .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.65f)
                    .testTag("pad_terms_open_web_btn"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = PadStrings.termsViewFullWebBtn(lang),
                    color = PadCyan,
                    fontSize = m.bodyFont,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            if (requireAgreement) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.itemGap * 0.75f)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(m.buttonRadius))
                            .background(PadSurface2)
                            .border(m.strokeThin, PadDanger, RoundedCornerShape(m.buttonRadius))
                            .clickable(onClick = onDeclineExit)
                            .padding(horizontal = m.cardPad * 0.65f, vertical = m.cardPad * 0.75f)
                            .testTag("pad_terms_decline_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = PadStrings.termsDeclineBtn(lang),
                            color = PadDanger,
                            fontSize = m.bodyFont,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(m.buttonRadius))
                            .background(PadAccent)
                            .clickable(onClick = onAcceptOrClose)
                            .padding(horizontal = m.cardPad * 0.65f, vertical = m.cardPad * 0.75f)
                            .testTag("pad_terms_agree_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = PadStrings.termsAgreeBtn(lang),
                            color = PadOnAccent,
                            fontSize = m.bodyFont,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(m.buttonRadius))
                        .background(PadAccent)
                        .clickable(onClick = onAcceptOrClose)
                        .padding(horizontal = m.cardPad * 0.7f, vertical = m.cardPad * 0.75f)
                        .testTag("pad_terms_close_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = PadStrings.closeBtn(lang),
                        color = PadOnAccent,
                        fontSize = m.bodyFont,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
