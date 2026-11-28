package com.muitenvn.cloudplay.pad

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Bảng màu tonal flat đồng bộ 100% với app chính cho các pop-up trong module Ravno Pad
 * (không phụ thuộc vào module app).
 */
internal object PadPopupColors {
    val Surface = Color(0xFF0D1511)
    val Surface2 = Color(0xFF142019)
    val Selected = Color(0xFF16352A)
    val Accent = Color(0xFF27D884)
    val OnAccent = Color(0xFF07140D)
    val Danger = Color(0xFFF0706B)
    val OnDanger = Color(0xFF1A0706)
    val Text = Color(0xFFE6EFE9)
    val Muted = Color(0xFF9FB3A8)
    val Line = Color(0xFF1D2F26)
    val KeyCapBorder = Color(0xFF2A3B32)
    val Scrim = Color(0xA8040806)
}

internal enum class PadPopupButtonVariant {
    PRIMARY,
    SECONDARY,
    DANGER
}

@Immutable
internal class PadPopupScope(
    val winW: Dp,
    val winH: Dp,
    val hasHardwareGamepad: Boolean,
    private val density: Density
) {
    private val shortEdge: Dp = if (winW < winH) winW else winH
    private val dpFactor: Float = (shortEdge.value / 360f).coerceIn(0.92f, 1.18f)
    private val baseUnit: Dp = Dp(dpFactor)
    private val fontScaleComp: Float =
        if (density.fontScale > 1.15f) (1.15f / density.fontScale).coerceIn(0.75f, 1f) else 1f
    private val spFactor: Float = dpFactor * fontScaleComp

    fun rDp(refPx: Float): Dp = baseUnit * refPx
    fun rSp(refSp: Float): TextUnit = with(density) { (refSp * spFactor).sp }
}

private fun hasExternalPadOrDpadNow(): Boolean = runCatching {
    InputDevice.getDeviceIds().any { id ->
        val dev = InputDevice.getDevice(id) ?: return@any false
        if (dev.isVirtual) return@any false
        val src = dev.sources
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }
}.getOrDefault(false)

@Composable
internal fun PadPopupScope.PadPopupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PadPopupButtonVariant = PadPopupButtonVariant.SECONDARY,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    testTag: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val showFocusRing = isFocused && hasHardwareGamepad && enabled

    val minBtnHeight = rDp(44f)
    val focusStroke = rDp(2f)
    val focusOffsetGap = rDp(2.5f)
    val innerCorner = rDp(12f)
    val outerCorner = innerCorner + focusOffsetGap + focusStroke * 0.5f

    val bgColor = when {
        !enabled -> PadPopupColors.Surface2.copy(alpha = 0.45f)
        variant == PadPopupButtonVariant.PRIMARY -> PadPopupColors.Accent
        variant == PadPopupButtonVariant.DANGER -> PadPopupColors.Danger
        showFocusRing -> PadPopupColors.Selected
        else -> PadPopupColors.Surface2
    }
    val textColor = when {
        !enabled -> PadPopupColors.Muted.copy(alpha = 0.6f)
        variant == PadPopupButtonVariant.PRIMARY -> PadPopupColors.OnAccent
        variant == PadPopupButtonVariant.DANGER -> PadPopupColors.OnDanger
        else -> PadPopupColors.Text
    }
    val focusRingColor = when {
        !showFocusRing -> Color.Transparent
        variant == PadPopupButtonVariant.DANGER -> PadPopupColors.Danger
        else -> PadPopupColors.Accent
    }
    val rippleColor = when (variant) {
        PadPopupButtonVariant.PRIMARY -> PadPopupColors.OnAccent
        PadPopupButtonVariant.DANGER -> PadPopupColors.OnDanger
        PadPopupButtonVariant.SECONDARY -> PadPopupColors.Accent
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
                        onClick()
                        true
                    }
                    isConfirm && event.type == KeyEventType.KeyDown -> true
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
            .semantics { contentDescription = text }
            .then(if (!testTag.isNullOrBlank()) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = if (variant == PadPopupButtonVariant.SECONDARY) FontWeight.SemiBold else FontWeight.Bold,
            fontSize = rSp(13.5f),
            lineHeight = rSp(17f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PadPopupScope.PadPopupTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = PadPopupColors.Text,
        fontWeight = FontWeight.SemiBold,
        fontSize = rSp(17.5f),
        lineHeight = rSp(22.5f),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
internal fun PadPopupScope.PadPopupBody(
    text: String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = winH * 0.42f)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = text,
            color = PadPopupColors.Muted,
            fontSize = rSp(13.5f),
            lineHeight = rSp(19.5f)
        )
    }
}

@Composable
internal fun PadPopupScope.PadPopupHintBar(
    selectLabel: String,
    backLabel: String,
    modifier: Modifier = Modifier
) {
    if (!hasHardwareGamepad) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(rDp(14f), Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PadKeyCapHint(keyLabel = "A", actionLabel = selectLabel)
        PadKeyCapHint(keyLabel = "B", actionLabel = backLabel)
    }
}

