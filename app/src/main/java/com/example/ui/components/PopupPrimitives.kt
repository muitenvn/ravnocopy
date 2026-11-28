package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.R
import com.example.ui.theme.SpaceGroteskFontFamily

/**
 * Bảng màu tonal flat chuẩn cho toàn bộ hệ pop-up Ravno (không đổ bóng, không làm mờ, không phát sáng).
 */
object PopupColors {
    val Background = Color(0xFF070B09)
    val Surface = Color(0xFF0D1511)
    val Surface2 = Color(0xFF142019)
    val Selected = Color(0xFF16352A)
    val Accent = Color(0xFF27D884)
    val OnAccent = Color(0xFF07140D)
    val Cyan = Color(0xFF3FD8EE)
    val Warn = Color(0xFFE3B341)
    val Danger = Color(0xFFF0706B)
    val OnDanger = Color(0xFF1A0706)
    val Text = Color(0xFFE6EFE9)
    val Muted = Color(0xFF9FB3A8)
    val Line = Color(0xFF1D2F26)
    val KeyCapBorder = Color(0xFF2A3B32)
    val Scrim = Color(0xA8040806)
}

enum class PopupButtonVariant {
    PRIMARY,
    SECONDARY,
    DANGER
}

/**
 * Nút chuẩn dùng chung cho mọi pop-up:
 * - 3 kiểu: PRIMARY (accent #27d884, chữ tối), SECONDARY (surface2 #142019), DANGER (#f0706b, chữ tối).
 * - Cao tối thiểu 44 (TV 52) theo hệ co giãn AdaptivePopupScope.
 * - Viền focus phẳng 2 điểm (TV 3 điểm) màu accent (nút nguy hiểm dùng viền màu đỏ), có khoảng hở tinh gọn.
 * - Điều khiển đầy đủ bằng D-Pad / tay cầm (A / Enter / DPad Center) và chạm cảm ứng.
 */
@Composable
fun AdaptivePopupScope.PopupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PopupButtonVariant = PopupButtonVariant.SECONDARY,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    contentDescriptionOverride: String? = null,
    testTag: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val showFocusRing = isFocused && isGamepadDetected && enabled

    val minBtnHeight = rDp(if (isTvUiMode) 52f else 44f)
    val focusStroke = rDp(if (isTvUiMode) 3f else 2f)
    val focusOffsetGap = rDp(2.5f)
    val innerCorner = rDp(12f)
    val outerCorner = innerCorner + focusOffsetGap + focusStroke * 0.5f

    val bgColor = when {
        !enabled -> PopupColors.Surface2.copy(alpha = 0.45f)
        variant == PopupButtonVariant.PRIMARY -> PopupColors.Accent
        variant == PopupButtonVariant.DANGER -> PopupColors.Danger
        showFocusRing -> PopupColors.Selected
        else -> PopupColors.Surface2
    }
    val textColor = when {
        !enabled -> PopupColors.Muted.copy(alpha = 0.6f)
        variant == PopupButtonVariant.PRIMARY -> PopupColors.OnAccent
        variant == PopupButtonVariant.DANGER -> PopupColors.OnDanger
        else -> PopupColors.Text
    }
    val focusRingColor = when {
        !showFocusRing -> Color.Transparent
        variant == PopupButtonVariant.DANGER -> PopupColors.Danger
        else -> PopupColors.Accent
    }
    val rippleColor = when (variant) {
        PopupButtonVariant.PRIMARY -> PopupColors.OnAccent
        PopupButtonVariant.DANGER -> PopupColors.OnDanger
        PopupButtonVariant.SECONDARY -> PopupColors.Accent
    }

    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                val isConfirm = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_BUTTON_A ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                when {
                    isConfirm && event.type == KeyEventType.KeyUp -> {
                        GamepadUiNavigation.onGamepadInteraction()
                        onClick()
                        true
                    }
                    isConfirm && event.type == KeyEventType.KeyDown -> {
                        GamepadUiNavigation.onGamepadInteraction()
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .border(focusStroke, focusRingColor, RoundedCornerShape(outerCorner))
            .padding(focusOffsetGap)
            .clip(RoundedCornerShape(innerCorner))
            .background(bgColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(color = rippleColor),
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = minBtnHeight)
            .padding(horizontal = rDp(14f), vertical = rDp(8f))
            .then(
                if (!contentDescriptionOverride.isNullOrBlank()) {
                    Modifier.semantics { contentDescription = contentDescriptionOverride }
                } else Modifier
            )
            .then(if (!testTag.isNullOrBlank()) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(rDp(6f)))
            }
            Text(
                text = text,
                color = textColor,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = if (variant == PopupButtonVariant.SECONDARY) FontWeight.SemiBold else FontWeight.Bold,
                fontSize = rSp(if (isTvUiMode) 14.5f else 13.5f),
                lineHeight = rSp(if (isTvUiMode) 18.5f else 17f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Tiêu đề chuẩn của pop-up (Space Grotesk đậm, màu sáng).
 */
@Composable
fun AdaptivePopupScope.PopupTitle(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 3
) {
    Text(
        text = text,
        color = PopupColors.Text,
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = rSp(if (isTvUiMode) 19f else 17.5f),
        lineHeight = rSp(if (isTvUiMode) 24f else 22.5f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * Nội dung mô tả chuẩn của pop-up (màu muted, tự cuộn bên trong khi dài để không đẩy nút ra ngoài màn).
 */
@Composable
fun AdaptivePopupScope.PopupBody(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = winH * 0.42f
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = text,
            color = PopupColors.Muted,
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = rSp(if (isTvUiMode) 14.5f else 13.5f),
            lineHeight = rSp(if (isTvUiMode) 21f else 19.5f)
        )
    }
}

/**
 * Thanh gợi ý nút tay cầm A / B cho pop-up.
 * Chỉ hiển thị trên TV hoặc khi phát hiện tay cầm (ẩn hoàn toàn trên điện thoại cảm ứng thuần).
 */
@Composable
fun AdaptivePopupScope.PopupHintBar(
    modifier: Modifier = Modifier,
    selectLabel: String = stringResource(R.string.popup_hint_select),
    backLabel: String = stringResource(R.string.popup_hint_back)
) {
    if (!isGamepadDetected) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(rDp(14f), Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PopupKeyCapHint(keyLabel = "A", actionLabel = selectLabel)
        PopupKeyCapHint(keyLabel = "B", actionLabel = backLabel)
    }
}

@Composable
private fun AdaptivePopupScope.PopupKeyCapHint(
    keyLabel: String,
    actionLabel: String
) {
    val capCorner = rDp(6f)
    val fontSize = rSp(if (isTvUiMode) 12.5f else 11.5f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(rDp(6f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(capCorner))
                .background(PopupColors.Surface2)
                .border(rDp(1.1f), PopupColors.KeyCapBorder, RoundedCornerShape(capCorner))
                .padding(horizontal = rDp(7f), vertical = rDp(2.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = keyLabel,
                color = PopupColors.Text,
                style = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = rSp(if (isTvUiMode) 11.5f else 10.5f),
                    lineHeight = rSp(if (isTvUiMode) 13.5f else 12.5f),
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
        }
        Text(
            text = actionLabel,
            color = PopupColors.Muted,
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
