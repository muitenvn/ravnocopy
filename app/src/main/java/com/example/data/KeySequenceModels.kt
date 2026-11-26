package com.example.data

import android.content.Context
import android.view.KeyEvent
import com.example.R
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mô hình dữ liệu & phân tích cú pháp cho tính năng Gán phím mở rộng:
 * - Một phím bàn phím (Android KeyEvent.KEYCODE_* > 0) hoặc một nút chuột (mã âm -101..-107)
 * - Phím gộp (delayAfterTenths = 0: nhấn cùng lúc từ trái sang phải, nhả ngược lại)
 * - Chuỗi phím (delayAfterTenths = 1..50: 0,1s..5,0s, mỗi bước chạm ngắn, chờ tính từ lúc bắt đầu bước trước)
 * - Dùng chung khung thanh chuỗi cho nút tay cầm vật lý (mã âm -1000..-1024 ứng với slot 0..24)
 *
 * Tương thích ngược hoàn toàn với định dạng cũ trong `physicalCustomKeyMap` ("keyCode:slot,..."):
 * - Gán 1 phím bàn phím dương tiếp tục lưu dạng "keyCode:slot" để bản cũ đọc nguyên vẹn.
 * - Chuỗi nhiều bước, nút chuột hoặc chuỗi tay cầm lưu kèm dạng "S<slot>=code~delay|code~delay".
 */
object KeySequenceCodec {
    const val MAX_SEQUENCE_STEPS = 6
    const val MIN_DELAY_TENTHS = 0
    const val MAX_DELAY_TENTHS = 50 // 5,0 giây (bước 0,1 giây)

    // Mã định danh 7 nút chuột (âm, tách biệt hoàn toàn với Android KeyEvent.KEYCODE_*)
    const val MOUSE_LEFT = -101
    const val MOUSE_RIGHT = -102
    const val MOUSE_MIDDLE = -103
    const val MOUSE_WHEEL_UP = -104
    const val MOUSE_WHEEL_DOWN = -105
    const val MOUSE_SIDE_1 = -106
    const val MOUSE_SIDE_2 = -107

    // Mã định danh 25 nút / hướng cần tay cầm trong khung thanh chuỗi (-1000..-1024)
    private const val GAMEPAD_SLOT_OFFSET = -1000

    fun isKeyboardCode(code: Int): Boolean = code > 0
    fun isMouseCode(code: Int): Boolean = code in MOUSE_SIDE_2..MOUSE_LEFT
    fun isGamepadSlotCode(code: Int): Boolean =
        code <= GAMEPAD_SLOT_OFFSET && (GAMEPAD_SLOT_OFFSET - code) in 0..SettingsRepository.PHYS_INPUT_MAX_CODE

    fun fromGamepadSlot(slot: Int): Int = GAMEPAD_SLOT_OFFSET - slot
    fun toGamepadSlot(code: Int): Int? =
        if (isGamepadSlotCode(code)) GAMEPAD_SLOT_OFFSET - code else null

    fun isValidCode(code: Int): Boolean =
        isKeyboardCode(code) || isMouseCode(code) || isGamepadSlotCode(code)

