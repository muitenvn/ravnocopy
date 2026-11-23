package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudplay.KbmPrototypeStrings
import com.example.cloudplay.KeyboardMousePrototype
import com.example.data.AppConfig
import kotlin.math.hypot

private val PcKeyBg = Color(0xCC0D1511)
private val PcKeyBgPressed = Color(0xEE16352A)
private val PcKeyBorder = Color(0x669EB3A7)
private val PcAccentGreen = Color(0xFF27D884)
private val PcAccentCyan = Color(0xFF3FD8EE)
private val PcKeyText = Color(0xFFEAF2EE)
private val PcKeySubText = Color(0xFF9EB3A7)

/**
 * Bộ Bàn phím + Chuột ẢO trên màn hình cảm ứng dành riêng cho người chơi PC (Chế độ Hybrid Cách 3):
 * - Nửa trái: Cụm WASD trượt đa chạm (cần trái), Shift (L3), Ctrl (B), Q (LB), Tab (View), Esc (Menu), D-Pad.
 * - Nửa phải: Vùng rê chuột cảm ứng (Touchpad Look -> cần phải) + LMB (RT), RMB (LT), Space (A), R/E (X), F (Y), G (RB), V (R3), C (B).
 * - Phong cách tonal flat, không glow/blur, nhả nút đối xứng chống kẹt phím.
 */
