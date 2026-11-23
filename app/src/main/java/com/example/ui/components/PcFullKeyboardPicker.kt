package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudplay.KbmPrototypeStrings
import com.example.data.AppConfig

private data class PcFullKeySpec(
    val code: String,
    val label: String,
    val widthDp: Float = 38f
)

private val PC_FULL_KEYBOARD_ROWS: List<List<PcFullKeySpec>> = listOf(
    // Row 0: Mouse + Esc + Function keys
    listOf(
        PcFullKeySpec("LMB", "LMB", 44f),
        PcFullKeySpec("RMB", "RMB", 44f),
        PcFullKeySpec("MMB", "MMB", 44f),
        PcFullKeySpec("ESC", "Esc", 40f),
        PcFullKeySpec("F1", "F1", 34f),
        PcFullKeySpec("F2", "F2", 34f),
        PcFullKeySpec("F3", "F3", 34f),
        PcFullKeySpec("F4", "F4", 34f),
        PcFullKeySpec("F5", "F5", 34f),
        PcFullKeySpec("F6", "F6", 34f),
        PcFullKeySpec("F7", "F7", 34f),
        PcFullKeySpec("F8", "F8", 34f),
        PcFullKeySpec("F9", "F9", 34f),
        PcFullKeySpec("F10", "F10", 36f),
        PcFullKeySpec("F11", "F11", 36f),
        PcFullKeySpec("F12", "F12", 36f)
    ),
    // Row 1: Numbers
    listOf(
        PcFullKeySpec("`", "`", 34f),
        PcFullKeySpec("1", "1", 36f),
        PcFullKeySpec("2", "2", 36f),
        PcFullKeySpec("3", "3", 36f),
        PcFullKeySpec("4", "4", 36f),
        PcFullKeySpec("5", "5", 36f),
        PcFullKeySpec("6", "6", 36f),
        PcFullKeySpec("7", "7", 36f),
        PcFullKeySpec("8", "8", 36f),
        PcFullKeySpec("9", "9", 36f),
        PcFullKeySpec("0", "0", 36f),
        PcFullKeySpec("-", "-", 34f),
        PcFullKeySpec("=", "=", 34f),
        PcFullKeySpec("BKSP", "Bksp", 52f)
    ),
    // Row 2: QWERTY
    listOf(
        PcFullKeySpec("TAB", "Tab", 48f),
        PcFullKeySpec("Q", "Q", 36f),
        PcFullKeySpec("W", "W", 36f),
        PcFullKeySpec("E", "E", 36f),
        PcFullKeySpec("R", "R", 36f),
        PcFullKeySpec("T", "T", 36f),
        PcFullKeySpec("Y", "Y", 36f),
        PcFullKeySpec("U", "U", 36f),
        PcFullKeySpec("I", "I", 36f),
        PcFullKeySpec("O", "O", 36f),
        PcFullKeySpec("P", "P", 36f),
        PcFullKeySpec("[", "[", 34f),
        PcFullKeySpec("]", "]", 34f),
        PcFullKeySpec("\\", "\\", 36f)
    ),
    // Row 3: ASDF
    listOf(
        PcFullKeySpec("CAPS", "Caps", 54f),
        PcFullKeySpec("A", "A", 36f),
        PcFullKeySpec("S", "S", 36f),
        PcFullKeySpec("D", "D", 36f),
        PcFullKeySpec("F", "F", 36f),
        PcFullKeySpec("G", "G", 36f),
        PcFullKeySpec("H", "H", 36f),
        PcFullKeySpec("J", "J", 36f),
        PcFullKeySpec("K", "K", 36f),
        PcFullKeySpec("L", "L", 36f),
        PcFullKeySpec(";", ";", 34f),
        PcFullKeySpec("'", "'", 34f),
        PcFullKeySpec("ENTER", "Enter", 58f)
    ),
    // Row 4: ZXCV + Up
    listOf(
        PcFullKeySpec("SHIFT", "Shift", 62f),
        PcFullKeySpec("Z", "Z", 36f),
        PcFullKeySpec("X", "X", 36f),
        PcFullKeySpec("C", "C", 36f),
        PcFullKeySpec("V", "V", 36f),
        PcFullKeySpec("B", "B", 36f),
        PcFullKeySpec("N", "N", 36f),
        PcFullKeySpec("M", "M", 36f),
        PcFullKeySpec(",", ",", 34f),
        PcFullKeySpec(".", ".", 34f),
        PcFullKeySpec("/", "/", 34f),
        PcFullKeySpec("UP", "↑", 40f)
    ),
    // Row 5: Modifiers, Space, Arrows
    listOf(
        PcFullKeySpec("CTRL", "Ctrl", 54f),
        PcFullKeySpec("ALT", "Alt", 48f),
        PcFullKeySpec("SPACE", "Space", 148f),
        PcFullKeySpec("LEFT", "←", 40f),
        PcFullKeySpec("DOWN", "↓", 40f),
        PcFullKeySpec("RIGHT", "→", 40f)
    )
)

