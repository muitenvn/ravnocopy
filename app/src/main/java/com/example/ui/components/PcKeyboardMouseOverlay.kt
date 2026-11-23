package com.example.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.cloudplay.KbmPrototypeStrings
import com.example.cloudplay.KeyboardMousePrototype
import com.example.data.AppConfig
import com.example.data.GamepadButtonPressMode
import com.example.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private val PcKeyBg = Color(0xCC0D1511)
private val PcKeyBgPressed = Color(0xEE16352A)
private val PcKeyBorder = Color(0x669EB3A7)
private val PcAccentGreen = Color(0xFF27D884)
private val PcAccentCyan = Color(0xFF3FD8EE)
private val PcKeyText = Color(0xFFEAF2EE)
private val PcKeySubText = Color(0xFF9EB3A7)

/**
 * Lớp Bàn phím & Chuột PC tách riêng hoàn toàn với Tay cầm:
 * - Nền vuốt chuột trực tiếp kiểu PojavLauncher (có công tắc bật/tắt chạm nền = LMB/RMB & chạm trực tiếp màn hình).
 * - Cần analog WASD có khóa chạy nhanh Shift.
 * - Nút tinh chỉnh (⚙) cố định ở cạnh trên mở hộp nổi ✥ Cả bàn phím (đổi qua lại Tay cầm ↔ Phím & Chuột).
 * - Chế độ sửa nút với thanh nổi gọn (+ Thêm phím từ Bàn phím Full-Size, ✓ Xong, ↺ Đặt lại) và nhấn đúp sửa từng nút y hệt tay cầm.
 */