@Composable
fun PcKeyboardMouseOverlay(
    config: AppConfig,
    modifier: Modifier = Modifier
) {
    val scale = config.gamepadScale.coerceIn(0.75f, 1.30f)
    val opacity = config.gamepadOpacity.coerceIn(0.30f, 0.95f)
    val keySize = (48f * scale).dp
    val smallKeySize = (40f * scale).dp
    val gap = (5f * scale).dp

    DisposableEffect(Unit) {
        onDispose {
            KeyboardMousePrototype.releaseAllAndUncapture("pc_touch_overlay_disposed")
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("pc_keyboard_mouse_overlay")
    ) {
        val isCompactPortrait = maxWidth < 540.dp

        // Vùng rê chuột cảm ứng nửa phải màn hình (Touchpad Look -> Cần phải R)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(maxWidth * 0.52f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var lastPos = down.position
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    break
                                }
                                val delta = change.position - lastPos
                                lastPos = change.position
                                if (delta.x != 0f || delta.y != 0f) {
                                    KeyboardMousePrototype.dispatchTouchTrackpadDelta(delta.x, delta.y)
                                }
                            }
                        } finally {
                            KeyboardMousePrototype.dispatchTouchTrackpadRelease()
                        }
                    }
                }
                .testTag("pc_touchpad_look_zone")
        ) {
            Text(
                text = KbmPrototypeStrings.touchLookAreaHint(config),
                color = PcKeySubText.copy(alpha = (opacity * 0.35f).coerceIn(0.12f, 0.35f)),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 54.dp, end = 16.dp)
            )
        }

        // ==========================================
        // CỤM PHÍM TRÁI: Esc, Tab, Q, D-Pad, WASD, Shift, Ctrl
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = if (isCompactPortrait) 10.dp else 18.dp,
                    bottom = if (isCompactPortrait) 14.dp else 18.dp
                )
                .alpha(opacity),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            // Hàng tiện ích PC trên cùng bên trái: Esc (Menu), Tab (View), Q (LB)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PcVirtualKeyCap(
                    label = "Esc",
                    subLabel = "Menu",
                    buttonIndex = 9,
                    width = smallKeySize * 1.15f,
                    height = smallKeySize * 0.85f,
                    tag = "pc_key_esc"
                )
                PcVirtualKeyCap(
                    label = "Tab",
                    subLabel = "View",
                    buttonIndex = 8,
                    width = smallKeySize * 1.15f,
                    height = smallKeySize * 0.85f,
                    tag = "pc_key_tab"
                )
                PcVirtualKeyCap(
                    label = "Q",
                    subLabel = "LB",
                    buttonIndex = 4,
                    width = smallKeySize,
                    height = smallKeySize * 0.85f,
                    accentBorder = PcAccentCyan,
                    tag = "pc_key_q"
                )
            }

            // Hàng D-Pad 4 hướng nhỏ gọn
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PcVirtualKeyCap(label = "←", subLabel = "", buttonIndex = 14, width = smallKeySize * 0.92f, height = smallKeySize * 0.78f, tag = "pc_key_dpad_left")
                PcVirtualKeyCap(label = "↑", subLabel = "", buttonIndex = 12, width = smallKeySize * 0.92f, height = smallKeySize * 0.78f, tag = "pc_key_dpad_up")
                PcVirtualKeyCap(label = "↓", subLabel = "", buttonIndex = 13, width = smallKeySize * 0.92f, height = smallKeySize * 0.78f, tag = "pc_key_dpad_down")
                PcVirtualKeyCap(label = "→", subLabel = "", buttonIndex = 15, width = smallKeySize * 0.92f, height = smallKeySize * 0.78f, tag = "pc_key_dpad_right")
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Cụm WASD chữ T ngược (hỗ trợ trượt ngón tay đi chéo) + Shift / Ctrl bên cạnh
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap + 2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                PcWasdTouchCluster(
                    keySize = keySize,
                    gap = gap
                )
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    PcVirtualKeyCap(
                        label = "Shift",
                        subLabel = "L3",
                        buttonIndex = 10,
                        width = keySize * 1.25f,
                        height = keySize,
                        accentBorder = PcAccentCyan,
                        tag = "pc_key_shift"
                    )
                    PcVirtualKeyCap(
                        label = "Ctrl",
                        subLabel = "B",
                        buttonIndex = 1,
                        width = keySize * 1.25f,
                        height = keySize,
                        tag = "pc_key_ctrl"
                    )
                }
            }
        }

        // ==========================================
        // CỤM PHẢI: Chuột Trái/Phải (LMB/RMB) + Phím hành động PC (R, E, F, G, V, C, Space)
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = if (isCompactPortrait) 10.dp else 18.dp,
                    bottom = if (isCompactPortrait) 14.dp else 18.dp
                )
                .alpha(opacity),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            // Hàng phím phụ PC: G (RB), F (Y), R (X), E (X)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PcVirtualKeyCap(
                    label = "G",
                    subLabel = "RB",
                    buttonIndex = 5,
                    width = smallKeySize,
                    height = smallKeySize,
                    accentBorder = PcAccentCyan,
                    tag = "pc_key_g"
                )
                PcVirtualKeyCap(
                    label = "F",
                    subLabel = "Y",
                    buttonIndex = 3,
                    width = smallKeySize,
                    height = smallKeySize,
                    tag = "pc_key_f"
                )
                PcVirtualKeyCap(
                    label = "R",
                    subLabel = "X",
                    buttonIndex = 2,
                    width = smallKeySize,
                    height = smallKeySize,
                    tag = "pc_key_r"
                )
                PcVirtualKeyCap(
                    label = "E",
                    subLabel = "X",
                    buttonIndex = 2,
                    width = smallKeySize,
                    height = smallKeySize,
                    tag = "pc_key_e"
                )
            }

            // Hàng chuột PC nổi bật: RMB (Ngắm LT) + LMB (Bắn RT)
            Row(horizontalArrangement = Arrangement.spacedBy(gap + 2.dp)) {
                PcVirtualKeyCap(
                    label = "RMB",
                    subLabel = "LT",
                    buttonIndex = 6,
                    width = keySize * 1.45f,
                    height = keySize * 1.12f,
                    accentBorder = PcAccentCyan,
                    tag = "pc_key_rmb"
                )
                PcVirtualKeyCap(
                    label = "LMB",
                    subLabel = "RT",
                    buttonIndex = 7,
                    width = keySize * 1.55f,
                    height = keySize * 1.12f,
                    accentBorder = PcAccentGreen,
                    tag = "pc_key_lmb"
                )
            }

            // Hàng dưới cùng: V (R3), C (B), Space (A - Nhảy)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PcVirtualKeyCap(
                    label = "V",
                    subLabel = "R3",
                    buttonIndex = 11,
                    width = smallKeySize,
                    height = keySize * 0.96f,
                    tag = "pc_key_v"
                )
                PcVirtualKeyCap(
                    label = "C",
                    subLabel = "B",
                    buttonIndex = 1,
                    width = smallKeySize,
                    height = keySize * 0.96f,
                    tag = "pc_key_c"
                )
                PcVirtualKeyCap(
                    label = "Space",
                    subLabel = "A",
                    buttonIndex = 0,
                    width = keySize * 1.95f,
                    height = keySize * 0.96f,
                    accentBorder = PcAccentGreen,
                    tag = "pc_key_space"
                )
            }
        }
    }
}