@Composable
private fun PadPopupScope.PadKeyCapHint(
    keyLabel: String,
    actionLabel: String
) {
    val capCorner = rDp(6f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(rDp(6f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(capCorner))
                .background(PadPopupColors.Surface2)
                .border(rDp(1.1f), PadPopupColors.KeyCapBorder, RoundedCornerShape(capCorner))
                .padding(horizontal = rDp(7f), vertical = rDp(2.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = keyLabel,
                color = PadPopupColors.Text,
                style = TextStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = rSp(10.5f),
                    lineHeight = rSp(12.5f),
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                maxLines = 1
            )
        }
        Text(
            text = actionLabel,
            color = PadPopupColors.Muted,
            fontWeight = FontWeight.Medium,
            fontSize = rSp(11.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Mẫu 1 (Hộp thoại xác nhận) cho module Ravno Pad:
 * - Nền phẳng #0d1511, viền 1 điểm #1d2f26, phủ tối phẳng, không đổ bóng/không làm mờ.
 * - Nút Hủy (phụ) được chọn sẵn, nút Xác nhận (nguy hiểm đỏ hoặc chính xanh).
 * - Màn nhỏ tự xếp dọc; nội dung dài tự cuộn trong hộp; B / Back = Hủy.
 */
@Composable
internal fun PadConfirmPopupDialog(
    lang: PadLanguage,
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    confirmVariant: PadPopupButtonVariant = PadPopupButtonVariant.DANGER,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dialogTestTag: String? = null,
    confirmButtonTestTag: String? = null,
    dismissButtonTestTag: String? = null
) {
    val cancelFocusRequester = remember { FocusRequester() }
    val hasGamepad = remember { hasExternalPadOrDpadNow() }

    LaunchedEffect(hasGamepad) {
        if (hasGamepad) {
            delay(50)
            runCatching { cancelFocusRequester.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    val code = event.nativeKeyEvent.keyCode
                    val isBack = code == KeyEvent.KEYCODE_BUTTON_B ||
                        code == KeyEvent.KEYCODE_BACK ||
                        code == KeyEvent.KEYCODE_ESCAPE
                    if (isBack && event.type == KeyEventType.KeyUp) {
                        onDismiss()
                        true
                    } else if (isBack && event.type == KeyEventType.KeyDown) {
                        true
                    } else {
                        false
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PadPopupColors.Scrim)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    }
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.Center
            ) {
                val density = LocalDensity.current
                val scope = remember(maxWidth, maxHeight, hasGamepad, density) {
                    PadPopupScope(
                        winW = maxWidth,
                        winH = maxHeight,
                        hasHardwareGamepad = hasGamepad,
                        density = density
                    )
                }

                with(scope) {
                    val corner = rDp(20f)
                    val isNarrow = winW < rDp(320f) || (confirmText.length + dismissText.length > 26 && winW < rDp(380f))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .widthIn(max = rDp(400f))
                            .heightIn(max = winH * 0.90f)
                            .clip(RoundedCornerShape(corner))
                            .background(PadPopupColors.Surface)
                            .border(rDp(1f), PadPopupColors.Line, RoundedCornerShape(corner))
                            .pointerInput(Unit) {}
                            .padding(rDp(20f))
                            .then(if (!dialogTestTag.isNullOrBlank()) Modifier.testTag(dialogTestTag) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(rDp(14f))
                    ) {
                        PadPopupTitle(text = title)
                        PadPopupBody(text = message)

                        if (isNarrow) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(rDp(8f))
                            ) {
                                PadPopupButton(
                                    text = dismissText,
                                    onClick = onDismiss,
                                    variant = PadPopupButtonVariant.SECONDARY,
                                    focusRequester = cancelFocusRequester,
                                    testTag = dismissButtonTestTag,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                PadPopupButton(
                                    text = confirmText,
                                    onClick = onConfirm,
                                    variant = confirmVariant,
                                    testTag = confirmButtonTestTag,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(rDp(8f))
                            ) {
                                PadPopupButton(
                                    text = dismissText,
                                    onClick = onDismiss,
                                    variant = PadPopupButtonVariant.SECONDARY,
                                    focusRequester = cancelFocusRequester,
                                    testTag = dismissButtonTestTag,
                                    modifier = Modifier.weight(1f)
                                )
                                PadPopupButton(
                                    text = confirmText,
                                    onClick = onConfirm,
                                    variant = confirmVariant,
                                    testTag = confirmButtonTestTag,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        PadPopupHintBar(
                            selectLabel = PadStrings.popupHintSelect(lang),
                            backLabel = PadStrings.popupHintBack(lang)
                        )
                    }
                }
            }
        }
    }
}
