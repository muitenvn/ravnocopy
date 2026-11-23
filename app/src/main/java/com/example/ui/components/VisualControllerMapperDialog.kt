package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
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
private val PadOverlayNeutral = Color(0xE0142019)
private val PadRingTrackBg = Color(0xCC0B130F)

private const val CONTROLLER_ASPECT_RATIO = 1.5f // 3:2 exact aspect ratio of img_controller_base

/**
 * Sơ đồ tay cầm trực quan dựng trên hình ảnh tay cầm thật (tỉ lệ 3:2 chuẩn):
 * - Hiển thị đúng bố cục tay cầm thật: LT/LB/RB/RT ở vai trên, View/Guide/Menu ở cầu giữa,
 *   vòng tròn Cần Trái (L↑/L↓/L←/L→ + L3), chữ thập D-Pad (▲▼◀▶),
 *   4 nút tròn A/B/X/Y đúng vị trí kim cương, và vòng tròn Cần Phải (R↑/R↓/R←/R→ + R3).
 * - Nhấn vào bất kỳ nút hoặc hướng cần nào rồi bấm BẤT KỲ phím vật lý nào (tay cầm, bàn phím,
 *   điều khiển TV, phím âm lượng...) hoặc gạt cần để gán tự do.
 * - Có hàng đảo chiều di chuyển nhanh 1 chạm (Đảo dọc/ngang Cần L & R, Đổi Cần L ↔ R).
 * - Tự co khít hoàn toàn trên màn hình dọc, màn hình ngang, máy tính bảng và TV không bị cắt.
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
    val customKeyMap = remember(config.physicalCustomKeyMap) {
        SettingsRepository.parsePhysicalCustomKeyMap(config.physicalCustomKeyMap)
    }
    val customKeysBySlot = remember(customKeyMap) {
        val grouped = mutableMapOf<Int, MutableList<Int>>()
        customKeyMap.forEach { (keyCode, slot) ->
            grouped.getOrPut(slot) { mutableListOf() }.add(keyCode)
        }
        grouped.mapValues { it.value.sorted() }
    }
    val totalRemappedCount = currentMap.size + customKeyMap.size

    val liveStdPressed by PhysicalGamepadCaptureBus.livePressedCodes.collectAsState()
    val liveCustomKeyCodes by PhysicalGamepadCaptureBus.liveCustomKeyCodes.collectAsState()
    val liveEffectiveSlots = remember(liveStdPressed, liveCustomKeyCodes, customKeyMap) {
        val out = HashSet<Int>(liveStdPressed)
        liveCustomKeyCodes.forEach { k ->
            customKeyMap[k]?.let { out.add(it) }
        }
        out
    }

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
        wideWidthFraction = 0.96f,
        portraitWidthFraction = 0.96f
    ) {
        val portraitScroll = rememberScrollState()
        val sideScroll = rememberScrollState()

        @Composable
        fun ControllerVisualCard(maxHeightLimit: Dp) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeightLimit)
                    .clip(RoundedCornerShape(16.dp))
                    .background(XboxDark)
                    .border(1.dp, XboxCardBorder, RoundedCornerShape(16.dp))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                val availW = maxWidth
                val availH = maxHeight
                val fittedH = minOf(availH, availW / CONTROLLER_ASPECT_RATIO)
                val fittedW = fittedH * CONTROLLER_ASPECT_RATIO

                RealControllerOverlayCanvas(
                    canvasWidth = fittedW,
                    canvasHeight = fittedH,
                    currentMap = currentMap,
                    customKeysBySlot = customKeysBySlot,
                    liveSlots = liveEffectiveSlots,
                    onSelectSlot = { editingSourceCode = it }
                )
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
                    fontSize = rSp(15f)
                )

                // Trạng thái thiết bị & tín hiệu bấm trực tiếp (tay cầm hoặc phím cứng bất kỳ)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (liveEffectiveSlots.isNotEmpty() || liveCustomKeyCodes.isNotEmpty()) PadLiveFill else XboxSurface,
                    border = BorderStroke(
                        1.dp,
                        if (liveEffectiveSlots.isNotEmpty() || liveCustomKeyCodes.isNotEmpty()) DirectCyan else XboxCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (hasPhysicalController) {
                                controllerName
                            } else {
                                config.tr("Hỗ trợ tay cầm & mọi phím cứng", "Works with gamepads & any hardware key")
                            },
                            color = if (hasPhysicalController) XboxNeonGreen else DirectCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = rSp(10.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val liveParts = mutableListOf<String>()
                        liveStdPressed.sorted().forEach { code ->
                            val src = SettingsRepository.getGamepadButtonShortLabel(code)
                            val dst = currentMap[code] ?: code
                            liveParts.add(
                                if (dst != code) "$src→${SettingsRepository.getGamepadButtonShortLabel(dst)}" else src
                            )
                        }
                        liveCustomKeyCodes.sorted().forEach { keyCode ->
                            val keyLabel = SettingsRepository.getHardwareKeyShortLabel(keyCode)
                            val mapped = customKeyMap[keyCode]
                            liveParts.add(
                                if (mapped != null) "$keyLabel→${SettingsRepository.getGamepadButtonShortLabel(mapped)}" else keyLabel
                            )
                        }
                        val liveText = if (liveParts.isNotEmpty()) {
                            liveParts.joinToString("  ")
                        } else {
                            config.tr(
                                "Chạm nút hoặc hướng trên vòng tròn L/R rồi bấm bất kỳ phím nào (tay cầm, bàn phím, điều khiển...) để gán.",
                                "Tap any button or L/R stick direction, then press any key or controller input to bind."
                            )
                        }
                        Text(
                            text = liveText,
                            color = if (liveParts.isNotEmpty()) DirectCyan else TextSecondary,
                            fontFamily = if (liveParts.isNotEmpty()) JetBrainsMonoFontFamily else null,
                            fontWeight = if (liveParts.isNotEmpty()) FontWeight.ExtraBold else FontWeight.Normal,
                            fontSize = rSp(10.2f),
                            lineHeight = rSp(13.8f)
                        )
                    }
                }

                // Hàng đảo chiều di chuyển nhanh 1 chạm
                Text(
                    text = config.tr("Đảo chiều di chuyển nhanh (Cần L / R)", "Quick Stick Inversion & Swap"),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = rSp(11f)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
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

                if (totalRemappedCount > 0) {
                    Text(
                        text = "${config.tr("Đã đổi", "Remapped")}: $totalRemappedCount",
                        color = XboxNeonGreen,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = rSp(10.8f)
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
                                        SettingsRepository.resetPhysicalButtonMap()
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
                        if (totalRemappedCount > 0) {
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
            leftWeight = 1.38f,
            rightWeight = 0.82f,
            leftColumn = {
                ControllerVisualCard(maxHeightLimit = (winH * 0.82f).coerceAtLeast(180.dp))
            },
            rightColumn = {
                Column(modifier = Modifier.verticalScroll(sideScroll)) {
                    SideControlsPanel()
                }
            },
            portraitColumn = {
                Column(
                    modifier = Modifier.verticalScroll(portraitScroll),
                    verticalArrangement = Arrangement.spacedBy(gapMed)
                ) {
                    ControllerVisualCard(maxHeightLimit = (winH * 0.42f).coerceAtLeast(210.dp))
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
            customKeyMap = customKeyMap,
            onApplyStandardMapping = { targetCode, swapWithExisting ->
                SettingsRepository.updatePhysicalInputMapping(
                    sourceCode = activeEditCode,
                    targetCode = targetCode,
                    swapWithExisting = swapWithExisting
                )
            },
            onBindCustomHardwareKey = { androidKeyCode ->
                SettingsRepository.bindPhysicalCustomKey(
                    androidKeyCode = androidKeyCode,
                    targetSlot = activeEditCode
                )
            },
            onClearCustomKeysForSlot = {
                SettingsRepository.clearPhysicalCustomKeysForSlot(activeEditCode)
            },
            onDismiss = { editingSourceCode = null }
        )
    }
}

/**
 * Hiển thị hình ảnh tay cầm thật (`img_controller_base_1795446123292`, tỉ lệ 3:2)
 * và đặt trực tiếp từng nút tròn, nút vai, D-Pad chữ thập, vòng tròn analog L & R
 * đúng tọa độ phần trăm lên trên thân tay cầm.
 */
