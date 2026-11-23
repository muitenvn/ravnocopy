package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudplay.KbmPrototypeStrings
import com.example.cloudplay.KbmTestMode
import com.example.cloudplay.KeyboardMousePrototype
import com.example.cloudplay.PcOverlayMode
import com.example.data.AppConfig
import com.example.data.GamepadButtonPressMode
import com.example.data.SettingsRepository
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.roundToInt

/**
 * Hộp nổi tinh chỉnh cho chế độ Bàn phím & Chuột PC (đồng bộ hoàn toàn về kiểu dáng
 * với GamepadNodePopover của Tay cầm: cùng 264.dp, tonal flat, nhấn đúp chỉnh từng phím y hệt tay cầm).
 */
@Composable
internal fun PcNodePopover(
    nodeId: String,
    nodeDef: PcVirtualNodeDef?,
    config: AppConfig,
    isEditMode: Boolean,
    usePerGameLayout: Boolean,
    hasActiveGameKey: Boolean,
    hasPerGameCustomLayout: Boolean,
    gameDisplayName: String?,
    nodeCust: PcNodeCustomization,
    onSwitchToGamepadMode: () -> Unit,
    onToggleUsePerGame: (Boolean) -> Unit,
    onUseGlobalLayout: () -> Unit,
    onEnterEditMode: () -> Unit,
    onShowAll: () -> Unit,
    onResetAll: () -> Unit,
    onUpdateNodeCust: (PcNodeCustomization) -> Unit,
    onNudge: (Float, Float) -> Unit,
    onHideOrDeleteNode: () -> Unit,
    onResetNode: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showResetAllConfirm by remember { mutableStateOf(false) }

    if (showResetAllConfirm) {
        AlertDialog(
            onDismissRequest = { showResetAllConfirm = false },
            containerColor = HudSurface2,
            shape = RoundedCornerShape(22.dp),
            titleContentColor = HudInk,
            textContentColor = HudDialogBody,
            title = {
                Text(
                    text = config.tr("Đặt lại bố cục mặc định?", "Reset Default Layout?"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = config.tr(
                        "Khôi phục toàn bộ vị trí, cỡ, độ mờ và các phím PC đã thêm về mặc định?",
                        "Restore all PC key positions, sizes, opacity, and custom keys to default?"
                    ),
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetAllConfirm = false
                        onResetAll()
                    },
                    modifier = Modifier.testTag("pc_tuner_confirm_reset_btn")
                ) {
                    Text(
                        text = config.tr("Đặt lại", "Reset"),
                        color = HudDanger,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetAllConfirm = false },
                    modifier = Modifier.testTag("pc_tuner_cancel_reset_btn")
                ) {
                    Text(
                        text = config.tr("Hủy", "Cancel"),
                        color = HudMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }

    val title = when {
        nodeId == "all" -> KbmPrototypeStrings.allKeyboardTitle(config)
        nodeDef?.isAnalogWasd == true -> KbmPrototypeStrings.analogWasdName(config)
        nodeDef != null -> nodeDef.displayLabel()
        else -> nodeId
    }

    Surface(
        modifier = modifier
            .width(264.dp)
            .heightIn(max = 380.dp)
            .testTag(if (nodeId == "all") "pc_tuner_all_popover" else "pc_tuner_node_popover"),
        shape = RoundedCornerShape(18.dp),
        color = HudSurface2,
        border = BorderStroke(1.dp, HudLine),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✥ $title",
                    color = HudInk,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onClose() }
                        .testTag("pc_popover_close"),
                    shape = RoundedCornerShape(9.dp),
                    color = HudSurface,
                    shadowElevation = 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✕", color = HudMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (nodeId == "all") {
                    PcAllKeyboardPopoverBody(
                        config = config,
                        isEditMode = isEditMode,
                        usePerGameLayout = usePerGameLayout,
                        hasActiveGameKey = hasActiveGameKey,
                        hasPerGameCustomLayout = hasPerGameCustomLayout,
                        gameDisplayName = gameDisplayName,
                        onSwitchToGamepadMode = onSwitchToGamepadMode,
                        onToggleUsePerGame = onToggleUsePerGame,
                        onUseGlobalLayout = onUseGlobalLayout,
                        onEnterEditMode = onEnterEditMode,
                        onShowAll = onShowAll,
                        onRequestResetAll = { showResetAllConfirm = true }
                    )
                } else {
                    PcSingleNodePopoverBody(
                        nodeDef = nodeDef,
                        config = config,
                        nodeCust = nodeCust,
                        onUpdateNodeCust = onUpdateNodeCust,
                        onNudge = onNudge,
                        onHideOrDeleteNode = onHideOrDeleteNode,
                        onResetNode = onResetNode
                    )
                }
            }
        }
    }
}

