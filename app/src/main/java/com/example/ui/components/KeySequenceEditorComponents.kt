package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.data.KeySequenceCodec
import com.example.data.KeySequenceStep
import com.example.ui.theme.DirectCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxDark
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import com.example.ui.theme.XboxSurfaceElevated
import com.example.ui.theme.XboxSurfaceVariant

private val SeqSlotActiveBg = Color(0xFF0B1A1F)
private val SeqSlotBorderIdle = Color(0xFF2A3B32)

/**
 * Thanh chuỗi phím/nút trên cùng:
 * - Hiển thị từng bước (tối đa 6 bước), giữa 2 bước có mũi tên ghi số giây theo ngôn ngữ.
 * - Cuối thanh luôn có 1 ô trống (khi < 6 bước) để thêm bước kế tiếp (bỏ trống không tính).
 * - Bấm ô để chọn sửa hoặc xóa; bấm mũi tên để mở bảng chỉnh số giây.
 */
@Composable
internal fun AdaptivePopupScope.KeySequenceBar(
    steps: List<KeySequenceStep>,
    selectedIndex: Int, // 0..steps.lastIndex = ô đã có phím, steps.size = ô trống cuối
    editingArrowIndex: Int?,
    langCode: String,
    onSelectSlot: (Int) -> Unit,
    onClickArrow: (Int) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val slotMinW = rDp(60f)
    val slotH = rDp(48f)
    val arrowW = rDp(58f)

    // Tự cuộn mượt về cuối khi thêm bước mới
    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty() && selectedIndex >= steps.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(sectionCorner))
            .background(XboxSurface)
            .border(borderThin, XboxCardBorder, RoundedCornerShape(sectionCorner))
            .horizontalScroll(scrollState)
            .padding(horizontal = padInner, vertical = gapMed),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gapSmall)
    ) {
        steps.forEachIndexed { idx, step ->
            val label = KeySequenceCodec.getLocalizedCodeLabel(step.code, context)
            val isSelected = selectedIndex == idx
            val slotCd = stringResource(R.string.keybind_slot_cd, idx + 1, label)

            Surface(
                shape = RoundedCornerShape(btnCorner),
                color = if (isSelected) SeqSlotActiveBg else XboxSurfaceVariant,
                border = BorderStroke(
                    width = if (isSelected) borderFocus else borderThin,
                    color = if (isSelected) DirectCyan else SeqSlotBorderIdle
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = slotMinW, minHeight = slotH)
                    .clip(RoundedCornerShape(btnCorner))
                    .gamepadClickable(cornerRadius = btnCorner) { onSelectSlot(idx) }
                    .semantics { contentDescription = slotCd }
                    .testTag("keyseq_slot_$idx")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) DirectCyan else TextPrimary,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = rSp(13.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Mũi tên giữa bước idx và bước tiếp theo (hoặc tới ô trống nếu còn chỗ)
            val hasNextRealStep = idx < steps.lastIndex
            if (hasNextRealStep) {
                val delayTenths = step.delayAfterTenths
                val isZero = delayTenths == 0
                val isArrowSelected = editingArrowIndex == idx
                val formatted = KeySequenceCodec.formatDelayNumber(delayTenths, langCode)
                val secText = stringResource(R.string.keybind_seconds_short, formatted)
                val arrowCd = stringResource(R.string.keybind_delay_arrow_cd, formatted)
                val arrowColor = when {
                    isArrowSelected -> WarningAmber
                    isZero -> TextSecondary
                    else -> DirectCyan
                }

                Surface(
                    shape = RoundedCornerShape(chipCorner),
                    color = if (isArrowSelected) WarningAmber.copy(alpha = 0.14f) else Color.Transparent,
                    border = if (isArrowSelected) BorderStroke(borderThin, WarningAmber) else null,
                    modifier = Modifier
                        .defaultMinSize(minWidth = arrowW, minHeight = slotH)
                        .clip(RoundedCornerShape(chipCorner))
                        .gamepadClickable(cornerRadius = chipCorner) { onClickArrow(idx) }
                        .semantics { contentDescription = arrowCd }
                        .testTag("keyseq_arrow_$idx")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = gapSmall, vertical = gapSmall),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = secText,
                            color = arrowColor,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = rSp(11f),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(rDp(2f)))
                        SequenceArrowGraphic(
                            color = arrowColor,
                            modifier = Modifier.size(width = rDp(40f), height = rDp(12f))
                        )
                    }
                }
            } else if (steps.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
                // Mũi tên mờ dẫn sang ô trống cuối thanh
                SequenceArrowGraphic(
                    color = TextSecondary.copy(alpha = 0.55f),
                    modifier = Modifier
                        .padding(horizontal = gapSmall)
                        .size(width = rDp(28f), height = rDp(12f))
                )
            }
        }

        // Ô trống cuối thanh (nếu chưa đủ 6 bước)
        if (steps.size < KeySequenceCodec.MAX_SEQUENCE_STEPS) {
            val isEmptySelected = selectedIndex >= steps.size
            val emptyCd = stringResource(R.string.keybind_add_step_cd)
            val dashedColor = if (isEmptySelected) DirectCyan else SeqSlotBorderIdle
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = slotMinW, minHeight = slotH)
                    .clip(RoundedCornerShape(btnCorner))
                    .background(if (isEmptySelected) SeqSlotActiveBg else XboxSurfaceVariant.copy(alpha = 0.55f))
                    .gamepadClickable(cornerRadius = btnCorner) { onSelectSlot(steps.size) }
                    .semantics { contentDescription = emptyCd }
                    .testTag("keyseq_slot_empty"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val cornerPx = btnCorner.toPx()
                    drawRoundRect(
                        color = dashedColor,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                        style = Stroke(
                            width = (if (isEmptySelected) borderFocus else borderThin).toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                    )
                }
                Text(
                    text = stringResource(R.string.keybind_empty_slot),
                    color = if (isEmptySelected) DirectCyan else TextSecondary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = if (isEmptySelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = rSp(12f),
                    modifier = Modifier.padding(horizontal = padInner),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SequenceArrowGraphic(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val strokeW = (size.height * 0.16f).coerceAtLeast(1.8f)
        val startX = strokeW
        val endX = size.width - strokeW
        val headLen = size.height * 0.42f
        drawLine(
            color = color,
            start = Offset(startX, midY),
            end = Offset(endX, midY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(endX - headLen, midY - headLen),
            end = Offset(endX, midY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(endX - headLen, midY + headLen),
            end = Offset(endX, midY),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Bàn phím đầy đủ 6 hàng (Esc, F1–F12, số, chữ, Tab/Caps/Shift/Ctrl/Win/Alt/Space/Enter/Xóa, mũi tên)
 * kèm hàng 7 nút chuột bên dưới.
 * - Mỗi phím đơn luôn đạt tối thiểu rDp(40f) để bấm thoải mái bằng ngón tay.
 * - Khi màn hình hẹp hơn bề rộng tối thiểu của bàn phím, tự cuộn ngang mượt và KHÔNG cắt bớt phím.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptivePopupScope.FullKeyboardAndMousePad(
    activeCodes: Set<Int>,
    onPressCode: (Int) -> Unit
) {
    val kbScroll = rememberScrollState()
    val unitKeyW = rDp(40f)
    val keyH = rDp(42f)
    val keyGap = rDp(4f)
    val maxUnitsInRow = remember {
        KeySequenceCodec.FULL_KEYBOARD_ROWS.maxOf { row ->
            row.sumOf { it.weight.toDouble() }.toFloat()
        }
    }
    val maxKeyCountInRow = remember {
        KeySequenceCodec.FULL_KEYBOARD_ROWS.maxOf { it.size }
    }
    val minBoardWidth = unitKeyW * maxUnitsInRow + keyGap * (maxKeyCountInRow - 1) + padInner * 2

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gapMed)
    ) {
        // Khối bàn phím 6 hàng (cuộn ngang nếu màn hẹp)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(sectionCorner))
                .background(XboxSurface)
                .border(borderThin, XboxCardBorder, RoundedCornerShape(sectionCorner))
        ) {
            val boardWidth = if (maxWidth > minBoardWidth) maxWidth else minBoardWidth
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(kbScroll)
            ) {
                Column(
                    modifier = Modifier
                        .width(boardWidth)
                        .padding(padInner),
                    verticalArrangement = Arrangement.spacedBy(keyGap)
                ) {
                    KeySequenceCodec.FULL_KEYBOARD_ROWS.forEachIndexed { rowIdx, rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(keyGap)
                        ) {
                            rowKeys.forEachIndexed { colIdx, keySpec ->
                                if (keySpec.isSpacer) {
                                    Spacer(
                                        modifier = Modifier
                                            .weight(keySpec.weight)
                                            .height(keyH)
                                    )
                                } else {
                                    val isOn = activeCodes.contains(keySpec.code)
                                    val label = keySpec.labelResId?.let { stringResource(it) }
                                        ?: keySpec.defaultLabel
                                    val keyCd = stringResource(R.string.keybind_key_cd, label)
                                    Surface(
                                        shape = RoundedCornerShape(chipCorner),
                                        color = if (isOn) XboxSurfaceElevated else XboxSurfaceVariant,
                                        border = BorderStroke(
                                            width = if (isOn) borderFocus else borderThin,
                                            color = if (isOn) XboxNeonGreen else SeqSlotBorderIdle
                                        ),
                                        modifier = Modifier
                                            .weight(keySpec.weight)
                                            .height(keyH)
                                            .clip(RoundedCornerShape(chipCorner))
                                            .gamepadClickable(cornerRadius = chipCorner) {
                                                onPressCode(keySpec.code)
                                            }
                                            .semantics { contentDescription = keyCd }
                                            .testTag("kb_key_${rowIdx}_${colIdx}_${keySpec.code}")
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(horizontal = rDp(2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isOn) XboxNeonGreen else TextPrimary,
                                                fontFamily = SpaceGroteskFontFamily,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = if (label.length > 3) rSp(10.5f) else rSp(12f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Hàng 7 nút chuột (Trái, Phải, Giữa, Cuộn lên, Cuộn xuống, Nút bên 1, Nút bên 2)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gapSmall),
            verticalArrangement = Arrangement.spacedBy(gapSmall)
        ) {
            Text(
                text = stringResource(R.string.keybind_mouse_label),
                color = TextSecondary,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = rSp(12f),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = gapSmall)
            )
            KeySequenceCodec.MOUSE_BUTTON_KEYS.forEach { mouseKey ->
                val isOn = activeCodes.contains(mouseKey.code)
                val label = mouseKey.labelResId?.let { stringResource(it) } ?: mouseKey.defaultLabel
                Surface(
                    shape = RoundedCornerShape(chipCorner),
                    color = if (isOn) XboxSurfaceElevated else XboxSurfaceVariant,
                    border = BorderStroke(
                        width = if (isOn) borderFocus else borderThin,
                        color = if (isOn) XboxNeonGreen else SeqSlotBorderIdle
                    ),
                    modifier = Modifier
                        .defaultMinSize(minWidth = rDp(44f), minHeight = rDp(40f))
                        .clip(RoundedCornerShape(chipCorner))
                        .gamepadClickable(cornerRadius = chipCorner) {
                            onPressCode(mouseKey.code)
                        }
                        .semantics { contentDescription = label }
                        .testTag("kb_mouse_${mouseKey.code}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isOn) XboxNeonGreen else TextPrimary,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = rSp(12f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bảng nhỏ chỉnh thời gian chờ giữa 2 bước (đơn vị giây, tối thiểu 0, tối đa 5 giây):
 * - Nút − / + (mỗi lần 0,1 giây)
 * - Ô nhập số trực tiếp (chấp nhận cả dấu phẩy và dấu chấm, tự giới hạn 0..5)
 * - Các mức chọn nhanh: 0 / 0,1 / 0,2 / 0,5 / 1 / 2
 * - Điều khiển hoàn toàn bằng D-Pad, A xác nhận, B/Esc đóng.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DelayStepEditorDialog(
    fromLabel: String,
    toLabel: String,
    initialDelayTenths: Int,
    langCode: String,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var delayTenths by remember(initialDelayTenths) {
        mutableIntStateOf(
            initialDelayTenths.coerceIn(
                KeySequenceCodec.MIN_DELAY_TENTHS,
                KeySequenceCodec.MAX_DELAY_TENTHS
            )
        )
    }
    var rawText by remember(initialDelayTenths, langCode) {
        mutableStateOf(KeySequenceCodec.formatDelayNumber(delayTenths, langCode))
    }
    val doneFocusRequester = remember { FocusRequester() }
    val quickPresetTenths = remember { listOf(0, 1, 2, 5, 10, 20) }

    fun setTenths(next: Int) {
        val clamped = next.coerceIn(
            KeySequenceCodec.MIN_DELAY_TENTHS,
            KeySequenceCodec.MAX_DELAY_TENTHS
        )
        delayTenths = clamped
        rawText = KeySequenceCodec.formatDelayNumber(clamped, langCode)
    }

    AdaptivePopupFrame(
        onDismissRequest = onDismiss,
        dialogTestTag = "keyseq_delay_picker_dialog",
        defaultFocusRequester = doneFocusRequester,
        wideWidthFraction = 0.52f,
        portraitWidthFraction = 0.90f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = rDp(380f)),
            verticalArrangement = Arrangement.spacedBy(gapMed)
        ) {
            Text(
                text = stringResource(R.string.keybind_delay_from_to, fromLabel, toLabel),
                color = WarningAmber,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = rSp(14.5f)
            )

            // Hàng − | Nhập số giây trực tiếp | +
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val decCd = stringResource(R.string.keybind_delay_decrease)
                AdaptiveFlatActionButton(
                    onClick = { setTenths(delayTenths - 1) },
                    isPrimary = false,
                    containerColor = XboxSurfaceVariant,
                    borderColor = SeqSlotBorderIdle,
                    modifier = Modifier
                        .size(rDp(48f))
                        .semantics { contentDescription = decCd }
                        .testTag("keyseq_delay_minus_btn")
                ) {
                    Text(
                        text = "−",
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = rSp(20f)
                    )
                }

                // Ô nhập số trực tiếp + nhãn đơn vị giây
                Surface(
                    shape = RoundedCornerShape(btnCorner),
                    color = XboxDark,
                    border = BorderStroke(borderThin, WarningAmber),
                    modifier = Modifier
                        .weight(1f)
                        .height(rDp(48f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = padInner),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        BasicTextField(
                            value = rawText,
                            onValueChange = { typed ->
                                // Chỉ cho nhập chữ số và 1 dấu thập phân (. hoặc ,)
                                val filtered = typed.filter { it.isDigit() || it == ',' || it == '.' }.take(5)
                                rawText = filtered
                                KeySequenceCodec.parseDelayInputToTenths(filtered)?.let { parsed ->
                                    delayTenths = parsed
                                }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextPrimary,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = rSp(19f),
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(XboxNeonGreen),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val resolved = KeySequenceCodec.parseDelayInputToTenths(rawText) ?: delayTenths
                                    setTenths(resolved)
                                    onApply(resolved)
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("keyseq_delay_input_field"),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.Center) {
                                    if (rawText.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.keybind_delay_input_hint),
                                            color = TextSecondary.copy(alpha = 0.6f),
                                            fontSize = rSp(13f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(gapSmall))
                        Text(
                            text = stringResource(R.string.keybind_seconds_unit),
                            color = TextSecondary,
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = rSp(13f)
                        )
                    }
                }

                val incCd = stringResource(R.string.keybind_delay_increase)
                AdaptiveFlatActionButton(
                    onClick = { setTenths(delayTenths + 1) },
                    isPrimary = false,
                    containerColor = XboxSurfaceVariant,
                    borderColor = SeqSlotBorderIdle,
                    modifier = Modifier
                        .size(rDp(48f))
                        .semantics { contentDescription = incCd }
                        .testTag("keyseq_delay_plus_btn")
                ) {
                    Text(
                        text = "+",
                        color = TextPrimary,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = rSp(20f)
                    )
                }
            }

            // Các mức chọn nhanh: 0 / 0,1 / 0,2 / 0,5 / 1 / 2
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall),
                verticalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                quickPresetTenths.forEach { preset ->
                    val isSelected = delayTenths == preset
                    val formatted = KeySequenceCodec.formatDelayNumber(preset, langCode)
                    Surface(
                        shape = RoundedCornerShape(btnCorner),
                        color = if (isSelected) XboxSurfaceElevated else XboxSurfaceVariant,
                        border = BorderStroke(
                            width = if (isSelected) borderFocus else borderThin,
                            color = if (isSelected) XboxNeonGreen else SeqSlotBorderIdle
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = rDp(46f), minHeight = rDp(40f))
                            .clip(RoundedCornerShape(btnCorner))
                            .gamepadClickable(cornerRadius = btnCorner) { setTenths(preset) }
                            .semantics { contentDescription = formatted }
                            .testTag("keyseq_delay_preset_$preset")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = padInner, vertical = gapSmall),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatted,
                                color = if (isSelected) XboxNeonGreen else TextPrimary,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = rSp(13f)
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.keybind_delay_note),
                color = TextSecondary,
                fontFamily = SpaceGroteskFontFamily,
                fontSize = rSp(11.5f),
                lineHeight = rSp(16f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gapSmall)
            ) {
                AdaptiveFlatActionButton(
                    onClick = onDismiss,
                    isPrimary = false,
                    gamepadHint = "B",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("keyseq_delay_cancel_btn")
                ) {
                    Text(
                        text = stringResource(R.string.keybind_cancel),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = rSp(12.5f)
                    )
                }
                AdaptiveFlatActionButton(
                    onClick = {
                        val finalVal = KeySequenceCodec.parseDelayInputToTenths(rawText) ?: delayTenths
                        onApply(finalVal)
                    },
                    isPrimary = true,
                    gamepadHint = "A",
                    focusRequester = doneFocusRequester,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("keyseq_delay_done_btn")
                ) {
                    Text(
                        text = stringResource(R.string.keybind_done),
                        color = Color(0xFF04150B),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = rSp(12.5f)
                    )
                }
            }
        }
    }
}