@Composable
fun PcKeyboardMouseOverlay(
    config: AppConfig,
    gameKey: String? = null,
    gameDisplayName: String? = null,
    onSwitchToGamepadMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val kbmState by KeyboardMousePrototype.state.collectAsState()

    var profile by remember(gameKey) { mutableStateOf(PcOverlayLayoutStore.loadProfile(context, gameKey)) }
    var hasPerGameCustom by remember(gameKey) { mutableStateOf(PcOverlayLayoutStore.hasPerGameProfile(context, gameKey)) }
    var isEditMode by remember { mutableStateOf(false) }
    var activePopoverNodeId by remember { mutableStateOf<String?>(null) }
    var showFullKeyboardPicker by remember { mutableStateOf(false) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    val nodeBoundsMap = remember { mutableStateMapOf<String, Rect>() }

    fun persistProfile(next: PcLayoutProfile) {
        profile = next
        PcOverlayLayoutStore.saveProfile(context, gameKey, next)
        hasPerGameCustom = PcOverlayLayoutStore.hasPerGameProfile(context, gameKey)
    }

    fun getNodeCust(id: String): PcNodeCustomization =
        profile.customizations[id] ?: PcNodeCustomization(opacity = config.gamepadOpacity.coerceIn(0.25f, 0.95f))

    fun updateNodeCust(id: String, updater: (PcNodeCustomization) -> PcNodeCustomization) {
        val nextMap = profile.customizations.toMutableMap()
        nextMap[id] = updater(getNodeCust(id))
        persistProfile(profile.copy(customizations = nextMap))
    }

    val allNodes = remember(profile.customNodes) {
        PcOverlayLayoutStore.DEFAULT_PC_NODES + profile.customNodes
    }

    // Khi vào chế độ sửa hoặc mở hộp nổi, luôn nhả sạch phím/chuột đang giữ để không kẹt nút
    LaunchedEffect(isEditMode, activePopoverNodeId, showFullKeyboardPicker) {
        if (isEditMode || activePopoverNodeId != null || showFullKeyboardPicker) {
            KeyboardMousePrototype.releaseAllAndUncapture("pc_editor_open")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            KeyboardMousePrototype.releaseAllAndUncapture("pc_touch_overlay_disposed")
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { rootSizePx = it }
            .testTag("pc_keyboard_mouse_overlay")
    ) {
        val maxWPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val globalScale = config.gamepadScale.coerceIn(0.75f, 1.35f)

        // 1. NỀN VUỐT CHUỘT KIỂU POJAVLAUNCHER (chỉ chạy khi hiện phím ảo PC, không sửa nút, và tắt Chạm trực tiếp)
        if (kbmState.shouldShowTouchPcOverlay && !isEditMode && !kbmState.directTouchPassThrough && activePopoverNodeId == null && !showFullKeyboardPicker) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(kbmState.tapBackgroundMouseClick) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastPos = down.position
                            val startPos = down.position
                            val downTime = SystemClock.uptimeMillis()
                            var dragged = false
                            var holdingRmb = false
                            val slopPx = 10.dp.toPx()

                            val longPressJob: Job? = if (kbmState.tapBackgroundMouseClick) {
                                scope.launch {
                                    delay(320L)
                                    if (!dragged) {
                                        holdingRmb = true
                                        KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("RMB"), true)
                                    }
                                }
                            } else null

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    val delta = change.position - lastPos
                                    lastPos = change.position
                                    if (!dragged && (change.position - startPos).getDistance() > slopPx) {
                                        dragged = true
                                        longPressJob?.cancel()
                                    }
                                    if (dragged && (delta.x != 0f || delta.y != 0f)) {
                                        KeyboardMousePrototype.dispatchTouchTrackpadDelta(delta.x, delta.y)
                                    }
                                }
                            } finally {
                                longPressJob?.cancel()
                                if (holdingRmb) {
                                    KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("RMB"), false)
                                } else if (!dragged && kbmState.tapBackgroundMouseClick && (SystemClock.uptimeMillis() - downTime) < 260L) {
                                    scope.launch {
                                        KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("LMB"), true)
                                        delay(55L)
                                        KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("LMB"), false)
                                    }
                                }
                                KeyboardMousePrototype.dispatchTouchTrackpadRelease()
                            }
                        }
                    }
                    .testTag("pc_pojav_swipe_surface")
            )
        }

        // 2. CÁC PHÍM ẢO PC & CẦN ANALOG WASD (hiện khi shouldShowTouchPcOverlay hoặc đang sửa bố cục)
        if (kbmState.shouldShowTouchPcOverlay || isEditMode) {
            allNodes.forEach { nodeDef ->
                val cust = getNodeCust(nodeDef.id)
                if (cust.hidden) return@forEach

                val effectiveScale = (globalScale * cust.scale).coerceIn(0.55f, 2.2f)
                val wDp = (nodeDef.defaultWidthDp * effectiveScale * (if (nodeDef.isAnalogWasd) 1f else cust.widthScale)).dp
                val hDp = (nodeDef.defaultHeightDp * effectiveScale * (if (nodeDef.isAnalogWasd) 1f else cust.heightScale)).dp
                val wPx = with(density) { wDp.toPx() }
                val hPx = with(density) { hDp.toPx() }

                val baseLeft = (nodeDef.defaultRelX * maxWPx - wPx / 2f)
                val baseTop = (nodeDef.defaultRelY * maxHPx - hPx / 2f)
                val curX = (baseLeft + cust.offsetX).coerceIn(0f, (maxWPx - wPx).coerceAtLeast(0f))
                val curY = (baseTop + cust.offsetY).coerceIn(0f, (maxHPx - hPx).coerceAtLeast(0f))

                var lastTapTimeMs by remember(nodeDef.id) { mutableLongStateOf(0L) }
                val isSelectedInEdit = activePopoverNodeId == nodeDef.id

                Box(
                    modifier = Modifier
                        .offset { IntOffset(curX.roundToInt(), curY.roundToInt()) }
                        .onGloballyPositioned { coords ->
                            nodeBoundsMap[nodeDef.id] = coords.boundsInRoot()
                        }
                        .alpha(if (isEditMode) cust.opacity.coerceAtLeast(0.65f) else cust.opacity)
                        .then(
                            if (isEditMode) {
                                Modifier.pointerInput(nodeDef.id, profile) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = true)
                                        down.consume()
                                        var moved = false
                                        var accDx = 0f
                                        var accDy = 0f
                                        val slop = 6.dp.toPx()
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Main)
                                            val ch = ev.changes.firstOrNull { it.id == down.id }
                                            if (ch == null || !ch.pressed) break
                                            val d = ch.position - ch.previousPosition
                                            ch.consume()
                                            accDx += d.x
                                            accDy += d.y
                                            if (!moved && hypot(accDx.toDouble(), accDy.toDouble()) > slop) {
                                                moved = true
                                            }
                                            if (moved && (d.x != 0f || d.y != 0f)) {
                                                updateNodeCust(nodeDef.id) { c ->
                                                    val nx = (c.offsetX + d.x).coerceIn(-baseLeft, maxWPx - wPx - baseLeft)
                                                    val ny = (c.offsetY + d.y).coerceIn(-baseTop, maxHPx - hPx - baseTop)
                                                    c.copy(offsetX = nx, offsetY = ny)
                                                }
                                            }
                                        }
                                        if (!moved) {
                                            val now = SystemClock.uptimeMillis()
                                            if (now - lastTapTimeMs <= 340L) {
                                                activePopoverNodeId = nodeDef.id
                                                lastTapTimeMs = 0L
                                            } else {
                                                lastTapTimeMs = now
                                            }
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    if (nodeDef.isAnalogWasd) {
                        PcAnalogWasdStick(
                            sizeDp = wDp,
                            isEditMode = isEditMode,
                            isSelected = isSelectedInEdit
                        )
                    } else {
                        PcVirtualActionButton(
                            nodeDef = nodeDef,
                            widthDp = wDp,
                            heightDp = hDp,
                            pressMode = cust.pressMode,
                            turboHz = cust.turboHz,
                            isEditMode = isEditMode,
                            isSelected = isSelectedInEdit
                        )
                    }
                }
            }
        }

        // 3. THANH NỔI CHỈNH SỬA GỌN Ở CẠNH TRÊN (không phải popup che màn hình)
        if (isEditMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 50.dp)
                    .zIndex(900f)
                    .testTag("pc_edit_floating_dock"),
                shape = RoundedCornerShape(12.dp),
                color = HudPanelBg,
                border = BorderStroke(1.dp, HudLine),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HudButton(
                        text = KbmPrototypeStrings.addKeyLabel(config),
                        onClick = {
                            activePopoverNodeId = null
                            showFullKeyboardPicker = !showFullKeyboardPicker
                        },
                        minHeight = 32.dp,
                        fontSize = 11.5.sp,
                        containerOverride = HudSelBg,
                        contentOverride = HudCyan,
                        modifier = Modifier.testTag("pc_dock_add_key_btn")
                    )
                    HudButton(
                        text = KbmPrototypeStrings.doneEditLabel(config),
                        onClick = {
                            isEditMode = false
                            showFullKeyboardPicker = false
                            activePopoverNodeId = null
                        },
                        style = HudButtonStyle.MAIN,
                        minHeight = 32.dp,
                        fontSize = 11.5.sp,
                        modifier = Modifier.testTag("pc_dock_done_btn")
                    )
                }
            }

            if (activePopoverNodeId == null && !showFullKeyboardPicker) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 26.dp, start = 16.dp, end = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = HudPanelBg,
                    border = BorderStroke(1.dp, HudLine),
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = KbmPrototypeStrings.doubleTapEditHint(config),
                        color = HudMuted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 4. BÀN PHÍM PC FULL-SIZE ĐỂ THÊM PHÍM ĐƠN / NÚT GỘP / CỤM PHÍM
        if (showFullKeyboardPicker) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(950f)
                    .background(HudScrimColor.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showFullKeyboardPicker = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                ) {
                    PcFullKeyboardPickerSheet(
                        config = config,
                        onAddSingleKey = { keyCode ->
                            val newNode = PcVirtualNodeDef(
                                id = "pc_custom_${System.currentTimeMillis()}",
                                keys = listOf(keyCode),
                                customLabel = keyCode,
                                isDefaultNode = false,
                                defaultRelX = 0.50f,
                                defaultRelY = 0.52f,
                                defaultWidthDp = if (keyCode.length >= 4) 64f else 48f,
                                defaultHeightDp = 44f,
                                accent = if (keyCode == "LMB" || keyCode == "RMB" || keyCode == "MMB") PcKeyAccent.CYAN else PcKeyAccent.NEUTRAL
                            )
                            persistProfile(profile.copy(customNodes = profile.customNodes + newNode))
                            showFullKeyboardPicker = false
                        },
                        onAddComboButton = { keysList ->
                            val newNode = PcVirtualNodeDef(
                                id = "pc_combo_${System.currentTimeMillis()}",
                                keys = keysList,
                                customLabel = keysList.joinToString("+"),
                                isDefaultNode = false,
                                defaultRelX = 0.50f,
                                defaultRelY = 0.52f,
                                defaultWidthDp = (52f + keysList.size * 12f).coerceIn(64f, 118f),
                                defaultHeightDp = 46f,
                                accent = PcKeyAccent.CYAN
                            )
                            persistProfile(profile.copy(customNodes = profile.customNodes + newNode))
                            showFullKeyboardPicker = false
                        },
                        onAddKeyCluster = { keysList ->
                            val baseTs = System.currentTimeMillis()
                            val count = keysList.size
                            val added = keysList.mapIndexed { idx, keyCode ->
                                val col = idx % 4
                                val row = idx / 4
                                val relX = (0.38f + col * 0.085f).coerceIn(0.10f, 0.90f)
                                val relY = (0.45f + row * 0.11f).coerceIn(0.15f, 0.85f)
                                PcVirtualNodeDef(
                                    id = "pc_cluster_${baseTs}_${idx}_${count}",
                                    keys = listOf(keyCode),
                                    customLabel = keyCode,
                                    isDefaultNode = false,
                                    defaultRelX = relX,
                                    defaultRelY = relY,
                                    defaultWidthDp = if (keyCode.length >= 4) 60f else 46f,
                                    defaultHeightDp = 44f,
                                    accent = PcKeyAccent.GREEN
                                )
                            }
                            persistProfile(profile.copy(customNodes = profile.customNodes + added))
                            showFullKeyboardPicker = false
                        },
                        onDismiss = { showFullKeyboardPicker = false }
                    )
                }
            }
        }

        // 5. HỘP NỔI TINH CHỈNH (✥ Cả bàn phím hoặc chỉnh từng phím khi nhấn đúp)
        val popNodeId = activePopoverNodeId
        if (popNodeId != null) {
            val anchorRect = nodeBoundsMap[popNodeId]
            val popWidthPx = with(density) { 264.dp.toPx() }
            val popMaxHeightPx = with(density) { 380.dp.toPx() }
            val marginPx = with(density) { 10.dp.toPx() }
            val viewportW = rootSizePx.width.toFloat().coerceAtLeast(popWidthPx + marginPx * 2f)
            val viewportH = rootSizePx.height.toFloat().coerceAtLeast(popMaxHeightPx + marginPx * 2f)

            val (popX, popY) = remember(popNodeId, anchorRect, viewportW, viewportH) {
                if (anchorRect == null || popNodeId == "all") {
                    val cx = ((anchorRect?.center?.x ?: (viewportW / 2f)) - popWidthPx / 2f)
                        .coerceIn(marginPx, (viewportW - popWidthPx - marginPx).coerceAtLeast(marginPx))
                    val cy = ((anchorRect?.bottom ?: 48f) + marginPx)
                        .coerceIn(marginPx, (viewportH - popMaxHeightPx - marginPx).coerceAtLeast(marginPx))
                    cx.roundToInt() to cy.roundToInt()
                } else {
                    var x = anchorRect.right + marginPx
                    if (x + popWidthPx > viewportW - marginPx) x = anchorRect.left - popWidthPx - marginPx
                    if (x < marginPx) x = anchorRect.center.x - popWidthPx / 2f
                    val clampedX = x.coerceIn(marginPx, (viewportW - popWidthPx - marginPx).coerceAtLeast(marginPx))
                    val clampedY = (anchorRect.top - marginPx)
                        .coerceIn(marginPx, (viewportH - popMaxHeightPx - marginPx).coerceAtLeast(marginPx))
                    clampedX.roundToInt() to clampedY.roundToInt()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(980f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { activePopoverNodeId = null }
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(popX, popY) }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                ) {
                    val targetDef = allNodes.firstOrNull { it.id == popNodeId }
                    PcNodePopover(
                        nodeId = popNodeId,
                        nodeDef = targetDef,
                        config = config,
                        isEditMode = isEditMode,
                        usePerGameLayout = profile.usePerGame,
                        hasActiveGameKey = !gameKey.isNullOrBlank(),
                        hasPerGameCustomLayout = hasPerGameCustom,
                        gameDisplayName = gameDisplayName,
                        nodeCust = getNodeCust(popNodeId),
                        onSwitchToGamepadMode = {
                            activePopoverNodeId = null
                            isEditMode = false
                            showFullKeyboardPicker = false
                            onSwitchToGamepadMode()
                        },
                        onToggleUsePerGame = { perGame ->
                            PcOverlayLayoutStore.setUsePerGame(context, perGame)
                            profile = PcOverlayLayoutStore.loadProfile(context, gameKey)
                            hasPerGameCustom = PcOverlayLayoutStore.hasPerGameProfile(context, gameKey)
                        },
                        onUseGlobalLayout = {
                            PcOverlayLayoutStore.clearPerGameProfile(context, gameKey)
                            profile = PcOverlayLayoutStore.loadProfile(context, gameKey)
                            hasPerGameCustom = false
                        },
                        onEnterEditMode = {
                            isEditMode = !isEditMode
                            if (!isEditMode) showFullKeyboardPicker = false
                            activePopoverNodeId = null
                        },
                        onShowAll = {
                            val nextMap = profile.customizations.mapValues { (_, c) -> c.copy(hidden = false) }
                            persistProfile(profile.copy(customizations = nextMap))
                        },
                        onResetAll = {
                            PcOverlayLayoutStore.resetProfile(context, gameKey, profile.usePerGame)
                            profile = PcOverlayLayoutStore.loadProfile(context, gameKey)
                            hasPerGameCustom = PcOverlayLayoutStore.hasPerGameProfile(context, gameKey)
                            activePopoverNodeId = null
                        },
                        onUpdateNodeCust = { nextCust ->
                            updateNodeCust(popNodeId) { nextCust }
                        },
                        onNudge = { dx, dy ->
                            updateNodeCust(popNodeId) { it.copy(offsetX = it.offsetX + dx, offsetY = it.offsetY + dy) }
                        },
                        onHideOrDeleteNode = {
                            if (targetDef?.isDefaultNode == false) {
                                val nextNodes = profile.customNodes.filterNot { it.id == popNodeId }
                                val nextCusts = profile.customizations - popNodeId
                                persistProfile(profile.copy(customNodes = nextNodes, customizations = nextCusts))
                            } else {
                                updateNodeCust(popNodeId) { it.copy(hidden = true) }
                            }
                            activePopoverNodeId = null
                        },
                        onResetNode = {
                            val nextMap = profile.customizations - popNodeId
                            persistProfile(profile.copy(customizations = nextMap))
                        },
                        onClose = { activePopoverNodeId = null }
                    )
                }
            }
        }

        // 6. NÚT TINH CHỈNH (⚙) LUÔN NỔI TRÊN CÙNG GIỮA MÀN HÌNH (đồng bộ với VirtualGamepad.kt)
        val isAllOpen = activePopoverNodeId == "all"
        val tuneDesc = KbmPrototypeStrings.allKeyboardTitle(config)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .zIndex(1000f)
                .onGloballyPositioned { coords ->
                    nodeBoundsMap["all"] = coords.boundsInRoot()
                }
                .size(width = 38.dp, height = 30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isAllOpen) HudSelBg else Color(0xCC142019))
                .border(
                    1.dp,
                    if (isAllOpen) PcAccentCyan else PcAccentCyan.copy(alpha = 0.55f),
                    RoundedCornerShape(10.dp)
                )
                .clickable {
                    showFullKeyboardPicker = false
                    activePopoverNodeId = if (isAllOpen) null else "all"
                }
                .semantics { contentDescription = tuneDesc }
                .testTag("pc_overlay_tune_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = tuneDesc,
                tint = PcAccentCyan,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Cần analog WASD ảo có khóa chạy nhanh Shift khi kéo lên trên cùng (giống Auto Sprint L3 của Tay cầm).
 */
@Composable
private fun PcAnalogWasdStick(
    sizeDp: Dp,
    isEditMode: Boolean,
    isSelected: Boolean
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var sprintLocked by remember { mutableStateOf(false) }

    LaunchedEffect(isEditMode) {
        if (isEditMode) {
            isDragging = false
            if (sprintLocked) {
                sprintLocked = false
                KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("SHIFT"), false)
            }
            knobOffset = Offset.Zero
            KeyboardMousePrototype.dispatchVirtualPcLeftStick(0f, 0f)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            KeyboardMousePrototype.dispatchVirtualPcLeftStick(0f, 0f)
            if (sprintLocked) {
                KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("SHIFT"), false)
            }
        }
    }

    val borderCol = when {
        isSelected -> HudWarn
        sprintLocked -> PcAccentCyan
        isDragging -> PcAccentGreen
        else -> PcKeyBorder
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .then(
                if (!isEditMode) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            val radius = (size.width / 2f) * 0.72f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            isDragging = true

                            fun applyPosition(pos: Offset) {
                                val raw = pos - center
                                val dist = raw.getDistance()
                                val clamped = if (dist > radius && dist > 0f) {
                                    Offset(raw.x / dist * radius, raw.y / dist * radius)
                                } else raw
                                knobOffset = clamped
                                val nx = (clamped.x / radius).coerceIn(-1f, 1f)
                                val ny = (clamped.y / radius).coerceIn(-1f, 1f)
                                KeyboardMousePrototype.dispatchVirtualPcLeftStick(nx, ny)

                                // Đẩy mạnh lên phía trên -> kích hoạt khóa chạy nhanh Shift
                                val shouldSprint = raw.y < -radius * 1.12f && kotlin.math.abs(nx) < 0.55f
                                if (shouldSprint && !sprintLocked) {
                                    sprintLocked = true
                                    KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("SHIFT"), true)
                                } else if (sprintLocked && ny > -0.25f) {
                                    sprintLocked = false
                                    KeyboardMousePrototype.dispatchVirtualPcKeySpecs(listOf("SHIFT"), false)
                                }
                            }

                            applyPosition(down.position)
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Main)
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch == null || !ch.pressed) break
                                    ch.consume()
                                    applyPosition(ch.position)
                                }
                            } finally {
                                isDragging = false
                                if (sprintLocked) {
                                    // Giữ chạy thẳng Shift khi người dùng đẩy quá ngưỡng khóa; chạm lại cần sẽ thả
                                    knobOffset = Offset(0f, -radius * 0.92f)
                                    KeyboardMousePrototype.dispatchVirtualPcLeftStick(0f, -1f)
                                } else {
                                    knobOffset = Offset.Zero
                                    KeyboardMousePrototype.dispatchVirtualPcLeftStick(0f, 0f)
                                }
                            }
                        }
                    }
                } else Modifier
            )
            .testTag("pc_analog_wasd_stick"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = PcKeyBg, radius = r - 2.dp.toPx(), center = c)
            drawCircle(
                color = borderCol,
                radius = r - 2.dp.toPx(),
                center = c,
                style = Stroke(width = if (isDragging || isSelected) 2.dp.toPx() else 1.2.dp.toPx())
            )
            val knobCenter = c + knobOffset
            drawCircle(
                color = if (sprintLocked) PcAccentCyan.copy(alpha = 0.35f) else if (isDragging) PcKeyBgPressed else Color(0xFF142019),
                radius = r * 0.42f,
                center = knobCenter
            )
            drawCircle(
                color = if (sprintLocked) PcAccentCyan else PcAccentGreen,
                radius = r * 0.42f,
                center = knobCenter,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (sprintLocked) "SHIFT" else "WASD",
                color = if (sprintLocked) PcAccentCyan else PcKeyText,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (!sprintLocked) {
                Text(
                    text = "▲ Shift",
                    color = PcKeySubText,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PcVirtualActionButton(
    nodeDef: PcVirtualNodeDef,
    widthDp: Dp,
    heightDp: Dp,
    pressMode: GamepadButtonPressMode,
    turboHz: Int,
    isEditMode: Boolean,
    isSelected: Boolean
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var toggleLatched by remember { mutableStateOf(false) }

    DisposableEffect(nodeDef.id, nodeDef.keys) {
        onDispose {
            if (pressed || toggleLatched) {
                pressed = false
                toggleLatched = false
                KeyboardMousePrototype.dispatchVirtualPcKeySpecs(nodeDef.keys, false)
            }
        }
    }

    val accentColor = when (nodeDef.accent) {
        PcKeyAccent.GREEN -> PcAccentGreen
        PcKeyAccent.CYAN -> PcAccentCyan
        PcKeyAccent.NEUTRAL -> PcKeyBorder
    }
    val active = pressed || toggleLatched
    val shape = RoundedCornerShape(10.dp)
    val label = nodeDef.displayLabel()

    Box(
        modifier = Modifier
            .size(width = widthDp, height = heightDp)
            .clip(shape)
            .background(if (active) PcKeyBgPressed else PcKeyBg)
            .border(
                width = if (isSelected) 2.dp else if (active) 1.5.dp else 1.dp,
                color = when {
                    isSelected -> HudWarn
                    active -> PcAccentGreen
                    else -> accentColor
                },
                shape = shape
            )
            .semantics { contentDescription = label }
            .testTag("pc_node_${nodeDef.id}")
            .then(
                if (!isEditMode) {
                    Modifier.pointerInput(nodeDef.id, pressMode, turboHz) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            when (pressMode) {
                                GamepadButtonPressMode.TOGGLE_HOLD -> {
                                    val next = !toggleLatched
                                    toggleLatched = next
                                    KeyboardMousePrototype.dispatchVirtualPcKeySpecs(nodeDef.keys, next)
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Main)
                                        val ch = ev.changes.firstOrNull { it.id == down.id }
                                        if (ch == null || !ch.pressed) break
                                        ch.consume()
                                    }
                                }
                                GamepadButtonPressMode.TURBO -> {
                                    pressed = true
                                    val halfPeriodMs = (500L / turboHz.coerceIn(4, 20)).coerceAtLeast(20L)
                                    val turboJob = scope.launch {
                                        var state = false
                                        try {
                                            while (true) {
                                                state = !state
                                                KeyboardMousePrototype.dispatchVirtualPcKeySpecs(nodeDef.keys, state)
                                                delay(halfPeriodMs)
                                            }
                                        } finally {
                                            if (state) {
                                                KeyboardMousePrototype.dispatchVirtualPcKeySpecs(nodeDef.keys, false)
                                            }
                                        }
                                    }
                                    try {
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Main)
                                            val ch = ev.changes.firstOrNull { it.id == down.id }
                                            if (ch == null || !ch.pressed) break
                                            ch.consume()
                                        }
                                    } finally {
                                        turboJob.cancel()
                                        pressed = false
                                    }
                                }
                                GamepadButtonPressMode.NORMAL -> {
                                    pressed = true
                                    KeyboardMousePrototype.dispatchVirtualPcKeySpecs(nodeDef.keys, true)
                                    try {
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Main)
                                            val ch = ev.changes.firstOrNull { it.id == down.id }
                                            if (ch == null || !ch.pressed) break
                                            ch.consume()
                                        }
                                    } finally {
                                        if (pressed) {
                                            pressed = false
                                            KeyboardMousePrototype.dispatchVirtualPcKeySpecs(nodeDef.keys, false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) PcAccentGreen else PcKeyText,
            fontSize = when {
                label.length >= 7 -> 9.5.sp
                label.length >= 4 -> 11.sp
                else -> 13.sp
            },
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}