@Composable
private fun PcAllKeyboardPopoverBody(
    config: AppConfig,
    isEditMode: Boolean,
    usePerGameLayout: Boolean,
    hasActiveGameKey: Boolean,
    hasPerGameCustomLayout: Boolean,
    gameDisplayName: String?,
    onSwitchToGamepadMode: () -> Unit,
    onToggleUsePerGame: (Boolean) -> Unit,
    onUseGlobalLayout: () -> Unit,
    onEnterEditMode: () -> Unit,
    onShowAll: () -> Unit,
    onRequestResetAll: () -> Unit
) {
    val kbmState by KeyboardMousePrototype.state.collectAsState()

    // 1. Thanh chuyển 1 chạm Tay cầm <-> Phím & Chuột ngay dòng đầu
    HudSegmentedControl(
        options = listOf(
            KbmPrototypeStrings.gamepadModeLabel(config),
            KbmPrototypeStrings.pcKbmModeLabel(config)
        ),
        selectedIndex = 1,
        onSelect = { idx ->
            if (idx == 0) onSwitchToGamepadMode()
        },
        bgColor = HudSurface,
        minButtonHeight = 32.dp,
        fontSize = 11.5.sp,
        tagPrefix = "pc_all_mode_switch"
    )

    // 2. Bố cục Chung / Riêng cho game (tách biệt với Tay cầm)
    HudSectionLabel(text = config.tr("Bố cục", "Layout"))
    HudSegmentedControl(
        options = listOf(
            config.tr("Chung", "Global"),
            config.tr("Riêng cho game", "Per-game")
        ),
        selectedIndex = if (usePerGameLayout) 1 else 0,
        onSelect = { idx -> onToggleUsePerGame(idx == 1) },
        bgColor = HudSurface,
        minButtonHeight = 30.dp,
        fontSize = 11.sp
    )

    if (usePerGameLayout && hasActiveGameKey) {
        HudSurfaceRow(minHeight = 32.dp, bgColor = HudSurface) {
            Text(
                text = gameDisplayName?.takeIf { it.isNotBlank() } ?: config.tr("Game hiện tại", "Current game"),
                color = if (hasPerGameCustomLayout) HudCyan else HudMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasPerGameCustomLayout) {
                Spacer(modifier = Modifier.width(6.dp))
                HudButton(
                    text = config.tr("Dùng chung", "Global"),
                    onClick = onUseGlobalLayout,
                    minHeight = 26.dp,
                    fontSize = 10.sp,
                    containerOverride = HudSurface2,
                    contentOverride = HudCyan
                )
            }
        }
    }

    // 3. Hiển thị phím PC
    HudSectionLabel(text = KbmPrototypeStrings.overlayDisplayTitle(config))
    val overlayModes = PcOverlayMode.entries
    HudSegmentedControl(
        options = listOf(
            KbmPrototypeStrings.overlayAutoLabel(config),
            KbmPrototypeStrings.overlayTouchPcLabel(config),
            KbmPrototypeStrings.overlayHardwareLabel(config)
        ),
        selectedIndex = overlayModes.indexOf(kbmState.pcOverlayMode).coerceAtLeast(0),
        onSelect = { idx ->
            overlayModes.getOrNull(idx)?.let { KeyboardMousePrototype.setPcOverlayMode(it) }
        },
        bgColor = HudSurface,
        minButtonHeight = 30.dp,
        fontSize = 10.5.sp
    )

    // 4. Kiểu gửi vào game
    HudSectionLabel(text = KbmPrototypeStrings.engineTitle(config))
    HudSegmentedControl(
        options = listOf(
            KbmPrototypeStrings.engineAllGamesLabel(config),
            KbmPrototypeStrings.engineNativeWebLabel(config)
        ),
        selectedIndex = if (kbmState.mode == KbmTestMode.NATIVE_PASSTHROUGH) 1 else 0,
        onSelect = { idx ->
            val target = if (idx == 1) KbmTestMode.NATIVE_PASSTHROUGH else KbmTestMode.VIRTUAL_GAMEPAD
            KeyboardMousePrototype.setPreferredPcEngine(target)
        },
        bgColor = HudSurface,
        minButtonHeight = 30.dp,
        fontSize = 10.5.sp
    )

    // 5. Độ nhạy chuột, Cỡ chung, Độ mờ
    HudSliderRow(
        label = KbmPrototypeStrings.mouseSensitivityLabel(config),
        valueText = String.format(java.util.Locale.US, "%.1fx", kbmState.mouseSensitivity),
        value = kbmState.mouseSensitivity,
        valueRange = 0.4f..2.5f,
        onValueChange = { KeyboardMousePrototype.setMouseSensitivity(it) },
        bgColor = HudSurface
    )

    HudSliderRow(
        label = config.tr("Cỡ chung", "Size"),
        valueText = "${(config.gamepadScale * 100).roundToInt()}%",
        value = config.gamepadScale,
        valueRange = 0.75f..1.35f,
        onValueChange = { SettingsRepository.updateGamepadScale(it) },
        bgColor = HudSurface
    )

    HudSliderRow(
        label = config.tr("Độ đậm mờ", "Opacity"),
        valueText = "${(config.gamepadOpacity * 100).roundToInt()}%",
        value = config.gamepadOpacity,
        valueRange = 0.25f..0.95f,
        onValueChange = { SettingsRepository.updateGamepadOpacity(it) },
        bgColor = HudSurface
    )

    // 6. Các công tắc chuột kiểu PojavLauncher
    HudSwitchRow(
        title = KbmPrototypeStrings.tapBgMouseLabel(config),
        checked = kbmState.tapBackgroundMouseClick,
        onCheckedChange = { KeyboardMousePrototype.setTapBackgroundMouseClick(it) },
        bgColor = HudSurface
    )

    HudSwitchRow(
        title = KbmPrototypeStrings.directTouchLabel(config),
        checked = kbmState.directTouchPassThrough,
        onCheckedChange = { KeyboardMousePrototype.setDirectTouchPassThrough(it) },
        bgColor = HudSurface
    )

    HudSwitchRow(
        title = KbmPrototypeStrings.invertYLabel(config),
        checked = kbmState.invertMouseY,
        onCheckedChange = { KeyboardMousePrototype.setInvertMouseY(it) },
        bgColor = HudSurface
    )

    // 7. Sửa & thêm phím + Hiện tất cả / Đặt lại tất cả
    HudButton(
        text = if (isEditMode) {
            KbmPrototypeStrings.doneEditLabel(config)
        } else {
            KbmPrototypeStrings.editButtonsPcLabel(config)
        },
        onClick = onEnterEditMode,
        style = HudButtonStyle.MAIN,
        minHeight = 36.dp,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pc_tuner_enter_edit_btn")
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HudButton(
            text = config.tr("Hiện tất cả", "Show All"),
            onClick = onShowAll,
            minHeight = 34.dp,
            fontSize = 11.5.sp,
            containerOverride = HudSurface,
            modifier = Modifier.weight(1f)
        )
        HudButton(
            text = config.tr("Đặt lại tất cả", "Reset All"),
            onClick = onRequestResetAll,
            minHeight = 34.dp,
            fontSize = 11.5.sp,
            containerOverride = HudSurface,
            contentOverride = HudDanger,
            modifier = Modifier
                .weight(1f)
                .testTag("pc_tuner_reset_all_btn")
        )
    }
}