/**
 * Bàn phím PC Full-Size trực quan để người dùng chọn 1 phím bất kỳ,
 * hoặc chọn từ 2 phím trở lên để tạo Nút gộp (Combo) hoặc Cụm phím rời (Cluster).
 */
@Composable
internal fun PcFullKeyboardPickerSheet(
    config: AppConfig,
    onAddSingleKey: (String) -> Unit,
    onAddComboButton: (List<String>) -> Unit,
    onAddKeyCluster: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedKeys = remember { mutableStateListOf<String>() }

    Surface(
        modifier = modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .testTag("pc_full_keyboard_picker"),
        shape = RoundedCornerShape(16.dp),
        color = HudSurface2,
        border = BorderStroke(1.dp, HudLine),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header + Selected keys preview + Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = KbmPrototypeStrings.fullKeyboardTitle(config),
                        color = HudInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selectedKeys.isNotEmpty()) {
                        Text(
                            text = selectedKeys.joinToString(" + "),
                            color = HudCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (selectedKeys.size == 1) {
                    HudButton(
                        text = KbmPrototypeStrings.doneEditLabel(config),
                        onClick = {
                            val key = selectedKeys.first()
                            onAddSingleKey(key)
                        },
                        style = HudButtonStyle.MAIN,
                        minHeight = 32.dp,
                        fontSize = 11.5.sp,
                        modifier = Modifier.testTag("pc_picker_add_single_btn")
                    )
                } else if (selectedKeys.size >= 2) {
                    HudButton(
                        text = KbmPrototypeStrings.createComboBtnLabel(config),
                        onClick = { onAddComboButton(selectedKeys.toList()) },
                        minHeight = 32.dp,
                        fontSize = 11.sp,
                        containerOverride = HudSelBg,
                        contentOverride = HudCyan,
                        modifier = Modifier.testTag("pc_picker_add_combo_btn")
                    )
                    HudButton(
                        text = KbmPrototypeStrings.createClusterBtnLabel(config),
                        onClick = { onAddKeyCluster(selectedKeys.toList()) },
                        style = HudButtonStyle.MAIN,
                        minHeight = 32.dp,
                        fontSize = 11.sp,
                        modifier = Modifier.testTag("pc_picker_add_cluster_btn")
                    )
                }

                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onDismiss() }
                        .testTag("pc_picker_close_btn"),
                    shape = RoundedCornerShape(9.dp),
                    color = HudSurface,
                    shadowElevation = 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✕", color = HudMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Full-size keyboard rows (horizontally scrollable if viewport is narrow)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PC_FULL_KEYBOARD_ROWS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { spec ->
                            val isSelected = spec.code in selectedKeys
                            val isMouse = spec.code == "LMB" || spec.code == "RMB" || spec.code == "MMB"
                            val shape = RoundedCornerShape(7.dp)
                            Box(
                                modifier = Modifier
                                    .width(spec.widthDp.dp)
                                    .height(36.dp)
                                    .clip(shape)
                                    .background(if (isSelected) HudSelBg else HudSurface)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = when {
                                            isSelected -> HudAccent
                                            isMouse -> HudCyan.copy(alpha = 0.55f)
                                            else -> HudLine
                                        },
                                        shape = shape
                                    )
                                    .clickable {
                                        if (isSelected) {
                                            selectedKeys.remove(spec.code)
                                        } else {
                                            selectedKeys.add(spec.code)
                                        }
                                    }
                                    .testTag("pc_picker_key_${spec.code.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = spec.label,
                                    color = when {
                                        isSelected -> HudAccent
                                        isMouse -> HudCyan
                                        else -> HudInk
                                    },
                                    fontSize = if (spec.label.length >= 4) 10.sp else 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