@Composable
private fun RealControllerOverlayCanvas(
    canvasWidth: Dp,
    canvasHeight: Dp,
    currentMap: Map<Int, Int>,
    customKeysBySlot: Map<Int, List<Int>>,
    liveSlots: Set<Int>,
    onSelectSlot: (Int) -> Unit
) {
    val w = canvasWidth
    val h = canvasHeight

    fun badgeForSlot(code: Int): String? {
        val mapped = currentMap[code]
        if (mapped != null && mapped != code) {
            return SettingsRepository.getGamepadButtonShortLabel(mapped)
        }
        val ext = customKeysBySlot[code]?.firstOrNull()
        if (ext != null) {
            return SettingsRepository.getHardwareKeyShortLabel(ext)
        }
        return null
    }

    fun Modifier.centeredAt(cx: Float, cy: Float, itemW: Dp, itemH: Dp): Modifier =
        this
            .offset(x = w * cx - itemW / 2f, y = h * cy - itemH / 2f)
            .size(width = itemW, height = itemH)

    Box(modifier = Modifier.size(width = w, height = h)) {
        // Ảnh nền thân tay cầm tự tạo (không logo, không chữ, đồng màu nền tối Ravno)
        Image(
            painter = painterResource(id = R.drawable.img_controller_base_1795446123292),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )

        // 1. Nút vai & cò trên (LT, LB, RB, RT)
        val triggerW = w * 0.118f
        val triggerH = h * 0.112f
        val bumperW = w * 0.126f
        val bumperH = h * 0.112f

        OverlayPillButton(
            code = 6, label = "LT", badge = badgeForSlot(6), isLive = liveSlots.contains(6),
            accentColor = XboxNeonGreen,
            modifier = Modifier.centeredAt(0.145f, 0.088f, triggerW, triggerH),
            onClick = { onSelectSlot(6) }
        )
        OverlayPillButton(
            code = 4, label = "LB", badge = badgeForSlot(4), isLive = liveSlots.contains(4),
            accentColor = XboxNeonGreen,
            modifier = Modifier.centeredAt(0.286f, 0.102f, bumperW, bumperH),
            onClick = { onSelectSlot(4) }
        )
        OverlayPillButton(
            code = 5, label = "RB", badge = badgeForSlot(5), isLive = liveSlots.contains(5),
            accentColor = XboxNeonGreen,
            modifier = Modifier.centeredAt(0.714f, 0.102f, bumperW, bumperH),
            onClick = { onSelectSlot(5) }
        )
        OverlayPillButton(
            code = 7, label = "RT", badge = badgeForSlot(7), isLive = liveSlots.contains(7),
            accentColor = XboxNeonGreen,
            modifier = Modifier.centeredAt(0.855f, 0.088f, triggerW, triggerH),
            onClick = { onSelectSlot(7) }
        )

        // 2. Cụm cầu giữa: Guide (biểu tượng vòng tròn Ravno), View, Menu
        val guideDiam = h * 0.145f
        OverlayGuideCircleButton(
            badge = badgeForSlot(16),
            isLive = liveSlots.contains(16),
            modifier = Modifier.centeredAt(0.500f, 0.222f, guideDiam, guideDiam),
            onClick = { onSelectSlot(16) }
        )
        val centerBtnW = w * 0.092f
        val centerBtnH = h * 0.098f
        OverlayPillButton(
            code = 8, label = "View", badge = badgeForSlot(8), isLive = liveSlots.contains(8),
            accentColor = TextSecondary,
            modifier = Modifier.centeredAt(0.420f, 0.320f, centerBtnW, centerBtnH),
            onClick = { onSelectSlot(8) }
        )
        OverlayPillButton(
            code = 9, label = "Menu", badge = badgeForSlot(9), isLive = liveSlots.contains(9),
            accentColor = TextSecondary,
            modifier = Modifier.centeredAt(0.580f, 0.320f, centerBtnW, centerBtnH),
            onClick = { onSelectSlot(9) }
        )

        // 3. Cần Analog Trái (L) — Vòng tròn analog thực thụ đặt đúng hốc cần trái (0.275, 0.328)
        val stickDiam = h * 0.285f
        OverlayAnalogStickRing(
            upCode = SettingsRepository.PHYS_STICK_L_UP,
            downCode = SettingsRepository.PHYS_STICK_L_DOWN,
            leftCode = SettingsRepository.PHYS_STICK_L_LEFT,
            rightCode = SettingsRepository.PHYS_STICK_L_RIGHT,
            clickCode = 10,
            centerLabel = "L3",
            badgeProvider = ::badgeForSlot,
            liveSlots = liveSlots,
            onSelectSlot = onSelectSlot,
            modifier = Modifier.centeredAt(0.275f, 0.328f, stickDiam, stickDiam)
        )

        // 4. D-Pad chữ thập đặt đúng vị trí hốc D-Pad (0.386, 0.528)
        val dpadDiam = h * 0.228f
        OverlayDpadCross(
            badgeProvider = ::badgeForSlot,
            liveSlots = liveSlots,
            onSelectSlot = onSelectSlot,
            modifier = Modifier.centeredAt(0.386f, 0.528f, dpadDiam, dpadDiam)
        )

        // 5. Cụm 4 nút tròn A · B · X · Y đặt đúng 4 hốc tròn trên ảnh tay cầm
        val faceBtnDiam = h * 0.118f
        OverlayCircleButton(
            code = 3, label = "Y", badge = badgeForSlot(3), isLive = liveSlots.contains(3),
            accentColor = PadWarningColor,
            modifier = Modifier.centeredAt(0.720f, 0.236f, faceBtnDiam, faceBtnDiam),
            onClick = { onSelectSlot(3) }
        )
        OverlayCircleButton(
            code = 2, label = "X", badge = badgeForSlot(2), isLive = liveSlots.contains(2),
            accentColor = DirectCyan,
            modifier = Modifier.centeredAt(0.660f, 0.326f, faceBtnDiam, faceBtnDiam),
            onClick = { onSelectSlot(2) }
        )
        OverlayCircleButton(
            code = 1, label = "B", badge = badgeForSlot(1), isLive = liveSlots.contains(1),
            accentColor = PadDangerColor,
            modifier = Modifier.centeredAt(0.780f, 0.326f, faceBtnDiam, faceBtnDiam),
            onClick = { onSelectSlot(1) }
        )
        OverlayCircleButton(
            code = 0, label = "A", badge = badgeForSlot(0), isLive = liveSlots.contains(0),
            accentColor = XboxNeonGreen,
            modifier = Modifier.centeredAt(0.720f, 0.416f, faceBtnDiam, faceBtnDiam),
            onClick = { onSelectSlot(0) }
        )

        // 6. Cần Analog Phải (R) — Vòng tròn analog thực thụ đặt đúng hốc cần phải (0.612, 0.528)
        OverlayAnalogStickRing(
            upCode = SettingsRepository.PHYS_STICK_R_UP,
            downCode = SettingsRepository.PHYS_STICK_R_DOWN,
            leftCode = SettingsRepository.PHYS_STICK_R_LEFT,
            rightCode = SettingsRepository.PHYS_STICK_R_RIGHT,
            clickCode = 11,
            centerLabel = "R3",
            badgeProvider = ::badgeForSlot,
            liveSlots = liveSlots,
            onSelectSlot = onSelectSlot,
            modifier = Modifier.centeredAt(0.612f, 0.528f, stickDiam, stickDiam)
        )
    }
}

