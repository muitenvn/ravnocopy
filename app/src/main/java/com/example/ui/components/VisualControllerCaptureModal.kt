package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.cloudplay.CapturedPhysicalInput
import com.example.cloudplay.PhysicalGamepadCaptureBus
import com.example.data.AppConfig
import com.example.data.SettingsRepository
import com.example.ui.theme.DirectCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import com.example.ui.theme.XboxSurfaceVariant
import kotlinx.coroutines.delay

/**
 * Hộp thoại con khi người dùng chọn 1 nút hoặc 1 hướng cần (L↑/L↓/L←/L→, R↑/R↓/R←/R→, A/B/X/Y, ...)
 * trên sơ đồ tay cầm trực quan:
 * - Bắt BẤT KỲ phím vật lý nào: nút tay cầm, gạt cần analog, bàn phím Bluetooth/USB,
 *   điều khiển TV, phím âm lượng hoặc tay cầm phụ.
 * - Hỗ trợ công tắc "Hoán đổi 2 chiều" cho nút/cần tay cầm (giúp đảo hướng cần L↑ ↔ L↓ tức thì).
 * - Hỗ trợ chọn thủ công cả 25 tín hiệu Xbox (8 hướng cần + 17 nút).
 */
@Composable
internal fun VisualControllerSlotBindDialog(
    sourceCode: Int,
    config: AppConfig,
    currentMap: Map<Int, Int>,
    customKeyMap: Map<Int, Int>,
    onApplyStandardMapping: (targetCode: Int, swapWithExisting: Boolean) -> Unit,
    onBindCustomHardwareKey: (androidKeyCode: Int) -> Unit,
    onClearCustomKeysForSlot: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEn = config.isSettingsEnglishUI()
    val srcFullLabel = SettingsRepository.getGamepadButtonLabel(sourceCode, isEn)
    val srcShortLabel = SettingsRepository.getGamepadButtonShortLabel(sourceCode)
    val currentDest = currentMap[sourceCode] ?: sourceCode
    val currentDestShort = SettingsRepository.getGamepadButtonShortLabel(currentDest)
    val boundCustomKeys = remember(customKeyMap, sourceCode) {
        customKeyMap.entries
            .filter { it.value == sourceCode }
            .map { it.key }
            .sorted()
    }

    var swapPairEnabled by remember(sourceCode) { mutableStateOf(true) }
    val isCapturing by PhysicalGamepadCaptureBus.isCapturing.collectAsState()
    var countdownSec by remember(sourceCode) { mutableIntStateOf(0) }
    val captureBtnFocus = remember { FocusRequester() }

    fun startCapture() {
        countdownSec = 8
        PhysicalGamepadCaptureBus.armCapture(
            onCaptured = { captured ->
                countdownSec = 0
                when (captured) {
                    is CapturedPhysicalInput.StandardGamepad -> {
                        onApplyStandardMapping(captured.code, swapPairEnabled)
                    }
                    is CapturedPhysicalInput.CustomHardwareKey -> {
                        onBindCustomHardwareKey(captured.androidKeyCode)
                    }
                }
                onDismiss()
            },
            onCancel = {
                countdownSec = 0
            }
        )
    }

    fun stopCapture() {
        countdownSec = 0
        PhysicalGamepadCaptureBus.disarmCapture()
    }

    // Nếu mở bằng cảm ứng -> tự động chờ bắt bất kỳ phím vật lý nào ngay; nếu mở bằng D-Pad/TV -> bấm A vào nút "Bắt phím" để tránh tự bắt nút A
    LaunchedEffect(sourceCode) {
        if (!GamepadUiNavigation.isGamepadModeActive && !GamepadUiNavigation.keepFocusVisibleInTvMode) {
            startCapture()
        }
    }

    LaunchedEffect(isCapturing) {
        if (!isCapturing) {
            countdownSec = 0
            return@LaunchedEffect
        }
        var remaining = 8
        countdownSec = remaining
        while (remaining > 0 && PhysicalGamepadCaptureBus.isCapturing.value) {
            delay(1000L)
            remaining--
            countdownSec = remaining
        }
        if (PhysicalGamepadCaptureBus.isCapturing.value) {
            PhysicalGamepadCaptureBus.disarmCapture()
        }
    }

    DisposableEffect(sourceCode) {
        onDispose {
            PhysicalGamepadCaptureBus.disarmCapture()
        }
    }

    AdaptivePopupFrame(
        onDismissRequest = {
            if (isCapturing) {
                stopCapture()
            } else {
                onDismiss()
            }
        },
        dialogTestTag = "visual_controller_slot_bind_dialog",
        defaultFocusRequester = captureBtnFocus,
        wideWidthFraction = 0.88f,
        portraitWidthFraction = 0.94f
    ) {
        val gridScroll = rememberScrollState()
        val columnsPerRow = 4

        @Composable
        fun HeaderAndCaptureControls() {
            Column(verticalArrangement = Arrangement.spacedBy(gapSmall)) {
                Text(
                    text = "${config.tr("Gán nút hoặc phím bất kỳ", "Bind Any Key or Button")}: $srcFullLabel",
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = rSp(14.5f)
                )

                val statusText = buildString {
                    append("${config.tr("Hiện tại", "Current")}: ")
                    if (currentDest == sourceCode) {
                        append("$srcShortLabel (${config.tr("Mặc định", "Default")})")
                    } else {
                        append("$srcShortLabel → $currentDestShort")
                    }
                    if (boundCustomKeys.isNotEmpty()) {
                        val keysText = boundCustomKeys.joinToString(", ") {
                            SettingsRepository.getHardwareKeyShortLabel(it)
                        }
                        append(" · ${config.tr("Phím ngoài", "Ext Key")}: $keysText")
                    }
                }
                Text(
                    text = statusText,
                    color = if (currentDest == sourceCode && boundCustomKeys.isEmpty()) TextSecondary else XboxNeonGreen,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = rSp(11f)
                )

                // Khung chờ bắt BẤT KỲ phím cứng / nút tay cầm / cần gạt nào
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCapturing) Color(0xFF10313A) else XboxSurface,
                    border = BorderStroke(
                        if (isCapturing) 1.5.dp else 1.dp,
                        if (isCapturing) DirectCyan else XboxCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isCapturing) {
                                "${config.tr("Hãy bấm bất kỳ phím nào (tay cầm, bàn phím, điều khiển TV, phím âm lượng...) hoặc gạt cần...", "Press any button/key (gamepad, keyboard, remote, volume...) or push a stick...")} (${countdownSec}s)"
                            } else {
                                config.tr(
                                    "Nhấn nút bên dưới rồi bấm bất kỳ phím cứng nào (tay cầm, bàn phím, điều khiển, phím âm lượng) hoặc gạt cần để gán ngay.",
                                    "Press below, then press any physical key (gamepad, keyboard, remote, volume) or push a stick."
                                )
                            },
                            color = if (isCapturing) DirectCyan else TextSecondary,
                            fontSize = rSp(10.8f),
                            fontWeight = if (isCapturing) FontWeight.Bold else FontWeight.Medium,
                            lineHeight = rSp(14.5f)
                        )

                        AdaptiveFlatActionButton(
                            onClick = { if (isCapturing) stopCapture() else startCapture() },
                            isPrimary = false,
                            containerColor = if (isCapturing) Color(0xFF2B1818) else Color(0xFF16352A),
                            contentColor = if (isCapturing) Color(0xFFF0706B) else XboxNeonGreen,
                            borderColor = if (isCapturing) Color(0xFFF0706B) else XboxNeonGreen,
                            focusedContainerColor = if (isCapturing) Color(0xFF3E2020) else Color(0xFF1F4B3B),
                            focusedBorderColor = Color.White,
                            focusRequester = captureBtnFocus,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = if (isCapturing) {
                                        config.tr("Hủy chờ bắt phím tay cầm", "Cancel controller capture")
                                    } else {
                                        config.tr("Bắt bất kỳ phím cứng hoặc hướng cần nào", "Capture any physical key or stick")
                                    }
                                }
                                .testTag("visual_bind_capture_toggle_btn")
                        ) {
                            Text(
                                text = if (isCapturing) {
                                    config.tr("Hủy chờ bấm (Back)", "Cancel Capture (Back)")
                                } else {
                                    config.tr("Bấm bất kỳ phím / nút / gạt cần nào", "Press Any Key / Button / Stick")
                                },
                                color = if (isCapturing) Color(0xFFF0706B) else XboxNeonGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = rSp(11.3f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Công tắc Hoán đổi 2 chiều (cho tay cầm: gán L↑ → L↓ sẽ tự đổi L↓ → L↑)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (swapPairEnabled) Color(0xFF16352A) else XboxSurface,
                    border = BorderStroke(1.dp, if (swapPairEnabled) XboxNeonGreen else XboxCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .gamepadClickable(cornerRadius = 10.dp) {
                            swapPairEnabled = !swapPairEnabled
                        }
                        .semantics {
                            contentDescription = config.tr("Tự hoán đổi 2 chiều khi đổi nút", "Auto swap pair when binding")
                        }
                        .testTag("visual_bind_swap_pair_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = config.tr("Tự hoán đổi 2 chiều (A ↔ B)", "Auto Swap Pair (A ↔ B)"),
                                color = if (swapPairEnabled) XboxNeonGreen else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = rSp(11.3f)
                            )
                            Text(
                                text = config.tr(
                                    "Bật để đảo trục ngược mượt (gán L↑ → L↓ sẽ tự đổi L↓ → L↑). Tắt để gán tự do trùng đích.",
                                    "On: cleanly swaps opposite direction/button. Off: allows mapping multiple inputs freely."
                                ),
                                color = TextSecondary,
                                fontSize = rSp(9.8f),
                                lineHeight = rSp(13.2f)
                            )
                        }
                        Text(
                            text = if (swapPairEnabled) config.tr("BẬT", "ON") else config.tr("TẮT", "OFF"),
                            color = if (swapPairEnabled) XboxNeonGreen else TextSecondary,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = rSp(11f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gapSmall)
                ) {
                    AdaptiveFlatActionButton(
                        onClick = {
                            stopCapture()
                            onClearCustomKeysForSlot()
                            onApplyStandardMapping(sourceCode, swapPairEnabled)
                            onDismiss()
                        },
                        isPrimary = false,
                        containerColor = XboxSurfaceVariant,
                        borderColor = XboxCardBorder,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("visual_bind_reset_single_btn")
                    ) {
                        Text(
                            text = config.tr("Về mặc định", "Default"),
                            color = DirectCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(11f),
                            maxLines = 1
                        )
                    }
                    AdaptiveFlatActionButton(
                        onClick = {
                            stopCapture()
                            onDismiss()
                        },
                        isPrimary = false,
                        gamepadHint = "B",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("visual_bind_close_btn")
                    ) {
                        Text(
                            text = config.tr("Đóng", "Close"),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(11f),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        @Composable
        fun TargetPickerGrid(maxGridHeight: Dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxGridHeight)
                    .verticalScroll(gridScroll),
                verticalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                Text(
                    text = config.tr("Hoặc chọn thủ công hướng cần (Analog L / R)", "Or pick Stick Direction (Analog L / R)"),
                    color = DirectCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = rSp(11f)
                )
                SettingsRepository.PHYSICAL_GAMEPAD_STICK_DIRECTIONS.chunked(columnsPerRow).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { (dstCode, dstShort, _) ->
                            val selected = dstCode == currentDest
                            AdaptiveFlatActionButton(
                                onClick = {
                                    stopCapture()
                                    onApplyStandardMapping(dstCode, swapPairEnabled)
                                    onDismiss()
                                },
                                isPrimary = selected,
                                containerColor = if (selected) Color(0xFF16352A) else XboxSurface,
                                contentColor = if (selected) XboxNeonGreen else TextPrimary,
                                borderColor = if (selected) XboxNeonGreen else XboxCardBorder,
                                focusedBorderColor = Color.White,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("visual_bind_pick_$dstCode")
                            ) {
                                Text(
                                    text = dstShort,
                                    color = if (selected) XboxNeonGreen else DirectCyan,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = rSp(11f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(columnsPerRow - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }

                Text(
                    text = config.tr("Hoặc chọn thủ công nút bấm (17 nút)", "Or pick Standard Button (17 buttons)"),
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = rSp(11f),
                    modifier = Modifier.padding(top = 2.dp)
                )
                SettingsRepository.STANDARD_GAMEPAD_BUTTONS.chunked(columnsPerRow).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { (dstCode, dstShort, _) ->
                            val selected = dstCode == currentDest
                            AdaptiveFlatActionButton(
                                onClick = {
                                    stopCapture()
                                    onApplyStandardMapping(dstCode, swapPairEnabled)
                                    onDismiss()
                                },
                                isPrimary = selected,
                                containerColor = if (selected) Color(0xFF16352A) else XboxSurface,
                                contentColor = if (selected) XboxNeonGreen else TextPrimary,
                                borderColor = if (selected) XboxNeonGreen else XboxCardBorder,
                                focusedBorderColor = Color.White,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("visual_bind_pick_$dstCode")
                            ) {
                                Text(
                                    text = if (dstCode == 16) "Guide" else dstShort,
                                    color = if (selected) XboxNeonGreen else TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = rSp(10.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(columnsPerRow - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }

        AdaptiveTwoColumnLayout(
            leftWeight = 0.92f,
            rightWeight = 1.08f,
            leftColumn = { HeaderAndCaptureControls() },
            rightColumn = { TargetPickerGrid(maxGridHeight = (winH * 0.72f).coerceAtLeast(220.dp)) },
            portraitColumn = {
                HeaderAndCaptureControls()
                TargetPickerGrid(maxGridHeight = (winH * 0.42f).coerceAtLeast(180.dp))
            }
        )
    }
}
