package com.example.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.data.SettingsRepository
import com.example.ui.components.AdaptiveFlatActionButton
import com.example.ui.components.AdaptivePopupFrame
import com.example.ui.components.AdaptivePopupScope
import com.example.ui.components.AdaptiveTwoColumnLayout
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import com.example.vpn.EndpointPingMeasurement
import com.example.vpn.RegionUnlockStatus
import com.example.vpn.ServerLocation

@Composable
internal fun ServerDetailDialog(
    server: ServerLocation,
    measurement: EndpointPingMeasurement?,
    pingMs: Int,
    unlockStatus: RegionUnlockStatus,
    isEn: Boolean,
    onDismiss: () -> Unit
) {
    val config by SettingsRepository.config.collectAsState()
    val closeBtnFocusRequester = remember { FocusRequester() }
    val detailBadge = measurement?.formatDisplayBadge(isEn = isEn, showPort = true)
        ?: if (pingMs > 0) "${pingMs}ms (:${server.port})" else "${config.tr("Chưa đo", "Not tested")} (:${server.port})"
    val unlockText = when (unlockStatus) {
        RegionUnlockStatus.UNLOCKED -> config.tr("Đã mở khóa", "Unlocked")
        RegionUnlockStatus.FAILED -> config.tr("Không mở khóa", "Unlock Failed")
        RegionUnlockStatus.UNTESTED -> config.tr("Chưa thử mở khóa", "Untested")
        RegionUnlockStatus.WRONG_REGION -> config.tr("Sai vùng", "Wrong Region")
    }

    AdaptivePopupFrame(
        onDismissRequest = onDismiss,
        dialogTestTag = "server_detail_dialog",
        defaultFocusRequester = closeBtnFocusRequester,
        wideWidthFraction = 0.78f,
        portraitWidthFraction = 0.90f
    ) {
        val infoScroll = rememberScrollState()

        @Composable
        fun AdaptivePopupScope.ServerInfoSection(maxInfoHeight: Dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxInfoHeight)
                    .verticalScroll(infoScroll),
                verticalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                Text(
                    text = "${server.flag} ${server.name}",
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = rSp(16f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
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
                        Text(
                            text = "${if (isEn) "Address & Port" else "Địa chỉ & Cổng"}: ${server.ip}:${server.port} (${server.protocol.uppercase()})",
                            color = TextPrimary,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = rSp(11.8f)
                        )
                        Text(
                            text = "${if (isEn) "Measured Latency" else "Độ trễ đo thực tế"}: $detailBadge",
                            color = XboxNeonGreen,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = rSp(12f),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${if (isEn) "Region Unlock Status" else "Trạng thái mở khóa vùng"}: $unlockText",
                            color = TextSecondary,
                            fontSize = rSp(11.8f)
                        )
                        Text(
                            text = "${if (isEn) "Check Status" else "Trạng thái kiểm tra"}: ${server.localizedSafetyBadge(isEn)}",
                            color = TextSecondary,
                            fontSize = rSp(11.8f)
                        )
                        Text(
                            text = "${if (isEn) "Description" else "Mô tả"}: ${server.localizedDescription(isEn)}",
                            color = TextSecondary,
                            fontSize = rSp(11.8f)
                        )
                    }
                }
            }
        }

        @Composable
        fun AdaptivePopupScope.CloseButtonPanel() {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(gapMed),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AdaptiveFlatActionButton(
                    onClick = onDismiss,
                    isPrimary = true,
                    gamepadHint = "B",
                    focusRequester = closeBtnFocusRequester,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = config.tr("Đóng", "Close"),
                        color = Color(0xFF03140B),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = rSp(12.5f)
                    )
                }
            }
        }

        AdaptiveTwoColumnLayout(
            leftWeight = 1.25f,
            rightWeight = 0.75f,
            leftColumn = {
                ServerInfoSection(maxInfoHeight = winH * 0.74f)
            },
            rightColumn = {
                CloseButtonPanel()
            },
            portraitColumn = {
                ServerInfoSection(maxInfoHeight = winH * 0.62f)
                CloseButtonPanel()
            }
        )
    }
}