/**
 * Chỉnh từng phím PC y hệt SingleNodePopoverBody của bản Tay cầm:
 * Cỡ chung nút, Bề ngang/Bề dọc, Độ đậm mờ, Tọa độ X/Y + nhích 2px, Kiểu bấm (Thường/Giữ/Turbo), Ẩn/Xóa, Đặt lại.
 */
@Composable
private fun PcSingleNodePopoverBody(
    nodeDef: PcVirtualNodeDef?,
    config: AppConfig,
    nodeCust: PcNodeCustomization,
    onUpdateNodeCust: (PcNodeCustomization) -> Unit,
    onNudge: (Float, Float) -> Unit,
    onHideOrDeleteNode: () -> Unit,
    onResetNode: () -> Unit
) {
    val isStick = nodeDef?.isAnalogWasd == true
    val isCustomAdded = nodeDef?.isDefaultNode == false
    var showCustomDim by remember(nodeDef?.id) { mutableStateOf(nodeCust.hasCustomDimensions()) }

    // 1. Cỡ chung nút
    HudSurfaceRow(minHeight = 34.dp, bgColor = HudSurface) {
        Text(
            text = config.tr("Cỡ chung nút", "Size"),
            color = HudInk,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        HudStepperControl(
            valueText = "${(nodeCust.scale * 100).roundToInt()}%",
            onDecrement = {
                onUpdateNodeCust(nodeCust.copy(scale = (nodeCust.scale - 0.10f).coerceIn(0.55f, 1.90f)))
            },
            onIncrement = {
                onUpdateNodeCust(nodeCust.copy(scale = (nodeCust.scale + 0.10f).coerceIn(0.55f, 1.90f)))
            },
            btnBg = HudSurface2,
            decTag = "pc_node_scale_minus",
            incTag = "pc_node_scale_plus"
        )
    }

    // 1b. Bề ngang · bề dọc (giống hệt bản tay cầm)
    if (!isStick) {
        val wPct = (nodeCust.widthScale * 100).roundToInt()
        val hPct = (nodeCust.heightScale * 100).roundToInt()
        val hasCustomDim = nodeCust.hasCustomDimensions()
        val arrow = if (showCustomDim) "▾" else "▸"
        val dimTitle = if (hasCustomDim) {
            config.tr("$arrow Chỉnh ngang · dọc ($wPct% × $hPct%)", "$arrow Width · Height ($wPct% × $hPct%)")
        } else {
            config.tr("$arrow Tùy chỉnh bề ngang · bề dọc", "$arrow Custom Width & Height")
        }
        HudButton(
            text = dimTitle,
            onClick = { showCustomDim = !showCustomDim },
            minHeight = 32.dp,
            fontSize = 11.5.sp,
            containerOverride = if (showCustomDim || hasCustomDim) HudSelBg else HudSurface,
            contentOverride = if (showCustomDim || hasCustomDim) HudAccent else HudMuted,
            modifier = Modifier.fillMaxWidth()
        )
        if (showCustomDim) {
            HudSliderRow(
                label = config.tr("Bề ngang", "Width"),
                valueText = "$wPct%",
                value = nodeCust.widthScale,
                valueRange = 0.50f..2.50f,
                onValueChange = { onUpdateNodeCust(nodeCust.copy(widthScale = it.coerceIn(0.50f, 2.50f))) },
                bgColor = HudSurface
            )
            HudSliderRow(
                label = config.tr("Bề dọc", "Height"),
                valueText = "$hPct%",
                value = nodeCust.heightScale,
                valueRange = 0.50f..2.50f,
                onValueChange = { onUpdateNodeCust(nodeCust.copy(heightScale = it.coerceIn(0.50f, 2.50f))) },
                bgColor = HudSurface
            )
            if (hasCustomDim) {
                HudButton(
                    text = config.tr("↺ Đặt lại ngang · dọc (100%)", "↺ Reset Width · Height"),
                    onClick = { onUpdateNodeCust(nodeCust.copy(widthScale = 1.0f, heightScale = 1.0f)) },
                    minHeight = 28.dp,
                    fontSize = 11.sp,
                    containerOverride = HudSurface,
                    contentOverride = HudCyan,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // 2. Độ đậm mờ riêng
    HudSliderRow(
        label = config.tr("Độ đậm mờ", "Opacity"),
        valueText = "${(nodeCust.opacity * 100).roundToInt()}%",
        value = nodeCust.opacity,
        valueRange = 0.20f..1.00f,
        onValueChange = { onUpdateNodeCust(nodeCust.copy(opacity = it.coerceIn(0.20f, 1.00f))) },
        bgColor = HudSurface
    )

    // 3. Tọa độ X/Y & nhích từng bước nhỏ
    val cx = nodeCust.offsetX.roundToInt()
    val cy = nodeCust.offsetY.roundToInt()
    val xs = if (cx > 0) "+$cx" else "$cx"
    val ys = if (cy > 0) "+$cy" else "$cy"
    HudSurfaceRow(minHeight = 34.dp, bgColor = HudSurface) {
        Text(
            text = "X:$xs Y:$ys",
            color = HudMuted,
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(
                "◀" to (-4f to 0f),
                "▲" to (0f to -4f),
                "▼" to (0f to 4f),
                "▶" to (4f to 0f)
            ).forEach { (sym, delta) ->
                Surface(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onNudge(delta.first, delta.second) },
                    shape = RoundedCornerShape(6.dp),
                    color = HudSurface2,
                    shadowElevation = 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(sym, color = HudInk, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    // 4. Kiểu bấm (Thường / Giữ / Turbo) giống bản tay cầm
    if (!isStick) {
        val pressModes = GamepadButtonPressMode.entries
        HudSectionLabel(text = config.tr("Kiểu bấm", "Press Mode"))
        HudSegmentedControl(
            options = pressModes.map { config.tr(it.titleVi, it.titleEn) },
            selectedIndex = pressModes.indexOf(nodeCust.pressMode).coerceAtLeast(0),
            onSelect = { idx ->
                pressModes.getOrNull(idx)?.let { onUpdateNodeCust(nodeCust.copy(pressMode = it)) }
            },
            bgColor = HudSurface,
            minButtonHeight = 28.dp,
            fontSize = 10.5.sp
        )
        if (nodeCust.pressMode == GamepadButtonPressMode.TURBO) {
            HudSliderRow(
                label = "Turbo",
                valueText = "${nodeCust.turboHz} Hz",
                value = nodeCust.turboHz.toFloat(),
                valueRange = 4f..20f,
                onValueChange = { onUpdateNodeCust(nodeCust.copy(turboHz = it.roundToInt().coerceIn(4, 20))) },
                bgColor = HudSurface
            )
        }
    }

    // 5. Ẩn/Xóa & Đặt lại nút
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HudButton(
            text = if (isCustomAdded) {
                KbmPrototypeStrings.deleteKeyLabel(config)
            } else {
                KbmPrototypeStrings.hideKeyLabel(config)
            },
            onClick = onHideOrDeleteNode,
            minHeight = 32.dp,
            fontSize = 11.5.sp,
            containerOverride = HudSurface,
            contentOverride = HudDanger,
            modifier = Modifier.weight(1f)
        )
        HudButton(
            text = config.tr("Đặt lại", "Reset"),
            onClick = onResetNode,
            minHeight = 32.dp,
            fontSize = 11.5.sp,
            containerOverride = HudSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
