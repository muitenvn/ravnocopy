package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudplay.PhysicalGamepadCaptureBus
import com.example.data.AppConfig
import com.example.data.SettingsRepository
import com.example.ui.theme.DirectCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxDark
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import com.example.ui.theme.XboxSurfaceVariant

private val PadSelectedFill = Color(0xFF16352A)
private val PadLiveFill = Color(0xFF10313A)
private val PadWarningColor = Color(0xFFE3B341)
private val PadDangerColor = Color(0xFFF0706B)

/**
 * Bảng Sơ đồ chỉnh nút tay cầm trực quan (mở trực tiếp trong Cài đặt chính & Cài đặt TV):
 * - Hiển thị hình bố cục tay cầm đầy đủ: LT/LB/RB/RT, View/Guide/Menu,
 *   Cần trái (L↑/L↓/L←/L→ + L3), D-Pad (▲▼◀▶), Cụm ABXY, Cần phải (R↑/R↓/R←/R→ + R3).
 * - Nhấn vào bất kỳ nút hoặc hướng cần nào để gán tự do bằng cách bấm trực tiếp trên tay cầm thật.
 * - Có hàng đảo trục nhanh (Đảo dọc/ngang Cần L & R, Đổi Cần L ↔ R) để đảo chiều di chuyển tức thì.
 * - Hiển thị đèn sáng trực tiếp (live) khi người dùng bấm nút hoặc gạt cần trên tay cầm thật.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisualControllerMapperDialog(
    config: AppConfig,
    controllerName: String,
    hasPhysicalController: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentMap = remember(config.physicalGamepadButtonMap) {
        SettingsRepository.parsePhysicalButtonMap(config.physicalGamepadButtonMap)
    }
    val livePressed by PhysicalGamepadCaptureBus.livePressedCodes.collectAsState()
    var editingSourceCode by remember { mutableStateOf<Int?>(null) }
    var confirmResetPending by remember { mutableStateOf(false) }
    val closeFocusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        PhysicalGamepadCaptureBus.setMonitorActive(true)
        onDispose {
            PhysicalGamepadCaptureBus.setMonitorActive(false)
        }
    }

    val invertLY = SettingsRepository.isPhysicalAxisInverted(
        currentMap, SettingsRepository.PHYS_STICK_L_UP, SettingsRepository.PHYS_STICK_L_DOWN
    )
    val invertLX = SettingsRepository.isPhysicalAxisInverted(
        currentMap, SettingsRepository.PHYS_STICK_L_LEFT, SettingsRepository.PHYS_STICK_L_RIGHT
    )
    val invertRY = SettingsRepository.isPhysicalAxisInverted(
        currentMap, SettingsRepository.PHYS_STICK_R_UP, SettingsRepository.PHYS_STICK_R_DOWN
    )
    val invertRX = SettingsRepository.isPhysicalAxisInverted(
        currentMap, SettingsRepository.PHYS_STICK_R_LEFT, SettingsRepository.PHYS_STICK_R_RIGHT
    )
    val sticksSwapped = SettingsRepository.arePhysicalSticksSwapped(currentMap)

    AdaptivePopupFrame(
        onDismissRequest = onDismiss,
        dialogTestTag = "visual_controller_mapper_dialog",
        defaultFocusRequester = closeFocusRequester,
        wideWidthFraction = 0.94f,
        portraitWidthFraction = 0.96f
    ) {
        val scrollState = rememberScrollState()
        val sideScrollState = rememberScrollState()
        val isCompact = !useTwoColumnLayout

        @Composable
        fun ControllerDiagramCard() {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(XboxDark)
                    .border(1.dp, XboxCardBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = if (isCompact) 8.dp else 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 10.dp)
            ) {
                // Hàng 1: Cò & Vai trên (LT, LB | View, Guide, Menu | RB, RT)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualPadSlotTile(
                        code = 6, label = "LT", currentMap = currentMap, livePressed = livePressed,
                        modifier = Modifier.weight(1f), minHeight = 40.dp,
                        onClick = { editingSourceCode = 6 }
                    )
                    VisualPadSlotTile(
                        code = 4, label = "LB", currentMap = currentMap, livePressed = livePressed,
                        modifier = Modifier.weight(1f), minHeight = 40.dp,
                        onClick = { editingSourceCode = 4 }
                    )
                    VisualPadSlotTile(
                        code = 8, label = "View", currentMap = currentMap, livePressed = livePressed,
                        modifier = Modifier.weight(0.95f), minHeight = 36.dp, compactText = true,
                        onClick = { editingSourceCode = 8 }
                    )
                    VisualGuideSlotTile(
                        currentMap = currentMap, livePressed = livePressed,
                        onClick = { editingSourceCode = 16 }
                    )
                    VisualPadSlotTile(
                        code = 9, label = "Menu", currentMap = currentMap, livePressed = livePressed,
                        modifier = Modifier.weight(0.95f), minHeight = 36.dp, compactText = true,
                        onClick = { editingSourceCode = 9 }
                    )
                    VisualPadSlotTile(
                        code = 5, label = "RB", currentMap = currentMap, livePressed = livePressed,
                        modifier = Modifier.weight(1f), minHeight = 40.dp,
                        onClick = { editingSourceCode = 5 }
                    )
                    VisualPadSlotTile(
                        code = 7, label = "RT", currentMap = currentMap, livePressed = livePressed,
                        modifier = Modifier.weight(1f), minHeight = 40.dp,
                        onClick = { editingSourceCode = 7 }
                    )
                }

                // Hàng 2: Nửa trên thân tay cầm — Cần Trái (L↑/↓/←/→ + L3) | Cụm A B X Y
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualStickCrossCluster(
                        title = config.tr("Cần Trái (L)", "Left Stick (L)"),
                        upCode = SettingsRepository.PHYS_STICK_L_UP,
                        downCode = SettingsRepository.PHYS_STICK_L_DOWN,
                        leftCode = SettingsRepository.PHYS_STICK_L_LEFT,
                        rightCode = SettingsRepository.PHYS_STICK_L_RIGHT,
                        clickCode = 10,
                        clickLabel = "L3",
                        currentMap = currentMap,
                        livePressed = livePressed,
                        onSelectCode = { editingSourceCode = it },
                        modifier = Modifier.weight(1f)
                    )
                    VisualAbxyDiamondCluster(
                        title = "A · B · X · Y",
                        currentMap = currentMap,
                        livePressed = livePressed,
                        onSelectCode = { editingSourceCode = it },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Hàng 3: Nửa dưới thân tay cầm — D-Pad (▲▼◀▶) | Cần Phải (R↑/↓/←/→ + R3)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualDpadCrossCluster(
                        title = "D-Pad",
                        currentMap = currentMap,
                        livePressed = livePressed,
                        onSelectCode = { editingSourceCode = it },
                        modifier = Modifier.weight(1f)
                    )
                    VisualStickCrossCluster(
                        title = config.tr("Cần Phải (R)", "Right Stick (R)"),
                        upCode = SettingsRepository.PHYS_STICK_R_UP,
                        downCode = SettingsRepository.PHYS_STICK_R_DOWN,
                        leftCode = SettingsRepository.PHYS_STICK_R_LEFT,
                        rightCode = SettingsRepository.PHYS_STICK_R_RIGHT,
                        clickCode = 11,
                        clickLabel = "R3",
                        currentMap = currentMap,
                        livePressed = livePressed,
                        onSelectCode = { editingSourceCode = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        @Composable
        fun SideControlsPanel() {
            Column(verticalArrangement = Arrangement.spacedBy(gapSmall)) {
                Text(
                    text = config.tr("Sơ đồ chỉnh nút tay cầm", "Controller Button Mapper"),
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = rSp(15.5f)
                )

                // Trạng thái tay cầm + tín hiệu bấm trực tiếp
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (livePressed.isNotEmpty()) PadLiveFill else XboxSurface,
                    border = BorderStroke(
                        1.dp,
                        if (livePressed.isNotEmpty()) DirectCyan else XboxCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (hasPhysicalController) {
                                controllerName
                            } else {
                                config.tr("Chưa thấy tay cầm — vẫn chỉnh sẵn được", "No controller connected — pre-configure anytime")
                            },
                            color = if (hasPhysicalController) XboxNeonGreen else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(11f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val liveText = if (livePressed.isNotEmpty()) {
                            livePressed.sorted().joinToString("  ") { code ->
                                val src = SettingsRepository.getGamepadButtonShortLabel(code)
                                val dst = currentMap[code] ?: code
                                if (dst != code) {
                                    "$src → ${SettingsRepository.getGamepadButtonShortLabel(dst)}"
                                } else {
                                    src
                                }
                            }
                        } else {
                            config.tr(
                                "Nhấn vào 1 nút hoặc 1 hướng cần (L↑/↓/←/→, R↑/↓/←/→) rồi bấm trên tay cầm thật để gán.",
                                "Tap any button or stick direction, then press on your controller to bind."
                            )
                        }
                        Text(
                            text = liveText,
                            color = if (livePressed.isNotEmpty()) DirectCyan else TextSecondary,
                            fontFamily = if (livePressed.isNotEmpty()) JetBrainsMonoFontFamily else null,
                            fontWeight = if (livePressed.isNotEmpty()) FontWeight.ExtraBold else FontWeight.Normal,
                            fontSize = rSp(10.5f),
                            lineHeight = rSp(14f)
                        )
                    }
                }

                // Hàng đảo trục nhanh (để người dùng đảo chiều di chuyển ngược ngay 1 chạm)
                Text(
                    text = config.tr("Đảo chiều di chuyển nhanh (Cần L / R)", "Quick Stick Inversion & Swap"),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = rSp(11.5f)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickInvertChip(
                        text = config.tr("↕ Đảo dọc L", "↕ Invert L-Y"),
                        active = invertLY,
                        testTag = "visual_map_invert_ly_btn",
                        onClick = {
                            SettingsRepository.togglePhysicalAxisInvert(
                                SettingsRepository.PHYS_STICK_L_UP,
                                SettingsRepository.PHYS_STICK_L_DOWN
                            )
                        }
                    )
                    QuickInvertChip(
                        text = config.tr("↔ Đảo ngang L", "↔ Invert L-X"),
                        active = invertLX,
                        testTag = "visual_map_invert_lx_btn",
                        onClick = {
                            SettingsRepository.togglePhysicalAxisInvert(
                                SettingsRepository.PHYS_STICK_L_LEFT,
                                SettingsRepository.PHYS_STICK_L_RIGHT
                            )
                        }
                    )
                    QuickInvertChip(
                        text = config.tr("↕ Đảo dọc R", "↕ Invert R-Y"),
                        active = invertRY,
                        testTag = "visual_map_invert_ry_btn",
                        onClick = {
                            SettingsRepository.togglePhysicalAxisInvert(
                                SettingsRepository.PHYS_STICK_R_UP,
                                SettingsRepository.PHYS_STICK_R_DOWN
                            )
                        }
                    )
                    QuickInvertChip(
                        text = config.tr("↔ Đảo ngang R", "↔ Invert R-X"),
                        active = invertRX,
                        testTag = "visual_map_invert_rx_btn",
                        onClick = {
                            SettingsRepository.togglePhysicalAxisInvert(
                                SettingsRepository.PHYS_STICK_R_LEFT,
                                SettingsRepository.PHYS_STICK_R_RIGHT
                            )
                        }
                    )
                    QuickInvertChip(
                        text = config.tr("⇄ Đổi Cần L ↔ R", "⇄ Swap L ↔ R"),
                        active = sticksSwapped,
                        testTag = "visual_map_swap_sticks_btn",
                        onClick = { SettingsRepository.togglePhysicalSticksSwap() }
                    )
                }

                // Tóm tắt các nút đã đổi + Đặt lại an toàn + Đóng
                if (currentMap.isNotEmpty()) {
                    Text(
                        text = "${config.tr("Đã đổi", "Remapped")}: ${currentMap.size}",
                        color = XboxNeonGreen,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = rSp(11f)
                    )
                }

                if (confirmResetPending) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF281818),
                        border = BorderStroke(1.dp, PadDangerColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = config.tr("Đặt lại toàn bộ sơ đồ nút về mặc định?", "Reset all controller bindings to default?"),
                                color = PadDangerColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = rSp(11f)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AdaptiveFlatActionButton(
                                    onClick = {
                                        SettingsRepository.resetPhysicalGamepadButtonMap()
                                        confirmResetPending = false
                                        Toast.makeText(
                                            context,
                                            config.tr("Đã đặt lại sơ đồ nút tay cầm", "Controller bindings reset"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    isPrimary = false,
                                    containerColor = Color(0xFF3A1A1A),
                                    borderColor = PadDangerColor,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("visual_map_confirm_reset_btn")
                                ) {
                                    Text(
                                        text = config.tr("Xác nhận", "Confirm"),
                                        color = PadDangerColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = rSp(11f)
                                    )
                                }
                                AdaptiveFlatActionButton(
                                    onClick = { confirmResetPending = false },
                                    isPrimary = false,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = config.tr("Hủy", "Cancel"),
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = rSp(11f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gapSmall)
                    ) {
                        if (currentMap.isNotEmpty()) {
                            AdaptiveFlatActionButton(
                                onClick = { confirmResetPending = true },
                                isPrimary = false,
                                containerColor = XboxSurfaceVariant,
                                borderColor = XboxCardBorder,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("visual_map_reset_all_btn")
                            ) {
                                Text(
                                    text = config.tr("Đặt lại", "Reset"),
                                    color = PadWarningColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = rSp(11.5f)
                                )
                            }
                        }
                        AdaptiveFlatActionButton(
                            onClick = onDismiss,
                            isPrimary = true,
                            gamepadHint = "B",
                            focusRequester = closeFocusRequester,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("visual_map_close_btn")
                        ) {
                            Text(
                                text = config.tr("Xong", "Done"),
                                color = Color(0xFF04150B),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = rSp(12f)
                            )
                        }
                    }
                }
            }
        }

        AdaptiveTwoColumnLayout(
            leftWeight = 1.32f,
            rightWeight = 0.88f,
            leftColumn = {
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    ControllerDiagramCard()
                }
            },
            rightColumn = {
                Column(modifier = Modifier.verticalScroll(sideScrollState)) {
                    SideControlsPanel()
                }
            },
            portraitColumn = {
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(gapMed)
                ) {
                    ControllerDiagramCard()
                    SideControlsPanel()
                }
            }
        )
    }

    val activeEditCode = editingSourceCode
    if (activeEditCode != null) {
        VisualControllerSlotBindDialog(
            sourceCode = activeEditCode,
            config = config,
            currentMap = currentMap,
            onApplyMapping = { targetCode, swapWithExisting ->
                SettingsRepository.updatePhysicalInputMapping(
                    sourceCode = activeEditCode,
                    targetCode = targetCode,
                    swapWithExisting = swapWithExisting
                )
            },
            onDismiss = { editingSourceCode = null }
        )
    }
}

@Composable
private fun QuickInvertChip(
    text: String,
    active: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) PadSelectedFill else XboxSurfaceVariant,
        border = BorderStroke(1.dp, if (active) XboxNeonGreen else XboxCardBorder),
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .gamepadClickable(cornerRadius = 8.dp, onClick = onClick)
            .semantics { contentDescription = text }
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (active) XboxNeonGreen else TextPrimary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VisualPadSlotTile(
    code: Int,
    label: String,
    currentMap: Map<Int, Int>,
    livePressed: Set<Int>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 44.dp,
    accentColor: Color = XboxNeonGreen,
    isCircle: Boolean = false,
    compactText: Boolean = false
) {
    val mappedCode = currentMap[code] ?: code
    val isRemapped = mappedCode != code
    val isLive = livePressed.contains(code)
    val mappedShort = SettingsRepository.getGamepadButtonShortLabel(mappedCode)
    val shape = if (isCircle) CircleShape else RoundedCornerShape(10.dp)
    val corner = if (isCircle) 24.dp else 10.dp

    val bg = when {
        isLive -> PadLiveFill
        isRemapped -> PadSelectedFill
        else -> XboxSurfaceVariant
    }
    val borderColor = when {
        isLive -> DirectCyan
        isRemapped -> XboxNeonGreen
        else -> XboxCardBorder
    }

    Surface(
        shape = shape,
        color = bg,
        border = BorderStroke(if (isLive || isRemapped) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = minHeight)
            .clip(shape)
            .gamepadClickable(cornerRadius = corner, onClick = onClick)
            .semantics {
                contentDescription = if (isRemapped) "$label → $mappedShort" else label
            }
            .testTag("visual_pad_slot_$code")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                color = when {
                    isLive -> DirectCyan
                    isRemapped -> TextSecondary
                    else -> accentColor
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compactText) 9.5.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            if (isRemapped) {
                Text(
                    text = "→$mappedShort",
                    color = XboxNeonGreen,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun VisualGuideSlotTile(
    currentMap: Map<Int, Int>,
    livePressed: Set<Int>,
    onClick: () -> Unit
) {
    val code = 16
    val mappedCode = currentMap[code] ?: code
    val isRemapped = mappedCode != code
    val isLive = livePressed.contains(code)
    val mappedShort = SettingsRepository.getGamepadButtonShortLabel(mappedCode)

    Surface(
        shape = CircleShape,
        color = when {
            isLive -> PadLiveFill
            isRemapped -> PadSelectedFill
            else -> XboxSurface
        },
        border = BorderStroke(
            if (isLive || isRemapped) 1.5.dp else 1.dp,
            when {
                isLive -> DirectCyan
                isRemapped -> XboxNeonGreen
                else -> XboxNeonGreen.copy(alpha = 0.65f)
            }
        ),
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .gamepadClickable(cornerRadius = 20.dp, onClick = onClick)
            .semantics {
                contentDescription = if (isRemapped) "Guide → $mappedShort" else "Guide"
            }
            .testTag("visual_pad_slot_16")
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isRemapped) {
                Text(
                    text = mappedShort,
                    color = XboxNeonGreen,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            } else {
                // Biểu tượng vòng tròn + chấm tâm riêng của Ravno (không dùng logo Xbox / 4 ô vuông)
                Canvas(modifier = Modifier.size(18.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val tint = if (isLive) DirectCyan else XboxNeonGreen
                    drawCircle(
                        color = tint,
                        radius = size.minDimension * 0.44f,
                        center = c,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = tint,
                        radius = size.minDimension * 0.16f,
                        center = c
                    )
                }
            }
        }
    }
}

@Composable
private fun VisualStickCrossCluster(
    title: String,
    upCode: Int,
    downCode: Int,
    leftCode: Int,
    rightCode: Int,
    clickCode: Int,
    clickLabel: String,
    currentMap: Map<Int, Int>,
    livePressed: Set<Int>,
    onSelectCode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(XboxSurface)
            .border(1.dp, XboxCardBorder, RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        // Lên
        VisualPadSlotTile(
            code = upCode,
            label = SettingsRepository.getGamepadButtonShortLabel(upCode),
            currentMap = currentMap,
            livePressed = livePressed,
            accentColor = DirectCyan,
            minHeight = 36.dp,
            onClick = { onSelectCode(upCode) },
            modifier = Modifier.fillMaxWidth(0.50f)
        )
        // Trái · Nhấn L3/R3 · Phải
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VisualPadSlotTile(
                code = leftCode,
                label = SettingsRepository.getGamepadButtonShortLabel(leftCode),
                currentMap = currentMap,
                livePressed = livePressed,
                accentColor = DirectCyan,
                minHeight = 36.dp,
                onClick = { onSelectCode(leftCode) },
                modifier = Modifier.weight(1f)
            )
            VisualPadSlotTile(
                code = clickCode,
                label = clickLabel,
                currentMap = currentMap,
                livePressed = livePressed,
                accentColor = TextPrimary,
                isCircle = true,
                minHeight = 36.dp,
                onClick = { onSelectCode(clickCode) },
                modifier = Modifier.weight(0.92f)
            )
            VisualPadSlotTile(
                code = rightCode,
                label = SettingsRepository.getGamepadButtonShortLabel(rightCode),
                currentMap = currentMap,
                livePressed = livePressed,
                accentColor = DirectCyan,
                minHeight = 36.dp,
                onClick = { onSelectCode(rightCode) },
                modifier = Modifier.weight(1f)
            )
        }
        // Xuống
        VisualPadSlotTile(
            code = downCode,
            label = SettingsRepository.getGamepadButtonShortLabel(downCode),
            currentMap = currentMap,
            livePressed = livePressed,
            accentColor = DirectCyan,
            minHeight = 36.dp,
            onClick = { onSelectCode(downCode) },
            modifier = Modifier.fillMaxWidth(0.50f)
        )
    }
}

@Composable
private fun VisualDpadCrossCluster(
    title: String,
    currentMap: Map<Int, Int>,
    livePressed: Set<Int>,
    onSelectCode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(XboxSurface)
            .border(1.dp, XboxCardBorder, RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        VisualPadSlotTile(
            code = 12, label = "▲", currentMap = currentMap, livePressed = livePressed,
            accentColor = TextPrimary, minHeight = 36.dp,
            onClick = { onSelectCode(12) },
            modifier = Modifier.fillMaxWidth(0.48f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VisualPadSlotTile(
                code = 14, label = "◀", currentMap = currentMap, livePressed = livePressed,
                accentColor = TextPrimary, minHeight = 36.dp,
                onClick = { onSelectCode(14) },
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier.weight(0.65f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(XboxCardBorder)
                )
            }
            VisualPadSlotTile(
                code = 15, label = "▶", currentMap = currentMap, livePressed = livePressed,
                accentColor = TextPrimary, minHeight = 36.dp,
                onClick = { onSelectCode(15) },
                modifier = Modifier.weight(1f)
            )
        }
        VisualPadSlotTile(
            code = 13, label = "▼", currentMap = currentMap, livePressed = livePressed,
            accentColor = TextPrimary, minHeight = 36.dp,
            onClick = { onSelectCode(13) },
            modifier = Modifier.fillMaxWidth(0.48f)
        )
    }
}

@Composable
private fun VisualAbxyDiamondCluster(
    title: String,
    currentMap: Map<Int, Int>,
    livePressed: Set<Int>,
    onSelectCode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(XboxSurface)
            .border(1.dp, XboxCardBorder, RoundedCornerShape(14.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            color = TextSecondary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        VisualPadSlotTile(
            code = 3, label = "Y", currentMap = currentMap, livePressed = livePressed,
            accentColor = PadWarningColor, isCircle = true, minHeight = 38.dp,
            onClick = { onSelectCode(3) },
            modifier = Modifier.fillMaxWidth(0.46f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VisualPadSlotTile(
                code = 2, label = "X", currentMap = currentMap, livePressed = livePressed,
                accentColor = DirectCyan, isCircle = true, minHeight = 38.dp,
                onClick = { onSelectCode(2) },
                modifier = Modifier.weight(1f)
            )
            VisualPadSlotTile(
                code = 1, label = "B", currentMap = currentMap, livePressed = livePressed,
                accentColor = PadDangerColor, isCircle = true, minHeight = 38.dp,
                onClick = { onSelectCode(1) },
                modifier = Modifier.weight(1f)
            )
        }
        VisualPadSlotTile(
            code = 0, label = "A", currentMap = currentMap, livePressed = livePressed,
            accentColor = XboxNeonGreen, isCircle = true, minHeight = 38.dp,
            onClick = { onSelectCode(0) },
            modifier = Modifier.fillMaxWidth(0.46f)
        )
    }
}
