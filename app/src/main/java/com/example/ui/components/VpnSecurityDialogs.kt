package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.ui.theme.JapanRed
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XboxCardBorder
import com.example.vpn.VpnSecurityAlert
import com.example.vpn.VpnSecurityViolationType

/**
 * Hộp thoại thông báo Cầu dao bảo mật (Kill-Switch) khi Proxy trong app phát sinh lỗi SSL
 * hoặc cố chuyển hướng ra ngoài phạm vi tin cậy và đã bị chặn + ngắt ngay lập tức.
 */
@Composable
fun VpnSecurityKillSwitchDialog(
    alert: VpnSecurityAlert,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.vpn_security_killswitch_title)
    val message = when (alert.violationType) {
        VpnSecurityViolationType.SSL_TAMPERING -> stringResource(
            R.string.vpn_security_killswitch_ssl_msg,
            alert.serverName,
            alert.detail
        )
        VpnSecurityViolationType.EXTERNAL_REDIRECT_OR_SCHEME -> stringResource(
            R.string.vpn_security_killswitch_external_msg,
            alert.serverName,
            alert.detail
        )
    }
    val okText = stringResource(R.string.vpn_security_killswitch_ok)
    val okFocusRequester = remember { FocusRequester() }

    AdaptivePopupFrame(
        onDismissRequest = onDismiss,
        dialogTestTag = "vpn_security_killswitch_dialog",
        defaultFocusRequester = okFocusRequester,
        borderColor = JapanRed,
        wideWidthFraction = 0.78f,
        portraitWidthFraction = 0.90f
    ) {
        val msgScroll = rememberScrollState()

        @Composable
        fun HeaderAndBody(modifier: Modifier = Modifier) {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(gapMed)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gapMed)
                ) {
                    Box(
                        modifier = Modifier
                            .size(iconBoxSize)
                            .clip(RoundedCornerShape(btnCorner))
                            .background(Color(0xFF331A19))
                            .border(borderThin, JapanRed, RoundedCornerShape(btnCorner)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GppBad,
                            contentDescription = null,
                            tint = JapanRed,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = rSp(15.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = winH * 0.48f)
                        .verticalScroll(msgScroll)
                ) {
                    Text(
                        text = message,
                        color = TextSecondary,
                        fontSize = rSp(12.5f),
                        lineHeight = rSp(17.5f)
                    )
                }
            }
        }

        @Composable
        fun DismissActionBtn(modifier: Modifier = Modifier) {
            AdaptiveFlatActionButton(
                onClick = onDismiss,
                isPrimary = true,
                gamepadHint = "A",
                containerColor = JapanRed,
                contentColor = Color(0xFF1F0606),
                borderColor = JapanRed,
                focusedContainerColor = JapanRed,
                focusedBorderColor = Color.White,
                focusRequester = okFocusRequester,
                modifier = modifier
                    .semantics { contentDescription = okText }
                    .testTag("vpn_security_killswitch_ok_btn")
            ) {
                Text(
                    text = okText,
                    color = Color(0xFF1F0606),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = rSp(12.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (useTwoColumnLayout) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapLarge),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderAndBody(modifier = Modifier.weight(1.25f))
                Box(
                    modifier = Modifier
                        .size(width = borderThin, height = rDp(84f))
                        .background(XboxCardBorder)
                )
                DismissActionBtn(modifier = Modifier.weight(0.85f))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(gapLarge)
            ) {
                HeaderAndBody(modifier = Modifier.fillMaxWidth())
                DismissActionBtn(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