    /**
     * Đọc toàn bộ gán phím/chuỗi từ chuỗi `physicalCustomKeyMap` (tương thích cả định dạng cũ và mới).
     * Trả về map: `targetXboxSlot (0..24) -> danh sách bước (1..6)`.
     */
    fun parseSlotBindings(raw: String): Map<Int, List<KeySequenceStep>> {
        if (raw.isBlank()) return emptyMap()
        val result = linkedMapOf<Int, List<KeySequenceStep>>()
        raw.split(",").forEach { entry ->
            val token = entry.trim()
            if (token.isEmpty()) return@forEach
            if (token.startsWith("S") && token.contains("=")) {
                val eqIdx = token.indexOf('=')
                val slot = token.substring(1, eqIdx).toIntOrNull()
                if (slot != null && slot in 0..SettingsRepository.PHYS_INPUT_MAX_CODE) {
                    val stepsPart = token.substring(eqIdx + 1)
                    val parsedSteps = stepsPart.split("|").mapNotNull { seg ->
                        val parts = seg.split("~")
                        val code = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                        if (!isValidCode(code)) return@mapNotNull null
                        val delay = (parts.getOrNull(1)?.toIntOrNull() ?: 0)
                            .coerceIn(MIN_DELAY_TENTHS, MAX_DELAY_TENTHS)
                        KeySequenceStep(code = code, delayAfterTenths = delay)
                    }.take(MAX_SEQUENCE_STEPS)
                    if (parsedSteps.isNotEmpty()) {
                        result[slot] = normalizeSteps(parsedSteps)
                    }
                }
            } else {
                // Định dạng cũ: "androidKeyCode:targetSlot"
                val parts = token.split(":")
                if (parts.size == 2) {
                    val keyCode = parts[0].toIntOrNull()
                    val slot = parts[1].toIntOrNull()
                    if (keyCode != null && keyCode > 0 && slot != null && slot in 0..SettingsRepository.PHYS_INPUT_MAX_CODE) {
                        // Chỉ bổ sung nếu slot chưa có bản định nghĩa S<slot>=...
                        if (!result.containsKey(slot)) {
                            result[slot] = listOf(KeySequenceStep(code = keyCode, delayAfterTenths = 0))
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Tuần tự hóa map `slot -> steps` về chuỗi lưu trong `physicalCustomKeyMap`.
     * Mọi mục 1 phím bàn phím dương xuất đúng định dạng cũ `"keyCode:slot"` để giữ tương thích ngược.
     */
    fun serializeSlotBindings(bindings: Map<Int, List<KeySequenceStep>>): String {
        val tokens = mutableListOf<String>()
        bindings.entries
            .filter { it.key in 0..SettingsRepository.PHYS_INPUT_MAX_CODE && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .forEach { (slot, rawSteps) ->
                val steps = normalizeSteps(rawSteps.filter { isValidCode(it.code) }.take(MAX_SEQUENCE_STEPS))
                if (steps.isEmpty()) return@forEach
                if (steps.size == 1 && isKeyboardCode(steps[0].code)) {
                    tokens.add("${steps[0].code}:$slot")
                } else {
                    val body = steps.joinToString("|") { step ->
                        "${step.code}~${step.delayAfterTenths.coerceIn(MIN_DELAY_TENTHS, MAX_DELAY_TENTHS)}"
                    }
                    tokens.add("S$slot=$body")
                }
            }
        return tokens.joinToString(",")
    }

    fun normalizeSteps(steps: List<KeySequenceStep>): List<KeySequenceStep> {
        if (steps.isEmpty()) return emptyList()
        val trimmed = steps.take(MAX_SEQUENCE_STEPS)
        return trimmed.mapIndexed { idx, step ->
            val clampedDelay = if (idx == trimmed.lastIndex) {
                0
            } else {
                step.delayAfterTenths.coerceIn(MIN_DELAY_TENTHS, MAX_DELAY_TENTHS)
            }
            step.copy(delayAfterTenths = clampedDelay)
        }
    }

    /**
     * Tìm xem chuỗi/phím người dùng đang chọn có trùng với một nút tay cầm khác hay không.
     * - Kiểm tra trùng phím đơn hoặc trùng toàn bộ chuỗi/phím gộp với slot khác.
     */
    fun findConflict(
        allBindings: Map<Int, List<KeySequenceStep>>,
        editingSlot: Int,
        candidateSteps: List<KeySequenceStep>
    ): DuplicateBindingConflict? {
        val clean = normalizeSteps(candidateSteps)
        if (clean.isEmpty()) return null
        val isSingleKeyboardOrMouse = clean.size == 1 && (isKeyboardCode(clean[0].code) || isMouseCode(clean[0].code))

        for ((otherSlot, otherSteps) in allBindings) {
            if (otherSlot == editingSlot || otherSteps.isEmpty()) continue
            val normalizedOther = normalizeSteps(otherSteps)
            // Trùng hệt chuỗi/phím gộp
            if (normalizedOther == clean) {
                return DuplicateBindingConflict(
                    otherSlot = otherSlot,
                    conflictCode = if (clean.size == 1) clean[0].code else null,
                    conflictSteps = clean
                )
            }
            // Trùng phím đơn bàn phím/chuột đang được dùng bởi nút khác
            if (isSingleKeyboardOrMouse && normalizedOther.size == 1 && normalizedOther[0].code == clean[0].code) {
                return DuplicateBindingConflict(
                    otherSlot = otherSlot,
                    conflictCode = clean[0].code,
                    conflictSteps = clean
                )
            }
        }
        return null
    }

    /**
     * Kiểm tra xung đột ngay khi bấm chọn 1 phím đơn trên bàn phím/chuột.
     */
    fun findSingleKeyConflict(
        allBindings: Map<Int, List<KeySequenceStep>>,
        editingSlot: Int,
        code: Int
    ): DuplicateBindingConflict? {
        if (!isKeyboardCode(code) && !isMouseCode(code)) return null
        for ((otherSlot, otherSteps) in allBindings) {
            if (otherSlot == editingSlot || otherSteps.isEmpty()) continue
            if (otherSteps.size == 1 && otherSteps[0].code == code) {
                return DuplicateBindingConflict(
                    otherSlot = otherSlot,
                    conflictCode = code,
                    conflictSteps = otherSteps
                )
            }
        }
        return null
    }

    /**
     * Định dạng số giây theo dấu thập phân của ngôn ngữ hiện tại (Việt/Đức/Pháp/TBN/BĐN/Nga/Indo dùng dấu phẩy).
     */
    fun formatDelayNumber(delayTenths: Int, langCode: String): String {
        val clamped = delayTenths.coerceIn(MIN_DELAY_TENTHS, MAX_DELAY_TENTHS)
        val whole = clamped / 10
        val frac = clamped % 10
        if (frac == 0) return whole.toString()
        val sep = resolveDecimalSeparator(langCode)
        return "$whole$sep$frac"
    }

    fun resolveDecimalSeparator(langCode: String): Char {
        return when (langCode.lowercase(Locale.ROOT).substringBefore('-')) {
            "vi", "de", "fr", "es", "pt", "ru", "id", "in" -> ','
            "en", "ja", "ko", "zh", "th" -> '.'
            else -> runCatching {
                DecimalFormatSymbols.getInstance(Locale.forLanguageTag(langCode)).decimalSeparator
            }.getOrDefault('.')
        }
    }

    /**
     * Đọc số giây do người dùng nhập trực tiếp (nhận cả dấu phẩy và dấu chấm), giới hạn 0..5 giây.
     */
    fun parseDelayInputToTenths(rawInput: String): Int? {
        val cleaned = rawInput.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val parsed = cleaned.toDoubleOrNull() ?: return null
        if (parsed.isNaN() || parsed.isInfinite()) return null
        val clamped = parsed.coerceIn(0.0, 5.0)
        return (clamped * 10.0).roundToInt().coerceIn(MIN_DELAY_TENTHS, MAX_DELAY_TENTHS)
    }

    /**
     * Nhãn cực ngắn hiển thị trên badge của sơ đồ hình tay cầm.
     */
    fun formatCompactBadge(steps: List<KeySequenceStep>): String? {
        if (steps.isEmpty()) return null
        if (steps.size == 1) return getShortCodeLabel(steps[0].code)
        val first = getShortCodeLabel(steps[0].code)
        val second = getShortCodeLabel(steps[1].code)
        val allChord = steps.dropLast(1).all { it.delayAfterTenths == 0 }
        return when {
            steps.size == 2 && allChord -> "$first+$second"
            steps.size == 2 -> "$first→$second"
            allChord -> "$first+${steps.size - 1}"
            else -> "$first→${steps.size}"
        }
    }

    fun getShortCodeLabel(code: Int): String = when (code) {
        MOUSE_LEFT -> "M1"
        MOUSE_RIGHT -> "M2"
        MOUSE_MIDDLE -> "M3"
        MOUSE_WHEEL_UP -> "W↑"
        MOUSE_WHEEL_DOWN -> "W↓"
        MOUSE_SIDE_1 -> "M4"
        MOUSE_SIDE_2 -> "M5"
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_CAPS_LOCK -> "Caps"
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Win"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Ctrl"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        KeyEvent.KEYCODE_DEL -> "Del"
        else -> {
            val padSlot = toGamepadSlot(code)
            if (padSlot != null) {
                SettingsRepository.getGamepadButtonShortLabel(padSlot)
            } else {
                SettingsRepository.getHardwareKeyShortLabel(code)
            }
        }
    }

    /**
     * Nhãn hiển thị rõ ràng theo 12 ngôn ngữ (cho ô chuỗi, thông báo trùng phím, tiêu đề chỉnh thời gian).
     */
    fun getLocalizedCodeLabel(code: Int, context: Context): String = when (code) {
        MOUSE_LEFT -> context.getString(R.string.keybind_mouse_left)
        MOUSE_RIGHT -> context.getString(R.string.keybind_mouse_right)
        MOUSE_MIDDLE -> context.getString(R.string.keybind_mouse_middle)
        MOUSE_WHEEL_UP -> context.getString(R.string.keybind_mouse_wheel_up)
        MOUSE_WHEEL_DOWN -> context.getString(R.string.keybind_mouse_wheel_down)
        MOUSE_SIDE_1 -> context.getString(R.string.keybind_mouse_side_1)
        MOUSE_SIDE_2 -> context.getString(R.string.keybind_mouse_side_2)
        KeyEvent.KEYCODE_DEL -> context.getString(R.string.keybind_key_backspace)
        else -> getShortCodeLabel(code)
    }

    /**
     * Chuyển Android KeyEvent keyCode sang thông số DOM KeyboardEvent chuẩn cho WebView.
     */
    fun getDomKeyDescriptor(keyCode: Int): DomKeyDescriptor {
        return when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> DomKeyDescriptor("Escape", "Escape", 27)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                val n = keyCode - KeyEvent.KEYCODE_F1 + 1
                DomKeyDescriptor("F$n", "F$n", 111 + n)
            }
            KeyEvent.KEYCODE_GRAVE -> DomKeyDescriptor("`", "Backquote", 192)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val d = keyCode - KeyEvent.KEYCODE_0
                DomKeyDescriptor("$d", "Digit$d", 48 + d)
            }
            KeyEvent.KEYCODE_MINUS -> DomKeyDescriptor("-", "Minus", 189)
            KeyEvent.KEYCODE_EQUALS -> DomKeyDescriptor("=", "Equal", 187)
            KeyEvent.KEYCODE_DEL -> DomKeyDescriptor("Backspace", "Backspace", 8)
            KeyEvent.KEYCODE_TAB -> DomKeyDescriptor("Tab", "Tab", 9)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val ch = ('A'.code + (keyCode - KeyEvent.KEYCODE_A)).toChar()
                DomKeyDescriptor(ch.lowercaseChar().toString(), "Key$ch", ch.code)
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> DomKeyDescriptor("[", "BracketLeft", 219)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> DomKeyDescriptor("]", "BracketRight", 221)
            KeyEvent.KEYCODE_BACKSLASH -> DomKeyDescriptor("\\", "Backslash", 220)
            KeyEvent.KEYCODE_CAPS_LOCK -> DomKeyDescriptor("CapsLock", "CapsLock", 20)
            KeyEvent.KEYCODE_SEMICOLON -> DomKeyDescriptor(";", "Semicolon", 186)
            KeyEvent.KEYCODE_APOSTROPHE -> DomKeyDescriptor("'", "Quote", 222)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> DomKeyDescriptor("Enter", "Enter", 13)
            KeyEvent.KEYCODE_SHIFT_LEFT -> DomKeyDescriptor("Shift", "ShiftLeft", 16)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> DomKeyDescriptor("Shift", "ShiftRight", 16)
            KeyEvent.KEYCODE_COMMA -> DomKeyDescriptor(",", "Comma", 188)
            KeyEvent.KEYCODE_PERIOD -> DomKeyDescriptor(".", "Period", 190)
            KeyEvent.KEYCODE_SLASH -> DomKeyDescriptor("/", "Slash", 191)
            KeyEvent.KEYCODE_CTRL_LEFT -> DomKeyDescriptor("Control", "ControlLeft", 17)
            KeyEvent.KEYCODE_CTRL_RIGHT -> DomKeyDescriptor("Control", "ControlRight", 17)
            KeyEvent.KEYCODE_META_LEFT -> DomKeyDescriptor("Meta", "MetaLeft", 91)
            KeyEvent.KEYCODE_META_RIGHT -> DomKeyDescriptor("Meta", "MetaRight", 92)
            KeyEvent.KEYCODE_ALT_LEFT -> DomKeyDescriptor("Alt", "AltLeft", 18)
            KeyEvent.KEYCODE_ALT_RIGHT -> DomKeyDescriptor("Alt", "AltRight", 18)
            KeyEvent.KEYCODE_SPACE -> DomKeyDescriptor(" ", "Space", 32)
            KeyEvent.KEYCODE_DPAD_LEFT -> DomKeyDescriptor("ArrowLeft", "ArrowLeft", 37)
            KeyEvent.KEYCODE_DPAD_UP -> DomKeyDescriptor("ArrowUp", "ArrowUp", 38)
            KeyEvent.KEYCODE_DPAD_RIGHT -> DomKeyDescriptor("ArrowRight", "ArrowRight", 39)
            KeyEvent.KEYCODE_DPAD_DOWN -> DomKeyDescriptor("ArrowDown", "ArrowDown", 40)
            else -> {
                val lbl = getShortCodeLabel(keyCode)
                DomKeyDescriptor(lbl, lbl, keyCode)
            }
        }
    }

    /**
     * Bố cục bàn phím đầy đủ 6 hàng theo chuẩn giao diện Ravno-ban-phim-chuot-v4.
     */
    val FULL_KEYBOARD_ROWS: List<List<VirtualKeySpec>> = listOf(
        // Hàng 1: Esc + F1..F12
        listOf(
            VirtualKeySpec(KeyEvent.KEYCODE_ESCAPE, "Esc", 1.4f),
            VirtualKeySpec.spacer(0.4f),
            VirtualKeySpec(KeyEvent.KEYCODE_F1, "F1"),
            VirtualKeySpec(KeyEvent.KEYCODE_F2, "F2"),
            VirtualKeySpec(KeyEvent.KEYCODE_F3, "F3"),
            VirtualKeySpec(KeyEvent.KEYCODE_F4, "F4"),
            VirtualKeySpec.spacer(0.4f),
            VirtualKeySpec(KeyEvent.KEYCODE_F5, "F5"),
            VirtualKeySpec(KeyEvent.KEYCODE_F6, "F6"),
            VirtualKeySpec(KeyEvent.KEYCODE_F7, "F7"),
            VirtualKeySpec(KeyEvent.KEYCODE_F8, "F8"),
            VirtualKeySpec.spacer(0.4f),
            VirtualKeySpec(KeyEvent.KEYCODE_F9, "F9"),
            VirtualKeySpec(KeyEvent.KEYCODE_F10, "F10"),
            VirtualKeySpec(KeyEvent.KEYCODE_F11, "F11"),
            VirtualKeySpec(KeyEvent.KEYCODE_F12, "F12")
        ),
        // Hàng 2: Hàng số + Xóa
        listOf(
            VirtualKeySpec(KeyEvent.KEYCODE_GRAVE, "`"),
            VirtualKeySpec(KeyEvent.KEYCODE_1, "1"),
            VirtualKeySpec(KeyEvent.KEYCODE_2, "2"),
            VirtualKeySpec(KeyEvent.KEYCODE_3, "3"),
            VirtualKeySpec(KeyEvent.KEYCODE_4, "4"),
            VirtualKeySpec(KeyEvent.KEYCODE_5, "5"),
            VirtualKeySpec(KeyEvent.KEYCODE_6, "6"),
            VirtualKeySpec(KeyEvent.KEYCODE_7, "7"),
            VirtualKeySpec(KeyEvent.KEYCODE_8, "8"),
            VirtualKeySpec(KeyEvent.KEYCODE_9, "9"),
            VirtualKeySpec(KeyEvent.KEYCODE_0, "0"),
            VirtualKeySpec(KeyEvent.KEYCODE_MINUS, "-"),
            VirtualKeySpec(KeyEvent.KEYCODE_EQUALS, "="),
            VirtualKeySpec(KeyEvent.KEYCODE_DEL, "Del", 2.0f, labelResId = R.string.keybind_key_backspace)
        ),
        // Hàng 3: Tab + QWERTY
        listOf(
            VirtualKeySpec(KeyEvent.KEYCODE_TAB, "Tab", 1.5f),
            VirtualKeySpec(KeyEvent.KEYCODE_Q, "Q"),
            VirtualKeySpec(KeyEvent.KEYCODE_W, "W"),
            VirtualKeySpec(KeyEvent.KEYCODE_E, "E"),
            VirtualKeySpec(KeyEvent.KEYCODE_R, "R"),
            VirtualKeySpec(KeyEvent.KEYCODE_T, "T"),
            VirtualKeySpec(KeyEvent.KEYCODE_Y, "Y"),
            VirtualKeySpec(KeyEvent.KEYCODE_U, "U"),
            VirtualKeySpec(KeyEvent.KEYCODE_I, "I"),
            VirtualKeySpec(KeyEvent.KEYCODE_O, "O"),
            VirtualKeySpec(KeyEvent.KEYCODE_P, "P"),
            VirtualKeySpec(KeyEvent.KEYCODE_LEFT_BRACKET, "["),
            VirtualKeySpec(KeyEvent.KEYCODE_RIGHT_BRACKET, "]"),
            VirtualKeySpec(KeyEvent.KEYCODE_BACKSLASH, "\\", 1.5f)
        ),
        // Hàng 4: Caps + ASDFGHJKL + Enter
        listOf(
            VirtualKeySpec(KeyEvent.KEYCODE_CAPS_LOCK, "Caps", 1.8f),
            VirtualKeySpec(KeyEvent.KEYCODE_A, "A"),
            VirtualKeySpec(KeyEvent.KEYCODE_S, "S"),
            VirtualKeySpec(KeyEvent.KEYCODE_D, "D"),
            VirtualKeySpec(KeyEvent.KEYCODE_F, "F"),
            VirtualKeySpec(KeyEvent.KEYCODE_G, "G"),
            VirtualKeySpec(KeyEvent.KEYCODE_H, "H"),
            VirtualKeySpec(KeyEvent.KEYCODE_J, "J"),
            VirtualKeySpec(KeyEvent.KEYCODE_K, "K"),
            VirtualKeySpec(KeyEvent.KEYCODE_L, "L"),
            VirtualKeySpec(KeyEvent.KEYCODE_SEMICOLON, ";"),
            VirtualKeySpec(KeyEvent.KEYCODE_APOSTROPHE, "'"),
            VirtualKeySpec(KeyEvent.KEYCODE_ENTER, "Enter", 2.2f)
        ),
        // Hàng 5: Shift + ZXCVBNM + Shift
        listOf(
            VirtualKeySpec(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", 2.3f),
            VirtualKeySpec(KeyEvent.KEYCODE_Z, "Z"),
            VirtualKeySpec(KeyEvent.KEYCODE_X, "X"),
            VirtualKeySpec(KeyEvent.KEYCODE_C, "C"),
            VirtualKeySpec(KeyEvent.KEYCODE_V, "V"),
            VirtualKeySpec(KeyEvent.KEYCODE_B, "B"),
            VirtualKeySpec(KeyEvent.KEYCODE_N, "N"),
            VirtualKeySpec(KeyEvent.KEYCODE_M, "M"),
            VirtualKeySpec(KeyEvent.KEYCODE_COMMA, ","),
            VirtualKeySpec(KeyEvent.KEYCODE_PERIOD, "."),
            VirtualKeySpec(KeyEvent.KEYCODE_SLASH, "/"),
            VirtualKeySpec(KeyEvent.KEYCODE_SHIFT_RIGHT, "Shift", 2.7f)
        ),
        // Hàng 6: Ctrl / Win / Alt / Space / Alt / Ctrl / Mũi tên
        listOf(
            VirtualKeySpec(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", 1.4f),
            VirtualKeySpec(KeyEvent.KEYCODE_META_LEFT, "Win", 1.2f),
            VirtualKeySpec(KeyEvent.KEYCODE_ALT_LEFT, "Alt", 1.2f),
            VirtualKeySpec(KeyEvent.KEYCODE_SPACE, "Space", 6.4f),
            VirtualKeySpec(KeyEvent.KEYCODE_ALT_RIGHT, "Alt", 1.2f),
            VirtualKeySpec(KeyEvent.KEYCODE_CTRL_RIGHT, "Ctrl", 1.4f),
            VirtualKeySpec(KeyEvent.KEYCODE_DPAD_LEFT, "←", 1.0f),
            VirtualKeySpec(KeyEvent.KEYCODE_DPAD_UP, "↑", 1.0f),
            VirtualKeySpec(KeyEvent.KEYCODE_DPAD_DOWN, "↓", 1.0f),
            VirtualKeySpec(KeyEvent.KEYCODE_DPAD_RIGHT, "→", 1.0f)
        )
    )

    val MOUSE_BUTTON_KEYS: List<VirtualKeySpec> = listOf(
        VirtualKeySpec(MOUSE_LEFT, "M1", 1.0f, labelResId = R.string.keybind_mouse_left),
        VirtualKeySpec(MOUSE_RIGHT, "M2", 1.0f, labelResId = R.string.keybind_mouse_right),
        VirtualKeySpec(MOUSE_MIDDLE, "M3", 1.0f, labelResId = R.string.keybind_mouse_middle),
        VirtualKeySpec(MOUSE_WHEEL_UP, "W↑", 1.0f, labelResId = R.string.keybind_mouse_wheel_up),
        VirtualKeySpec(MOUSE_WHEEL_DOWN, "W↓", 1.0f, labelResId = R.string.keybind_mouse_wheel_down),
        VirtualKeySpec(MOUSE_SIDE_1, "M4", 1.0f, labelResId = R.string.keybind_mouse_side_1),
        VirtualKeySpec(MOUSE_SIDE_2, "M5", 1.0f, labelResId = R.string.keybind_mouse_side_2)
    )
}

data class KeySequenceStep(
    val code: Int,
    val delayAfterTenths: Int = 0 // 0 = nhấn cùng lúc (phím gộp), 1..50 = 0,1s..5,0s
)

data class DuplicateBindingConflict(
    val otherSlot: Int,
    val conflictCode: Int?,
    val conflictSteps: List<KeySequenceStep>
)

data class DomKeyDescriptor(
    val key: String,
    val code: String,
    val legacyKeyCode: Int
)

data class VirtualKeySpec(
    val code: Int,
    val defaultLabel: String,
    val weight: Float = 1.0f,
    val isSpacer: Boolean = false,
    val labelResId: Int? = null
) {
    companion object {
        fun spacer(weight: Float) = VirtualKeySpec(
            code = 0,
            defaultLabel = "",
            weight = weight,
            isSpacer = true
        )
    }
}