@Composable
private fun PcWasdTouchCluster(
    keySize: Dp,
    gap: Dp
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeW by remember { mutableStateOf(false) }
    var activeA by remember { mutableStateOf(false) }
    var activeS by remember { mutableStateOf(false) }
    var activeD by remember { mutableStateOf(false) }

    fun updateFromDirections(w: Boolean, a: Boolean, s: Boolean, d: Boolean) {
        activeW = w
        activeA = a
        activeS = s
        activeD = d
        var lx = 0f
        var ly = 0f
        if (a) lx -= 1f
        if (d) lx += 1f
        if (w) ly -= 1f
        if (s) ly += 1f
        val mag = hypot(lx.toDouble(), ly.toDouble()).toFloat()
        if (mag > 1f) {
            lx /= mag
            ly /= mag
        }
        KeyboardMousePrototype.dispatchVirtualPcLeftStick(lx, ly)
    }

    val totalW = keySize * 3 + gap * 2
    val totalH = keySize * 2 + gap

    Box(
        modifier = Modifier
            .size(width = totalW, height = totalH)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            var w = false
                            var a = false
                            var s = false
                            var d = false
                            val bw = boxSize.width.coerceAtLeast(1).toFloat()
                            val bh = boxSize.height.coerceAtLeast(1).toFloat()
                            var anyPressed = false
                            for (change in event.changes) {
                                if (!change.pressed) continue
                                anyPressed = true
                                change.consume()
                                val nx = ((change.position.x / bw) * 2f - 1f).coerceIn(-1f, 1f)
                                val ny = ((change.position.y / bh) * 2f - 1f).coerceIn(-1f, 1f)
                                if (ny < -0.18f) w = true
                                if (ny > 0.18f) s = true
                                if (nx < -0.24f) a = true
                                if (nx > 0.24f) d = true
                                // Nếu chạm gần tâm dưới (vị trí phím S) mà ny trong khoảng 0..0.18
                                if (!w && !s && !a && !d) {
                                    if (ny <= 0f) w = true else s = true
                                }
                            }
                            if (!anyPressed) {
                                if (activeW || activeA || activeS || activeD) {
                                    updateFromDirections(false, false, false, false)
                                }
                            } else {
                                updateFromDirections(w, a, s, d)
                            }
                        }
                    } finally {
                        updateFromDirections(false, false, false, false)
                    }
                }
            }
            .testTag("pc_wasd_cluster")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PcKeyVisualBox(label = "W", subLabel = "", pressed = activeW, width = keySize, height = keySize, accentBorder = PcAccentGreen)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                PcKeyVisualBox(label = "A", subLabel = "", pressed = activeA, width = keySize, height = keySize, accentBorder = PcAccentGreen)
                PcKeyVisualBox(label = "S", subLabel = "", pressed = activeS, width = keySize, height = keySize, accentBorder = PcAccentGreen)
                PcKeyVisualBox(label = "D", subLabel = "", pressed = activeD, width = keySize, height = keySize, accentBorder = PcAccentGreen)
            }
        }
    }
}

@Composable
private fun PcVirtualKeyCap(
    label: String,
    subLabel: String,
    buttonIndex: Int,
    width: Dp,
    height: Dp,
    accentBorder: Color = PcKeyBorder,
    tag: String
) {
    var pressed by remember { mutableStateOf(false) }

    DisposableEffect(buttonIndex) {
        onDispose {
            if (pressed) {
                pressed = false
                KeyboardMousePrototype.dispatchVirtualPcButton(buttonIndex, false)
            }
        }
    }

    Box(
        modifier = Modifier
            .semantics { contentDescription = if (subLabel.isNotBlank()) "$label ($subLabel)" else label }
            .testTag(tag)
            .pointerInput(buttonIndex) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    pressed = true
                    KeyboardMousePrototype.dispatchVirtualPcButton(buttonIndex, true, label)
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val ch = event.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) break
                            ch.consume()
                        }
                    } finally {
                        if (pressed) {
                            pressed = false
                            KeyboardMousePrototype.dispatchVirtualPcButton(buttonIndex, false)
                        }
                    }
                }
            }
    ) {
        PcKeyVisualBox(
            label = label,
            subLabel = subLabel,
            pressed = pressed,
            width = width,
            height = height,
            accentBorder = accentBorder
        )
    }
}

@Composable
private fun PcKeyVisualBox(
    label: String,
    subLabel: String,
    pressed: Boolean,
    width: Dp,
    height: Dp,
    accentBorder: Color
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(if (pressed) PcKeyBgPressed else PcKeyBg)
            .border(
                width = if (pressed) 1.5.dp else 1.dp,
                color = if (pressed) PcAccentGreen else accentBorder,
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                color = if (pressed) PcAccentGreen else PcKeyText,
                fontSize = if (label.length >= 4) 11.5.sp else 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    color = PcKeySubText,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
