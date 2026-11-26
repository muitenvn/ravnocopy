package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.cloudplay.CapturedPhysicalInput
import com.example.cloudplay.PhysicalGamepadCaptureBus
import com.example.data.AppConfig
import com.example.data.KeySequenceCodec
import com.example.data.KeySequenceStep
import com.example.data.SettingsRepository
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
import com.example.ui.theme.XboxSurfaceElevated
import com.example.ui.theme.XboxSurfaceVariant
import kotlinx.coroutines.delay

private enum class SlotBindTab {
    KEYBOARD_MOUSE,
    GAMEPAD
}

/**
 * Hộp thoại gán phím/chuột/phím gộp/chuỗi phím khi bấm vào 1 nút hoặc hướng cần trên sơ đồ tay cầm:
 * - Mặc định mở ngay Bàn phím đầy đủ (6 hàng) + hàng 7 nút Chuột kèm Thanh chuỗi ở trên cùng.
 * - Dùng chung khung "Thanh chuỗi + Mũi tên giây" cho tab Tay cầm vật lý (tối đa 6 bước).
 * - Cảnh báo trùng phím, Esc để hủy, nút "Xóa hết", "Xóa gán nút này", "Xong".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VisualControllerSlotBindDialog(
    sourceCode: Int,
    config: AppConfig,
    currentMap: Map<Int, Int>,
    slotBindings: Map<Int, List<KeySequenceStep>>,
    onApplyStandardMapping: (targetCode: Int, swapWithExisting: Boolean) -> Unit,
    onSaveSlotSequence: (steps: List<KeySequenceStep>, replaceConflictSlot: Int?) -> Unit,
    onClearSlotAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val langCode = config.appLanguage.effectiveLanguageCode()
    val srcShortLabel = SettingsRepository.getGamepadButtonShortLabel(sourceCode)
    val existingSequence = remember(slotBindings, sourceCode) {
        slotBindings[sourceCode].orEmpty()
    }
    val existingRemapDest = currentMap[sourceCode] ?: sourceCode

    val initialTab = remember(sourceCode, existingSequence) {
        if (existingSequence.isNotEmpty() && existingSequence.all { KeySequenceCodec.isGamepadSlotCode(it.code) }) {
            SlotBindTab.GAMEPAD
        } else {
            SlotBindTab.KEYBOARD_MOUSE
        }
    }
    var activeTab by remember(sourceCode) { mutableStateOf(initialTab) }

    var kbSteps by remember(sourceCode, existingSequence) {
        val initial = if (existingSequence.any { KeySequenceCodec.isKeyboardCode(it.code) || KeySequenceCodec.isMouseCode(it.code) }) {
            existingSequence
        } else {
            emptyList()
        }
        mutableStateOf(KeySequenceCodec.normalizeSteps(initial))
    }
    var gamepadSteps by remember(sourceCode, existingSequence, existingRemapDest) {
        val initial = when {
            existingSequence.any { KeySequenceCodec.isGamepadSlotCode(it.code) } -> existingSequence
            existingRemapDest != sourceCode -> listOf(
                KeySequenceStep(KeySequenceCodec.fromGamepadSlot(existingRemapDest), 0)
            )
            else -> emptyList()
        }
        mutableStateOf(KeySequenceCodec.normalizeSteps(initial))
    }

    val currentSteps = if (activeTab == SlotBindTab.KEYBOARD_MOUSE) kbSteps else gamepadSteps
    var selectedSlotIdx by remember(sourceCode, activeTab) {
        mutableIntStateOf(currentSteps.size.coerceAtMost(KeySequenceCodec.MAX_SEQUENCE_STEPS - 1).let {
            if (currentSteps.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) currentSteps.size else it
        })
    }
    var editingArrowIdx by remember(sourceCode, activeTab) { mutableStateOf<Int?>(null) }
    var swapPairEnabled by remember(sourceCode) { mutableStateOf(true) }
    var limitNoticeVisible by remember(sourceCode) { mutableStateOf(false) }

    val isCapturing by PhysicalGamepadCaptureBus.isCapturing.collectAsState()
    var countdownSec by remember(sourceCode) { mutableIntStateOf(0) }
    val doneFocusRequester = remember { FocusRequester() }
    val bodyScroll = rememberScrollState()

    fun updateCurrentSteps(next: List<KeySequenceStep>, nextSelected: Int) {
        val normalized = KeySequenceCodec.normalizeSteps(next)
        if (activeTab == SlotBindTab.KEYBOARD_MOUSE) {
            kbSteps = normalized
        } else {
            gamepadSteps = normalized
        }
        selectedSlotIdx = if (normalized.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
            nextSelected.coerceIn(0, normalized.size)
        } else {
            nextSelected.coerceIn(0, normalized.lastIndex.coerceAtLeast(0))
        }
        limitNoticeVisible = false
    }

    fun applyInputCodeToSequence(code: Int) {
        val steps = currentSteps
        if (selectedSlotIdx in steps.indices) {
            val updated = steps.toMutableList()
            updated[selectedSlotIdx] = updated[selectedSlotIdx].copy(code = code)
            updateCurrentSteps(updated, selectedSlotIdx)
        } else if (steps.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
            val updated = steps + KeySequenceStep(code = code, delayAfterTenths = 0)
            updateCurrentSteps(updated, updated.size)
        } else {
            limitNoticeVisible = true
        }
    }

    fun removeSelectedStep() {
        val steps = currentSteps
        if (selectedSlotIdx !in steps.indices) return
        val updated = steps.toMutableList().apply { removeAt(selectedSlotIdx) }
        val nextIdx = if (updated.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
            selectedSlotIdx.coerceAtMost(updated.size)
        } else {
            updated.lastIndex
        }
        updateCurrentSteps(updated, nextIdx)
    }

    fun startCapture() {
        countdownSec = 8
        PhysicalGamepadCaptureBus.armCapture(
            onCaptured = { captured ->
                countdownSec = 0
                when (captured) {
                    is CapturedPhysicalInput.StandardGamepad -> {
                        activeTab = SlotBindTab.GAMEPAD
                        val padCode = KeySequenceCodec.fromGamepadSlot(captured.code)
                        val steps = gamepadSteps
                        if (selectedSlotIdx in steps.indices) {
                            val updated = steps.toMutableList()
                            updated[selectedSlotIdx] = updated[selectedSlotIdx].copy(code = padCode)
                            gamepadSteps = KeySequenceCodec.normalizeSteps(updated)
                        } else if (steps.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
                            val updated = KeySequenceCodec.normalizeSteps(steps + KeySequenceStep(padCode, 0))
                            gamepadSteps = updated
                            selectedSlotIdx = updated.size.coerceAtMost(KeySequenceCodec.MAX_SEQUENCE_STEPS)
                        }
                    }
                    is CapturedPhysicalInput.CustomHardwareKey -> {
                        activeTab = SlotBindTab.KEYBOARD_MOUSE
                        val steps = kbSteps
                        if (selectedSlotIdx in steps.indices) {
                            val updated = steps.toMutableList()
                            updated[selectedSlotIdx] = updated[selectedSlotIdx].copy(code = captured.androidKeyCode)
                            kbSteps = KeySequenceCodec.normalizeSteps(updated)
                        } else if (steps.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
                            val updated = KeySequenceCodec.normalizeSteps(
                                steps + KeySequenceStep(captured.androidKeyCode, 0)
                            )
                            kbSteps = updated
                            selectedSlotIdx = updated.size.coerceAtMost(KeySequenceCodec.MAX_SEQUENCE_STEPS)
                        }
                    }
                }
            },
            onCancel = { countdownSec = 0 }
        )
    }

    fun stopCapture() {
        countdownSec = 0
        PhysicalGamepadCaptureBus.disarmCapture()
    }

    fun commitAndClose(replaceConflictSlot: Int? = null) {
        stopCapture()
        if (activeTab == SlotBindTab.KEYBOARD_MOUSE) {
            onSaveSlotSequence(kbSteps, replaceConflictSlot)
            onDismiss()
        } else {
            when {
                gamepadSteps.isEmpty() -> {
                    onClearSlotAll()
                    onDismiss()
                }
                gamepadSteps.size == 1 -> {
                    val dstSlot = KeySequenceCodec.toGamepadSlot(gamepadSteps[0].code)
                    if (dstSlot != null) {
                        onSaveSlotSequence(emptyList(), null)
                        onApplyStandardMapping(dstSlot, swapPairEnabled)
                    }
                    onDismiss()
                }
                else -> {
                    // Chuỗi / phím gộp nhiều nút tay cầm
                    onApplyStandardMapping(sourceCode, false)
                    onSaveSlotSequence(gamepadSteps, null)
                    onDismiss()
                }
            }
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
        onDispose { PhysicalGamepadCaptureBus.disarmCapture() }
    }

    val duplicateConflict = remember(slotBindings, sourceCode, kbSteps, activeTab) {
        if (activeTab == SlotBindTab.KEYBOARD_MOUSE) {
            KeySequenceCodec.findConflict(slotBindings, sourceCode, kbSteps)
        } else {
            null
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
        defaultFocusRequester = doneFocusRequester,
        wideWidthFraction = 0.96f,
        portraitWidthFraction = 0.96f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                    ) {
                        if (isCapturing) {
                            stopCapture()
                        } else {
                            onDismiss()
                        }
                        true
                    } else {
                        false
                    }
                }
                .verticalScroll(bodyScroll),
            verticalArrangement = Arrangement.spacedBy(gapMed)
        ) {
            // 1. Thanh đầu: Tên nút đang gán + Nút Xóa hết + Nút Xong
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                Text(
                    text = stringResource(R.string.keybind_button_title, srcShortLabel),
                    color = DirectCyan,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = rSp(15.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                AdaptiveFlatActionButton(
                    onClick = { updateCurrentSteps(emptyList(), 0) },
                    isPrimary = false,
                    containerColor = XboxSurfaceVariant,
                    borderColor = XboxCardBorder,
                    modifier = Modifier.testTag("visual_bind_clear_all_btn")
                ) {
                    Text(
                        text = stringResource(R.string.keybind_clear_all),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = rSp(11.5f),
                        maxLines = 1
                    )
                }

                AdaptiveFlatActionButton(
                    onClick = { commitAndClose(duplicateConflict?.otherSlot) },
                    isPrimary = true,
                    gamepadHint = "A",
                    focusRequester = doneFocusRequester,
                    modifier = Modifier.testTag("visual_bind_done_btn")
                ) {
                    Text(
                        text = stringResource(R.string.keybind_done),
                        color = Color(0xFF04150B),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = rSp(12f),
                        maxLines = 1
                    )
                }
            }

            // 2. Chuyển chế độ Bàn phím & Chuột / Tay cầm + Bắt phím vật lý
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall),
                verticalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                ModeSwitchChip(
                    text = stringResource(R.string.keybind_tab_keyboard_mouse),
                    selected = activeTab == SlotBindTab.KEYBOARD_MOUSE,
                    testTag = "visual_bind_tab_kb_mouse",
                    onClick = {
                        activeTab = SlotBindTab.KEYBOARD_MOUSE
                        editingArrowIdx = null
                    }
                )
                ModeSwitchChip(
                    text = stringResource(R.string.keybind_tab_gamepad),
                    selected = activeTab == SlotBindTab.GAMEPAD,
                    testTag = "visual_bind_tab_gamepad",
                    onClick = {
                        activeTab = SlotBindTab.GAMEPAD
                        editingArrowIdx = null
                    }
                )
                Surface(
                    shape = RoundedCornerShape(btnCorner),
                    color = if (isCapturing) Color(0xFF2B1818) else XboxSurfaceVariant,
                    border = BorderStroke(
                        borderThin,
                        if (isCapturing) JapanRed else DirectCyan
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = rDp(40f))
                        .clip(RoundedCornerShape(btnCorner))
                        .gamepadClickable(cornerRadius = btnCorner) {
                            if (isCapturing) stopCapture() else startCapture()
                        }
                        .testTag("visual_bind_capture_toggle_btn")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isCapturing) {
                                stringResource(R.string.keybind_capture_waiting, countdownSec)
                            } else {
                                stringResource(R.string.keybind_capture_physical)
                            },
                            color = if (isCapturing) JapanRed else DirectCyan,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = rSp(11.5f),
                            maxLines = 1
                        )
                    }
                }
            }

            // 3. Thanh chuỗi + mũi tên giây dùng chung cho cả Bàn phím/Chuột lẫn Tay cầm
            KeySequenceBar(
                steps = currentSteps,
                selectedIndex = selectedSlotIdx,
                editingArrowIndex = editingArrowIdx,
                langCode = langCode,
                onSelectSlot = { idx -> selectedSlotIdx = idx },
                onClickArrow = { arrowIdx -> editingArrowIdx = arrowIdx }
            )

            // Hướng dẫn ngắn + nút xóa nhanh ô đang chọn (khi chọn một ô đã có phím)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                Text(
                    text = when {
                        limitNoticeVisible -> stringResource(R.string.keybind_max_steps_reached)
                        activeTab == SlotBindTab.KEYBOARD_MOUSE -> stringResource(R.string.keybind_sequence_hint)
                        else -> stringResource(R.string.keybind_gamepad_sequence_hint)
                    },
                    color = if (limitNoticeVisible) WarningAmber else TextSecondary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = rSp(11.5f),
                    lineHeight = rSp(15.5f),
                    modifier = Modifier.weight(1f)
                )

                if (selectedSlotIdx in currentSteps.indices) {
                    Surface(
                        shape = RoundedCornerShape(chipCorner),
                        color = Color(0xFF281818),
                        border = BorderStroke(borderThin, JapanRed),
                        modifier = Modifier
                            .defaultMinSize(minHeight = rDp(40f))
                            .clip(RoundedCornerShape(chipCorner))
                            .gamepadClickable(cornerRadius = chipCorner) { removeSelectedStep() }
                            .testTag("visual_bind_remove_step_btn")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.keybind_remove_step),
                                color = JapanRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = rSp(11f),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 4. Cảnh báo trùng phím (màu vàng cảnh báo phẳng #E3B341)
            if (duplicateConflict != null) {
                val conflictKeyLabel = duplicateConflict.conflictCode?.let {
                    KeySequenceCodec.getLocalizedCodeLabel(it, context)
                } ?: (KeySequenceCodec.formatCompactBadge(duplicateConflict.conflictSteps) ?: "")
                val conflictSlotLabel = SettingsRepository.getGamepadButtonShortLabel(duplicateConflict.otherSlot)

                Surface(
                    shape = RoundedCornerShape(sectionCorner),
                    color = Color(0xFF262010),
                    border = BorderStroke(borderThin, WarningAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("visual_bind_duplicate_warning_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = padInner, vertical = gapMed),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(gapSmall)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.keybind_duplicate_warning,
                                conflictKeyLabel,
                                conflictSlotLabel
                            ),
                            color = WarningAmber,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = rSp(11.5f),
                            lineHeight = rSp(15.5f),
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(chipCorner),
                            color = WarningAmber,
                            modifier = Modifier
                                .defaultMinSize(minHeight = rDp(40f))
                                .clip(RoundedCornerShape(chipCorner))
                                .gamepadClickable(cornerRadius = chipCorner) {
                                    commitAndClose(duplicateConflict.otherSlot)
                                }
                                .testTag("visual_bind_replace_conflict_btn")
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.keybind_replace_confirm),
                                    color = Color(0xFF1A1405),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = rSp(11f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 5. Nội dung chính theo tab: Bàn phím đầy đủ + hàng Chuột HOẶC Lưới nút Tay cầm
            if (activeTab == SlotBindTab.KEYBOARD_MOUSE) {
                val activeCodes = remember(kbSteps) { kbSteps.map { it.code }.toSet() }
                FullKeyboardAndMousePad(
                    activeCodes = activeCodes,
                    onPressCode = { code -> applyInputCodeToSequence(code) }
                )
            } else {
                GamepadStepsPickerPanel(
                    gamepadSteps = gamepadSteps,
                    swapPairEnabled = swapPairEnabled,
                    onToggleSwapPair = { swapPairEnabled = !swapPairEnabled },
                    onPickPadSlot = { dstSlot ->
                        applyInputCodeToSequence(KeySequenceCodec.fromGamepadSlot(dstSlot))
                    }
                )
            }

            // 6. Hàng đáy: Xóa gán nút này + gợi ý Esc hủy + Hủy
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                AdaptiveFlatActionButton(
                    onClick = {
                        stopCapture()
                        onClearSlotAll()
                        onDismiss()
                    },
                    isPrimary = false,
                    containerColor = Color(0xFF281818),
                    borderColor = JapanRed,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("visual_bind_reset_single_btn")
                ) {
                    Text(
                        text = stringResource(R.string.keybind_clear_slot),
                        color = JapanRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = rSp(11.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        text = "${stringResource(R.string.keybind_cancel)} (${stringResource(R.string.keybind_esc_cancel_hint)})",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = rSp(11f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // Bảng nhỏ chỉnh thời gian chờ khi bấm vào một mũi tên giữa hai bước
    val arrowIdx = editingArrowIdx
    if (arrowIdx != null && arrowIdx in 0 until currentSteps.lastIndex) {
        val fromLabel = KeySequenceCodec.getLocalizedCodeLabel(currentSteps[arrowIdx].code, context)
        val toLabel = KeySequenceCodec.getLocalizedCodeLabel(currentSteps[arrowIdx + 1].code, context)
        DelayStepEditorDialog(
            fromLabel = fromLabel,
            toLabel = toLabel,
            initialDelayTenths = currentSteps[arrowIdx].delayAfterTenths,
            langCode = langCode,
            onApply = { newTenths ->
                val updated = currentSteps.toMutableList()
                updated[arrowIdx] = updated[arrowIdx].copy(delayAfterTenths = newTenths)
                updateCurrentSteps(updated, selectedSlotIdx)
                editingArrowIdx = null
            },
            onDismiss = { editingArrowIdx = null }
        )
    }
}

@Composable
private fun AdaptivePopupScope.ModeSwitchChip(
    text: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(btnCorner),
        color = if (selected) XboxSurfaceElevated else XboxSurfaceVariant,
        border = BorderStroke(
            width = if (selected) borderFocus else borderThin,
            color = if (selected) XboxNeonGreen else XboxCardBorder
        ),
        modifier = Modifier
            .defaultMinSize(minHeight = rDp(40f))
            .clip(RoundedCornerShape(btnCorner))
            .gamepadClickable(cornerRadius = btnCorner, onClick = onClick)
            .semantics { contentDescription = text }
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (selected) XboxNeonGreen else TextPrimary,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = rSp(12f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AdaptivePopupScope.GamepadStepsPickerPanel(
    gamepadSteps: List<KeySequenceStep>,
    swapPairEnabled: Boolean,
    onToggleSwapPair: () -> Unit,
    onPickPadSlot: (Int) -> Unit
) {
    val selectedPadSlots = remember(gamepadSteps) {
        gamepadSteps.mapNotNull { KeySequenceCodec.toGamepadSlot(it.code) }.toSet()
    }
    val columnsPerRow = if (useTwoColumnLayout) 6 else 4

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gapSmall)
    ) {
        // Giữ nguyên công tắc Hoán đổi 2 chiều cho trường hợp đổi 1 nút/hướng cần tay cầm
        if (gamepadSteps.size <= 1) {
            Surface(
                shape = RoundedCornerShape(sectionCorner),
                color = if (swapPairEnabled) XboxSurfaceElevated else XboxSurface,
                border = BorderStroke(borderThin, if (swapPairEnabled) XboxNeonGreen else XboxCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(sectionCorner))
                    .gamepadClickable(cornerRadius = sectionCorner, onClick = onToggleSwapPair)
                    .testTag("visual_bind_swap_pair_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padInner, vertical = gapMed),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = gapSmall)) {
                        Text(
                            text = stringResource(R.string.keybind_swap_pair_title),
                            color = if (swapPairEnabled) XboxNeonGreen else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(11.5f)
                        )
                        Text(
                            text = stringResource(R.string.keybind_swap_pair_desc),
                            color = TextSecondary,
                            fontSize = rSp(10f),
                            lineHeight = rSp(13.5f)
                        )
                    }
                    Text(
                        text = if (swapPairEnabled) stringResource(R.string.keybind_on) else stringResource(R.string.keybind_off),
                        color = if (swapPairEnabled) XboxNeonGreen else TextSecondary,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = rSp(11f)
                    )
                }
            }
        }

        // 8 hướng cần analog L/R
        SettingsRepository.PHYSICAL_GAMEPAD_STICK_DIRECTIONS.chunked(columnsPerRow).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                rowItems.forEach { (dstCode, dstShort, _) ->
                    val selected = selectedPadSlots.contains(dstCode)
                    AdaptiveFlatActionButton(
                        onClick = { onPickPadSlot(dstCode) },
                        isPrimary = false,
                        containerColor = if (selected) XboxSurfaceElevated else XboxSurfaceVariant,
                        borderColor = if (selected) XboxNeonGreen else XboxCardBorder,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("visual_bind_pick_$dstCode")
                    ) {
                        Text(
                            text = dstShort,
                            color = if (selected) XboxNeonGreen else DirectCyan,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(11.5f),
                            maxLines = 1
                        )
                    }
                }
                repeat(columnsPerRow - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }

        // 17 nút bấm chuẩn
        SettingsRepository.STANDARD_GAMEPAD_BUTTONS.chunked(columnsPerRow).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                rowItems.forEach { (dstCode, dstShort, _) ->
                    val selected = selectedPadSlots.contains(dstCode)
                    AdaptiveFlatActionButton(
                        onClick = { onPickPadSlot(dstCode) },
                        isPrimary = false,
                        containerColor = if (selected) XboxSurfaceElevated else XboxSurfaceVariant,
                        borderColor = if (selected) XboxNeonGreen else XboxCardBorder,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("visual_bind_pick_$dstCode")
                    ) {
                        Text(
                            text = if (dstCode == 16) "Guide" else dstShort,
                            color = if (selected) XboxNeonGreen else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(11f),
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