/**
 * Vòng tròn Cần Analog (L / R):
 * - Nền vòng tròn đồng tâm khớp với thân tay cầm thật.
 * - 4 điểm chạm tròn gọn ở 4 hướng (↑, ↓, ←, →) quanh vành tròn + nút tròn tâm L3 / R3.
 */
@Composable
private fun OverlayAnalogStickRing(
    upCode: Int,
    downCode: Int,
    leftCode: Int,
    rightCode: Int,
    clickCode: Int,
    centerLabel: String,
    badgeProvider: (Int) -> String?,
    liveSlots: Set<Int>,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val anyLive = liveSlots.contains(upCode) || liveSlots.contains(downCode) ||
        liveSlots.contains(leftCode) || liveSlots.contains(rightCode) || liveSlots.contains(clickCode)
    val anyMapped = badgeProvider(upCode) != null || badgeProvider(downCode) != null ||
        badgeProvider(leftCode) != null || badgeProvider(rightCode) != null || badgeProvider(clickCode) != null

    BoxWithConstraints(
        modifier = modifier
            .clip(CircleShape)
            .background(PadRingTrackBg)
            .border(
                width = if (anyLive || anyMapped) 1.5.dp else 1.dp,
                color = when {
                    anyLive -> DirectCyan
                    anyMapped -> XboxNeonGreen.copy(alpha = 0.78f)
                    else -> Color(0xFF284135)
                },
                shape = CircleShape
            )
    ) {
        val d = maxWidth
        val dirSize = d * 0.30f
        val centerSize = d * 0.34f

        // Vòng tròn phụ bên trong để tạo cảm giác hốc analog thật
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(d * 0.62f)
                .clip(CircleShape)
                .border(0.8.dp, Color(0x552E4A3C), CircleShape)
        )

        // Lên (12 giờ)
        OverlayStickDirDot(
            code = upCode,
            defaultArrow = "↑",
            badge = badgeProvider(upCode),
            isLive = liveSlots.contains(upCode),
            onClick = { onSelectSlot(upCode) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = d * 0.03f)
                .size(dirSize)
        )
        // Xuống (6 giờ)
        OverlayStickDirDot(
            code = downCode,
            defaultArrow = "↓",
            badge = badgeProvider(downCode),
            isLive = liveSlots.contains(downCode),
            onClick = { onSelectSlot(downCode) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = d * 0.03f)
                .size(dirSize)
        )
        // Trái (9 giờ)
        OverlayStickDirDot(
            code = leftCode,
            defaultArrow = "←",
            badge = badgeProvider(leftCode),
            isLive = liveSlots.contains(leftCode),
            onClick = { onSelectSlot(leftCode) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = d * 0.03f)
                .size(dirSize)
        )
        // Phải (3 giờ)
        OverlayStickDirDot(
            code = rightCode,
            defaultArrow = "→",
            badge = badgeProvider(rightCode),
            isLive = liveSlots.contains(rightCode),
            onClick = { onSelectSlot(rightCode) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = d * 0.03f)
                .size(dirSize)
        )
        // Tâm tròn L3 / R3
        OverlayCircleButton(
            code = clickCode,
            label = centerLabel,
            badge = badgeProvider(clickCode),
            isLive = liveSlots.contains(clickCode),
            accentColor = TextPrimary,
            compactFont = true,
            onClick = { onSelectSlot(clickCode) },
            modifier = Modifier
                .align(Alignment.Center)
                .size(centerSize)
        )
    }
}

@Composable
private fun OverlayStickDirDot(
    code: Int,
    defaultArrow: String,
    badge: String?,
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMapped = badge != null
    val display = badge ?: defaultArrow
    val bg = when {
        isLive -> PadLiveFill
        isMapped -> PadSelectedFill
        else -> Color(0xCC16241D)
    }
    val border = when {
        isLive -> DirectCyan
        isMapped -> XboxNeonGreen
        else -> Color(0xFF2E473B)
    }

    Surface(
        shape = CircleShape,
        color = bg,
        border = BorderStroke(if (isLive || isMapped) 1.3.dp else 0.8.dp, border),
        modifier = modifier
            .clip(CircleShape)
            .gamepadClickable(cornerRadius = 24.dp, onClick = onClick)
            .semantics {
                val base = SettingsRepository.getGamepadButtonShortLabel(code)
                contentDescription = if (isMapped) "$base → $badge" else base
            }
            .testTag("visual_pad_slot_$code")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = display,
                color = when {
                    isLive -> DirectCyan
                    isMapped -> XboxNeonGreen
                    else -> DirectCyan.copy(alpha = 0.88f)
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (display.length > 2) 7.sp else if (display.length == 2) 8.sp else 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/**
 * Cụm D-Pad hình chữ thập (▲ ▼ ◀ ▶) đặt đúng hốc chữ thập trên thân tay cầm.
 */
@Composable
private fun OverlayDpadCross(
    badgeProvider: (Int) -> String?,
    liveSlots: Set<Int>,
    onSelectSlot: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val d = maxWidth
        val arm = d * 0.34f
        val center = d * 0.24f

        // Khối nối tâm chữ thập
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(center)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xCC142019))
        )

        OverlayDpadArm(
            code = 12, arrow = "▲", badge = badgeProvider(12), isLive = liveSlots.contains(12),
            onClick = { onSelectSlot(12) },
            modifier = Modifier.align(Alignment.TopCenter).size(arm)
        )
        OverlayDpadArm(
            code = 13, arrow = "▼", badge = badgeProvider(13), isLive = liveSlots.contains(13),
            onClick = { onSelectSlot(13) },
            modifier = Modifier.align(Alignment.BottomCenter).size(arm)
        )
        OverlayDpadArm(
            code = 14, arrow = "◀", badge = badgeProvider(14), isLive = liveSlots.contains(14),
            onClick = { onSelectSlot(14) },
            modifier = Modifier.align(Alignment.CenterStart).size(arm)
        )
        OverlayDpadArm(
            code = 15, arrow = "▶", badge = badgeProvider(15), isLive = liveSlots.contains(15),
            onClick = { onSelectSlot(15) },
            modifier = Modifier.align(Alignment.CenterEnd).size(arm)
        )
    }
}

@Composable
private fun OverlayDpadArm(
    code: Int,
    arrow: String,
    badge: String?,
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMapped = badge != null
    val display = badge ?: arrow
    val bg = when {
        isLive -> PadLiveFill
        isMapped -> PadSelectedFill
        else -> PadOverlayNeutral
    }
    val border = when {
        isLive -> DirectCyan
        isMapped -> XboxNeonGreen
        else -> Color(0xFF2B4237)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = BorderStroke(if (isLive || isMapped) 1.3.dp else 0.9.dp, border),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .gamepadClickable(cornerRadius = 6.dp, onClick = onClick)
            .semantics {
                val base = SettingsRepository.getGamepadButtonShortLabel(code)
                contentDescription = if (isMapped) "$base → $badge" else base
            }
            .testTag("visual_pad_slot_$code")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = display,
                color = when {
                    isLive -> DirectCyan
                    isMapped -> XboxNeonGreen
                    else -> TextPrimary
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (display.length > 2) 7.sp else if (display.length == 2) 8.sp else 9.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun OverlayCircleButton(
    code: Int,
    label: String,
    badge: String?,
    isLive: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactFont: Boolean = false
) {
    val isMapped = badge != null
    val display = badge ?: label
    val bg = when {
        isLive -> PadLiveFill
        isMapped -> PadSelectedFill
        else -> PadOverlayNeutral
    }
    val border = when {
        isLive -> DirectCyan
        isMapped -> XboxNeonGreen
        else -> accentColor.copy(alpha = 0.65f)
    }

    Surface(
        shape = CircleShape,
        color = bg,
        border = BorderStroke(if (isLive || isMapped) 1.5.dp else 1.1.dp, border),
        modifier = modifier
            .clip(CircleShape)
            .gamepadClickable(cornerRadius = 24.dp, onClick = onClick)
            .semantics {
                contentDescription = if (isMapped) "$label → $badge" else label
            }
            .testTag("visual_pad_slot_$code")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = display,
                color = when {
                    isLive -> DirectCyan
                    isMapped -> XboxNeonGreen
                    else -> accentColor
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = when {
                    display.length >= 3 -> 7.5.sp
                    compactFont || display.length == 2 -> 8.5.sp
                    else -> 10.5.sp
                },
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun OverlayPillButton(
    code: Int,
    label: String,
    badge: String?,
    isLive: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMapped = badge != null
    val display = if (isMapped) "$label→$badge" else label
    val bg = when {
        isLive -> PadLiveFill
        isMapped -> PadSelectedFill
        else -> PadOverlayNeutral
    }
    val border = when {
        isLive -> DirectCyan
        isMapped -> XboxNeonGreen
        else -> Color(0xFF2B4237)
    }

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = bg,
        border = BorderStroke(if (isLive || isMapped) 1.4.dp else 1.dp, border),
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .gamepadClickable(cornerRadius = 9.dp, onClick = onClick)
            .semantics {
                contentDescription = if (isMapped) "$label → $badge" else label
            }
            .testTag("visual_pad_slot_$code")
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = display,
                color = when {
                    isLive -> DirectCyan
                    isMapped -> XboxNeonGreen
                    else -> accentColor
                },
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (display.length > 4) 7.5.sp else 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun OverlayGuideCircleButton(
    badge: String?,
    isLive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMapped = badge != null
    Surface(
        shape = CircleShape,
        color = when {
            isLive -> PadLiveFill
            isMapped -> PadSelectedFill
            else -> PadOverlayNeutral
        },
        border = BorderStroke(
            if (isLive || isMapped) 1.5.dp else 1.1.dp,
            when {
                isLive -> DirectCyan
                isMapped -> XboxNeonGreen
                else -> XboxNeonGreen.copy(alpha = 0.72f)
            }
        ),
        modifier = modifier
            .clip(CircleShape)
            .gamepadClickable(cornerRadius = 24.dp, onClick = onClick)
            .semantics {
                contentDescription = if (isMapped) "Guide → $badge" else "Guide"
            }
            .testTag("visual_pad_slot_16")
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isMapped) {
                Text(
                    text = badge.orEmpty(),
                    color = XboxNeonGreen,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.sp,
                    maxLines = 1
                )
            } else {
                // Biểu tượng vòng tròn + chấm tâm riêng của Ravno (không dùng logo Xbox / 4 ô vuông)
                Canvas(modifier = Modifier.fillMaxSize(0.56f)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val tint = if (isLive) DirectCyan else XboxNeonGreen
                    drawCircle(
                        color = tint,
                        radius = size.minDimension * 0.44f,
                        center = c,
                        style = Stroke(width = 1.8.dp.toPx())
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
            .defaultMinSize(minHeight = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .gamepadClickable(cornerRadius = 8.dp, onClick = onClick)
            .semantics { contentDescription = text }
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (active) XboxNeonGreen else TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
