package com.example.cloudplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import com.example.data.KeySequenceCodec
import com.example.data.KeySequenceStep
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Kết quả bắt phím vật lý bất kỳ từ người dùng:
 * - [StandardGamepad]: nút tay cầm chuẩn (`0..16`) hoặc 8 hướng cần analog (`17..24`).
 * - [CustomHardwareKey]: BẤT KỲ phím cứng bàn phím/remote nào (`KeyEvent.KEYCODE_* > 0`)
 *   hoặc nút chuột vật lý (`KeySequenceCodec.MOUSE_* < 0`).
 */
sealed class CapturedPhysicalInput {
    data class StandardGamepad(val code: Int) : CapturedPhysicalInput()
    data class CustomHardwareKey(val androidKeyCode: Int) : CapturedPhysicalInput()
}

/**
 * Cầu nối bắt phím, nút chuột & hướng gạt cần trực tiếp từ MỌI thiết bị nhập vật lý,
 * và thực thi an toàn trong game cho:
 * - Một phím / nút chuột (tương thích ngược hoàn toàn với `physicalCustomKeyMap`)
 * - Phím gộp (0 giây): nhấn theo thứ tự từ trái sang phải, giữ suốt lúc nút kích hoạt còn giữ,
 *   nhả theo thứ tự ngược lại.
 * - Chuỗi phím (>0 giây): mỗi bước là một lần chạm ngắn; thời gian chờ tính từ lúc bắt đầu bước trước;
 *   chạy một lần mỗi lần nhấn nút kích hoạt.
 * - Nhả sạch mọi phím và hủy mọi bước đang chờ khi mở popup/menu app, mất focus, tạm dừng,
 *   thoát game hoặc hủy giữa chừng.
 */
object PhysicalGamepadCaptureBus {
    private const val SEQUENCE_TAP_DURATION_MS = 45L
    private const val WHEEL_PULSE_DURATION_MS = 55L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _livePressedCodes = MutableStateFlow<Set<Int>>(emptySet())
    val livePressedCodes: StateFlow<Set<Int>> = _livePressedCodes.asStateFlow()

    private val _liveCustomKeyCodes = MutableStateFlow<Set<Int>>(emptySet())
    val liveCustomKeyCodes: StateFlow<Set<Int>> = _liveCustomKeyCodes.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile
    private var monitorActive: Boolean = false

    @Volatile
    private var captureCallback: ((CapturedPhysicalInput) -> Unit)? = null

    @Volatile
    private var cancelCallback: (() -> Unit)? = null

    @Volatile
    private var captureArmedAtUptimeMs: Long = 0L

    private val heldKeys = HashSet<Int>()
    private val heldCustomKeys = HashSet<Int>()
    private val heldMotionCodes = HashSet<Int>()
    private val ignoredStdUntilRelease = HashSet<Int>()
    private val ignoredCustomUntilRelease = HashSet<Int>()

    // --- Runtime trong game ---
    @Volatile
    private var inGameWebViewRef: WeakReference<WebView>? = null

    // Các phím bàn phím / nút chuột vật lý đang giữ thực sự
    private val heldPhysicalInputCodes = LinkedHashSet<Int>()

    // Trạng thái mặt nạ nút chuột lần trước để phát hiện cạnh nhấn/nhả đối xứng
    private var lastMouseButtonsMask: Int = 0

    // Ánh xạ trigger đang giữ -> slot Xbox ảo đang được giữ (chiều Phím/Chuột/Phím gộp -> Slot Xbox)
    private val activeInputToSlotHolds = HashMap<Int, Int>() // singleInputCode -> slot
    private val activeChordSlotHolds = HashMap<Int, Set<Int>>() // slot -> tập mã phím tạo thành phím gộp

    // Chiều Nút kích hoạt tay cầm -> Phím gộp (0 giây): lưu danh sách mã output theo đúng thứ tự trái -> phải
    private val activeTriggerOutputChords = HashMap<Int, List<Int>>() // triggerSlot -> ordered output codes

    // Các bước chuỗi phím đang chạy theo từng triggerSlot (để hủy sạch khi nhấn lại hoặc thoát)
    private val activeSequenceTokens = HashMap<Int, SequenceExecution>()
    private val wheelPulseRunnables = HashMap<Int, Runnable>()

    // Bộ đếm tham chiếu slot Xbox ảo (0..24) và mã tổng hợp (keyboard/mouse) để nhả đối xứng tuyệt đối
    private val slotHoldRefCount = HashMap<Int, Int>()
    private val synthCodeHoldRefCount = HashMap<Int, Int>()

    private class SequenceExecution(
        val runnables: MutableList<Runnable> = mutableListOf(),
        val activeCodesInOrder: MutableList<Int> = mutableListOf(),
        @Volatile var cancelled: Boolean = false
    )

    fun attachInGameWebView(webView: WebView?) {
        if (webView == null) {
            releaseAllInGameKeys()
            inGameWebViewRef = null
        } else {
            inGameWebViewRef = WeakReference(webView)
        }
    }

    /**
     * Nhả toàn bộ phím, nút chuột, slot tay cầm đang giữ và hủy toàn bộ các bước chuỗi đang chờ.
     * Gọi khi mở popup/menu app, mất focus, tạm dừng, thoát game hoặc hủy giữa chừng.
     */
    fun releaseAllInGameKeys() {
        val hadAnyState = heldPhysicalInputCodes.isNotEmpty() ||
            activeInputToSlotHolds.isNotEmpty() ||
            activeChordSlotHolds.isNotEmpty() ||
            activeTriggerOutputChords.isNotEmpty() ||
            activeSequenceTokens.isNotEmpty() ||
            wheelPulseRunnables.isNotEmpty() ||
            slotHoldRefCount.isNotEmpty() ||
            synthCodeHoldRefCount.isNotEmpty() ||
            lastMouseButtonsMask != 0

        // Hủy mọi bước chuỗi đang hẹn giờ
        activeSequenceTokens.values.forEach { exec ->
            exec.cancelled = true
            exec.runnables.forEach { mainHandler.removeCallbacks(it) }
            exec.runnables.clear()
            exec.activeCodesInOrder.clear()
        }
        activeSequenceTokens.clear()

        wheelPulseRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        wheelPulseRunnables.clear()

        // Nhả mọi phím gộp đang giữ theo thứ tự ngược lại
        val wv = inGameWebViewRef?.get()
        if (wv != null && activeTriggerOutputChords.isNotEmpty()) {
            activeTriggerOutputChords.values.forEach { ordered ->
                for (i in ordered.lastIndex downTo 0) {
                    releaseOutputCode(wv, ordered[i])
                }
            }
        }

        heldPhysicalInputCodes.clear()
        lastMouseButtonsMask = 0
        activeInputToSlotHolds.clear()
        activeChordSlotHolds.clear()
        activeTriggerOutputChords.clear()
        slotHoldRefCount.clear()
        synthCodeHoldRefCount.clear()

        if (wv != null && hadAnyState) {
            wv.post {
                runCatching {
                    wv.evaluateJavascript(
                        "if (window.__cpCustomKeyReleaseAll) window.__cpCustomKeyReleaseAll();",
                        null
                    )
                }
            }
        }
    }

    // --- Phát / nhả tín hiệu mức thấp với ref-count đối xứng ---

    private fun acquireSlot(wv: WebView, slot: Int) {
        if (slot !in 0..SettingsRepository.PHYS_INPUT_MAX_CODE) return
        val prev = slotHoldRefCount[slot] ?: 0
        slotHoldRefCount[slot] = prev + 1
        if (prev == 0) {
            evalSlotJs(wv, slot, true)
        }
    }

    private fun releaseSlot(wv: WebView, slot: Int) {
        if (slot !in 0..SettingsRepository.PHYS_INPUT_MAX_CODE) return
        val prev = slotHoldRefCount[slot] ?: 0
        if (prev <= 1) {
            slotHoldRefCount.remove(slot)
            if (prev == 1) {
                evalSlotJs(wv, slot, false)
            }
        } else {
            slotHoldRefCount[slot] = prev - 1
        }
    }

    private fun evalSlotJs(wv: WebView, slot: Int, pressed: Boolean) {
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    "if (window.__cpCustomKey) window.__cpCustomKey($slot, $pressed);",
                    null
                )
            }
        }
    }

    private fun acquireOutputCode(wv: WebView, code: Int) {
        val padSlot = KeySequenceCodec.toGamepadSlot(code)
        if (padSlot != null) {
            acquireSlot(wv, padSlot)
            return
        }
        if (code == KeySequenceCodec.MOUSE_WHEEL_UP || code == KeySequenceCodec.MOUSE_WHEEL_DOWN) {
            evalSynthMouseJs(wv, code, true)
            return
        }
        val prev = synthCodeHoldRefCount[code] ?: 0
        synthCodeHoldRefCount[code] = prev + 1
        if (prev == 0) {
            if (KeySequenceCodec.isMouseCode(code)) {
                evalSynthMouseJs(wv, code, true)
            } else if (KeySequenceCodec.isKeyboardCode(code)) {
                evalSynthKeyJs(wv, code, true)
            }
        }
    }

    private fun releaseOutputCode(wv: WebView, code: Int) {
        val padSlot = KeySequenceCodec.toGamepadSlot(code)
        if (padSlot != null) {
            releaseSlot(wv, padSlot)
            return
        }
        if (code == KeySequenceCodec.MOUSE_WHEEL_UP || code == KeySequenceCodec.MOUSE_WHEEL_DOWN) {
            return
        }
        val prev = synthCodeHoldRefCount[code] ?: 0
        if (prev <= 1) {
            synthCodeHoldRefCount.remove(code)
            if (prev == 1) {
                if (KeySequenceCodec.isMouseCode(code)) {
                    evalSynthMouseJs(wv, code, false)
                } else if (KeySequenceCodec.isKeyboardCode(code)) {
                    evalSynthKeyJs(wv, code, false)
                }
            }
        } else {
            synthCodeHoldRefCount[code] = prev - 1
        }
    }

    private fun evalSynthKeyJs(wv: WebView, keyCode: Int, pressed: Boolean) {
        val desc = KeySequenceCodec.getDomKeyDescriptor(keyCode)
        val keyJson = JSONObject.quote(desc.key)
        val codeJson = JSONObject.quote(desc.code)
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    "if (window.__cpSynthKey) window.__cpSynthKey($keyJson, $codeJson, ${desc.legacyKeyCode}, $pressed);",
                    null
                )
            }
        }
    }

    private fun evalSynthMouseJs(wv: WebView, mouseCode: Int, pressed: Boolean) {
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    "if (window.__cpSynthMouse) window.__cpSynthMouse($mouseCode, $pressed);",
                    null
                )
            }
        }
    }

    // --- Thực thi Phím gộp (0 giây) & Chuỗi phím (>0 giây) cho nút kích hoạt ---

    private fun isPureChord(steps: List<KeySequenceStep>): Boolean {
        return steps.size >= 2 && steps.dropLast(1).all { it.delayAfterTenths == 0 }
    }

    private fun hasSequenceDelay(steps: List<KeySequenceStep>): Boolean {
        return steps.size >= 2 && steps.dropLast(1).any { it.delayAfterTenths > 0 }
    }

    private fun cancelSequenceForTrigger(wv: WebView, triggerSlot: Int) {
        val prev = activeSequenceTokens.remove(triggerSlot) ?: return
        prev.cancelled = true
        prev.runnables.forEach { mainHandler.removeCallbacks(it) }
        prev.runnables.clear()
        // Nhả theo thứ tự ngược lại bất kỳ bước nào đang giữ dở dang
        for (i in prev.activeCodesInOrder.lastIndex downTo 0) {
            releaseOutputCode(wv, prev.activeCodesInOrder[i])
        }
        prev.activeCodesInOrder.clear()
    }

    /**
     * Chạy chuỗi phím (>0 giây):
     * - Các bước liên tiếp có delay = 0 giây gom thành 1 cụm nhấn trái -> phải, nhả ngược lại.
     * - Thời gian chờ giữa các cụm tính từ LÚC BẮT ĐẦU bước/cụm trước.
     * - Mỗi cụm là một lần chạm ngắn; chạy 1 lần cho mỗi lần nhấn nút kích hoạt.
     */
    private fun startTimedSequence(wv: WebView, triggerSlot: Int, steps: List<KeySequenceStep>) {
        cancelSequenceForTrigger(wv, triggerSlot)
        if (steps.isEmpty()) return

        // Gom các bước liên tiếp nối bằng 0 giây thành từng cụm đồng thời kèm mốc thời gian bắt đầu
        data class StepGroup(val codes: List<Int>, val startDelayMs: Long)
        val groups = mutableListOf<StepGroup>()
        var currentCodes = mutableListOf<Int>()
        var currentStartMs = 0L
        var nextStartMs = 0L

        steps.forEachIndexed { index, step ->
            if (currentCodes.isEmpty()) {
                currentStartMs = nextStartMs
            }
            currentCodes.add(step.code)
            val isLast = index == steps.lastIndex
            if (isLast || step.delayAfterTenths > 0) {
                groups.add(StepGroup(currentCodes.toList(), currentStartMs))
                nextStartMs = currentStartMs + step.delayAfterTenths * 100L
                currentCodes = mutableListOf()
            }
        }

        val exec = SequenceExecution()
        activeSequenceTokens[triggerSlot] = exec

        groups.forEachIndexed { idx, group ->
            val downRunnable = Runnable {
                if (exec.cancelled) return@Runnable
                // Nhấn xuống theo thứ tự từ trái sang phải
                group.codes.forEach { code ->
                    acquireOutputCode(wv, code)
                    exec.activeCodesInOrder.add(code)
                }
            }
            val nextGroupStart = groups.getOrNull(idx + 1)?.startDelayMs
            val holdMs = if (nextGroupStart != null) {
                val gap = (nextGroupStart - group.startDelayMs).coerceAtLeast(20L)
                minOf(SEQUENCE_TAP_DURATION_MS, (gap - 10L).coerceAtLeast(15L))
            } else {
                SEQUENCE_TAP_DURATION_MS
            }
            val upRunnable = Runnable {
                if (exec.cancelled) return@Runnable
                // Nhả theo thứ tự ngược lại (phải -> trái)
                for (i in group.codes.lastIndex downTo 0) {
                    val code = group.codes[i]
                    releaseOutputCode(wv, code)
                    exec.activeCodesInOrder.remove(code)
                }
                if (idx == groups.lastIndex) {
                    activeSequenceTokens.remove(triggerSlot)
                }
            }
            exec.runnables.add(downRunnable)
            exec.runnables.add(upRunnable)

            if (group.startDelayMs <= 0L) {
                downRunnable.run()
            } else {
                mainHandler.postDelayed(downRunnable, group.startDelayMs)
            }
            mainHandler.postDelayed(upRunnable, group.startDelayMs + holdMs)
        }
    }

    /**
     * Xử lý sự kiện phím cứng khi đang trong game (chỉ được gọi khi `!shouldRouteToAppUi`).
     */
    fun handleInGameKeyEvent(event: KeyEvent): Boolean {
        val rawMap = SettingsRepository.config.value.physicalCustomKeyMap
        if (rawMap.isBlank() &&
            heldPhysicalInputCodes.isEmpty() &&
            activeInputToSlotHolds.isEmpty() &&
            activeChordSlotHolds.isEmpty() &&
            activeTriggerOutputChords.isEmpty()
        ) {
            return false
        }
        val wv = inGameWebViewRef?.get() ?: return false
        val bindings = KeySequenceCodec.parseSlotBindings(rawMap)
        val keyCode = event.keyCode

        // 1. Nếu là nút trên tay cầm vật lý: kiểm tra xem nút đó có gán chuỗi tay cầm hoặc chuỗi macro (>0s) không
        if (isGamepadSource(event.source)) {
            val stdSlot = mapKeyCodeToStandardCode(keyCode)
            if (stdSlot != null) {
                val steps = bindings[stdSlot]
                val isGamepadMacro = steps != null && (
                    steps.any { KeySequenceCodec.isGamepadSlotCode(it.code) } ||
                        hasSequenceDelay(steps) ||
                        (isPureChord(steps) && activeTriggerOutputChords.containsKey(stdSlot))
                )
                if (isGamepadMacro && steps != null) {
                    return handleGamepadTriggerMacro(wv, stdSlot, steps, event)
                }
                // Đảm bảo nhả nếu trước đó nút tay cầm này đang giữ một phím gộp
                if (event.action == KeyEvent.ACTION_UP && activeTriggerOutputChords.containsKey(stdSlot)) {
                    val held = activeTriggerOutputChords.remove(stdSlot)
                    if (held != null) {
                        for (i in held.lastIndex downTo 0) {
                            releaseOutputCode(wv, held[i])
                        }
                    }
                    return true
                }
            }
        }

        // 2. Sự kiện từ bàn phím vật lý (hoặc phím cứng ngoài): ánh xạ sang slot Xbox ảo
        return handlePhysicalInputCodeEvent(
            wv = wv,
            bindings = bindings,
            inputCode = keyCode,
            action = event.action,
            repeatCount = event.repeatCount
        )
    }

    private fun handleGamepadTriggerMacro(
        wv: WebView,
        triggerSlot: Int,
        steps: List<KeySequenceStep>,
        event: KeyEvent
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                if (hasSequenceDelay(steps)) {
                    // Chuỗi phím (>0 giây): chạm ngắn từng bước, chạy 1 lần mỗi lần nhấn nút kích hoạt
                    startTimedSequence(wv, triggerSlot, steps)
                } else {
                    // Phím gộp (0 giây) hoặc 1 nút tay cầm: nhấn trái -> phải, giữ suốt lúc nút kích hoạt còn giữ
                    if (!activeTriggerOutputChords.containsKey(triggerSlot)) {
                        val ordered = steps.map { it.code }
                        activeTriggerOutputChords[triggerSlot] = ordered
                        ordered.forEach { code -> acquireOutputCode(wv, code) }
                    }
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                val held = activeTriggerOutputChords.remove(triggerSlot)
                if (held != null) {
                    // Nhả theo thứ tự ngược lại
                    for (i in held.lastIndex downTo 0) {
                        releaseOutputCode(wv, held[i])
                    }
                }
                true
            }
            else -> false
        }
    }

    /**
     * Xử lý sự kiện chuột vật lý (nút trái/phải/giữa/bên 1/bên 2 và cuộn lên/xuống) khi đang trong game.
     */
    fun handleInGameMouseEvent(event: MotionEvent): Boolean {
        if (!isMouseSource(event.source)) return false
        val rawMap = SettingsRepository.config.value.physicalCustomKeyMap
        if (rawMap.isBlank() &&
            lastMouseButtonsMask == 0 &&
            activeInputToSlotHolds.isEmpty() &&
            activeChordSlotHolds.isEmpty()
        ) {
            return false
        }
        val wv = inGameWebViewRef?.get() ?: return false
        val bindings = KeySequenceCodec.parseSlotBindings(rawMap)
        val hasMouseBinding = bindings.values.any { steps ->
            steps.any { KeySequenceCodec.isMouseCode(it.code) }
        }
        if (!hasMouseBinding && lastMouseButtonsMask == 0) return false

        // 1. Cuộn chuột (Wheel Up / Down) -> chạm ngắn tự nhả
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val wheelCode = when {
                vScroll > 0.01f -> KeySequenceCodec.MOUSE_WHEEL_UP
                vScroll < -0.01f -> KeySequenceCodec.MOUSE_WHEEL_DOWN
                else -> null
            } ?: return false
            return triggerMouseWheelPulse(wv, bindings, wheelCode)
        }

        // 2. Các nút chuột nhấn/nhả đối xứng theo buttonState
        val newMask = event.buttonState
        val changed = newMask xor lastMouseButtonsMask
        if (changed == 0) {
            // Nếu nút chuột đang được tiêu thụ thì chặn ACTION_BUTTON_PRESS/RELEASE mặc định của hệ thống
            val anyHeldMouseConsumed = heldPhysicalInputCodes.any { KeySequenceCodec.isMouseCode(it) }
            return anyHeldMouseConsumed && (
                event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                    event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
                )
        }
        val oldMask = lastMouseButtonsMask
        lastMouseButtonsMask = newMask

        var consumed = false
        for ((androidBtn, mouseCode) in MOUSE_BUTTON_MASK_PAIRS) {
            val wasDown = (oldMask and androidBtn) != 0
            val isDown = (newMask and androidBtn) != 0
            if (!wasDown && isDown) {
                if (handlePhysicalInputCodeEvent(wv, bindings, mouseCode, KeyEvent.ACTION_DOWN, 0)) {
                    consumed = true
                }
            } else if (wasDown && !isDown) {
                if (handlePhysicalInputCodeEvent(wv, bindings, mouseCode, KeyEvent.ACTION_UP, 0)) {
                    consumed = true
                }
            }
        }
        return consumed
    }

    private fun triggerMouseWheelPulse(
        wv: WebView,
        bindings: Map<Int, List<KeySequenceStep>>,
        wheelCode: Int
    ): Boolean {
        val targetSlot = bindings.entries.firstOrNull { (_, steps) ->
            steps.size == 1 && steps[0].code == wheelCode
        }?.key ?: return false

        wheelPulseRunnables.remove(targetSlot)?.let {
            mainHandler.removeCallbacks(it)
            releaseSlot(wv, targetSlot)
        }
        acquireSlot(wv, targetSlot)
        val releaseTask = Runnable {
            wheelPulseRunnables.remove(targetSlot)
            releaseSlot(wv, targetSlot)
        }
        wheelPulseRunnables[targetSlot] = releaseTask
        mainHandler.postDelayed(releaseTask, WHEEL_PULSE_DURATION_MS)
        return true
    }

    /**
     * Xử lý nhấn/nhả 1 mã đầu vào vật lý (phím bàn phím hoặc nút chuột) đối chiếu với `bindings`:
     * - Ưu tiên khớp Phím gộp (0 giây, gồm >= 2 phím bàn phím/chuột) khi đủ tất cả phím trong tổ hợp.
     * - Khớp 1 phím / nút chuột đơn lẻ -> giữ slot Xbox tương ứng qua `window.__cpCustomKey`.
     */
    private fun handlePhysicalInputCodeEvent(
        wv: WebView,
        bindings: Map<Int, List<KeySequenceStep>>,
        inputCode: Int,
        action: Int,
        repeatCount: Int
    ): Boolean {
        // Tìm tất cả slot dùng inputCode (1 phím hoặc phím gộp 0s)
        val singleSlot = bindings.entries.firstOrNull { (_, steps) ->
            steps.size == 1 && steps[0].code == inputCode
        }?.key

        val chordEntries = bindings.entries
            .filter { (_, steps) ->
                isPureChord(steps) && steps.all { KeySequenceCodec.isKeyboardCode(it.code) || KeySequenceCodec.isMouseCode(it.code) }
            }
            .sortedByDescending { it.value.size }

        val participatesInAnyChord = chordEntries.any { (_, steps) -> steps.any { it.code == inputCode } }

        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (singleSlot == null && !participatesInAnyChord && !activeInputToSlotHolds.containsKey(inputCode)) {
                    return false
                }
                if (repeatCount > 0) return true
                heldPhysicalInputCodes.add(inputCode)

                // Kiểm tra khớp phím gộp (0 giây) dài nhất chứa inputCode trước
                val matchedChord = chordEntries.firstOrNull { (_, steps) ->
                    val requiredCodes = steps.map { it.code }.toSet()
                    requiredCodes.contains(inputCode) && heldPhysicalInputCodes.containsAll(requiredCodes)
                }
                if (matchedChord != null) {
                    val chordSlot = matchedChord.key
                    val chordCodes = matchedChord.value.map { it.code }.toSet()
                    // Nếu phím đơn nào trong tổ hợp đang giữ slot đơn trước đó, nhả slot đơn đó để không bắn nhầm
                    chordCodes.forEach { memberCode ->
                        val prevSingle = activeInputToSlotHolds.remove(memberCode)
                        if (prevSingle != null) {
                            releaseSlot(wv, prevSingle)
                        }
                    }
                    if (!activeChordSlotHolds.containsKey(chordSlot)) {
                        activeChordSlotHolds[chordSlot] = chordCodes
                        acquireSlot(wv, chordSlot)
                    }
                    return true
                }

                // Nếu chưa đủ phím của tổ hợp mà có gán phím đơn -> kích hoạt phím đơn
                if (singleSlot != null) {
                    if (activeInputToSlotHolds[inputCode] != singleSlot) {
                        activeInputToSlotHolds[inputCode] = singleSlot
                        acquireSlot(wv, singleSlot)
                    }
                    return true
                }
                // Phím bổ trợ (vd Ctrl) chỉ tham gia phím gộp -> tiêu thụ sạch khi đang giữ
                participatesInAnyChord
            }
            KeyEvent.ACTION_UP -> {
                val wasHeld = heldPhysicalInputCodes.remove(inputCode)
                var consumed = false

                // Nhả mọi phím gộp đang giữ mà có chứa inputCode
                val chordsToRelease = activeChordSlotHolds.entries
                    .filter { it.value.contains(inputCode) }
                    .map { it.key }
                chordsToRelease.forEach { chordSlot ->
                    activeChordSlotHolds.remove(chordSlot)
                    releaseSlot(wv, chordSlot)
                    consumed = true
                }

                // Nhả phím đơn nếu đang giữ
                val heldSingleSlot = activeInputToSlotHolds.remove(inputCode)
                if (heldSingleSlot != null) {
                    releaseSlot(wv, heldSingleSlot)
                    consumed = true
                }

                consumed || wasHeld
            }
            else -> false
        }
    }

    // --- Phần giám sát & chờ bắt phím trong màn hình Cài đặt / Gán phím ---

    fun setMonitorActive(active: Boolean) {
        monitorActive = active
        if (!active) {
            disarmCapture()
            heldKeys.clear()
            heldCustomKeys.clear()
            heldMotionCodes.clear()
            ignoredStdUntilRelease.clear()
            ignoredCustomUntilRelease.clear()
            _livePressedCodes.value = emptySet()
            _liveCustomKeyCodes.value = emptySet()
        }
    }

    fun armCapture(
        onCaptured: (CapturedPhysicalInput) -> Unit,
        onCancel: () -> Unit
    ) {
        ignoredStdUntilRelease.clear()
        ignoredStdUntilRelease.addAll(heldKeys)
        ignoredStdUntilRelease.addAll(heldMotionCodes)
        ignoredCustomUntilRelease.clear()
        ignoredCustomUntilRelease.addAll(heldCustomKeys)
        captureArmedAtUptimeMs = SystemClock.uptimeMillis()
        captureCallback = onCaptured
        cancelCallback = onCancel
        _isCapturing.value = true
    }

    fun disarmCapture() {
        captureCallback = null
        cancelCallback = null
        ignoredStdUntilRelease.clear()
        ignoredCustomUntilRelease.clear()
        _isCapturing.value = false
    }

    private fun publishLiveCodes() {
        val combined = HashSet<Int>(heldKeys.size + heldMotionCodes.size)
        combined.addAll(heldKeys)
        combined.addAll(heldMotionCodes)
        _livePressedCodes.value = combined
        _liveCustomKeyCodes.value = HashSet(heldCustomKeys)
    }

    private fun isGamepadSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }

    private fun isMouseSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
    }

    private fun isReservedSystemKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_UNKNOWN,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_SOFT_SLEEP,
        KeyEvent.KEYCODE_SYSRQ,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST -> true
        else -> false
    }

    private fun mapKeyCodeToStandardCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 6
        KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_START -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12
        KeyEvent.KEYCODE_DPAD_DOWN -> 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 14
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_MODE -> 16
        else -> null
    }

    /**
     * Bắt nút chuột vật lý khi đang chờ bắt phím trong màn hình Gán phím.
     */
    fun handleCaptureMouseEvent(event: MotionEvent): Boolean {
        val capturing = captureCallback != null
        if (!capturing || !isMouseSource(event.source)) return false
        val now = SystemClock.uptimeMillis()
        if (now - captureArmedAtUptimeMs < 200L) return true

        val capturedMouseCode: Int? = when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                when {
                    v > 0.01f -> KeySequenceCodec.MOUSE_WHEEL_UP
                    v < -0.01f -> KeySequenceCodec.MOUSE_WHEEL_DOWN
                    else -> null
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                MOUSE_BUTTON_MASK_PAIRS.firstOrNull { (mask, _) ->
                    (event.buttonState and mask) != 0
                }?.second
            }
            else -> null
        }

        if (capturedMouseCode != null) {
            val cb = captureCallback
            disarmCapture()
            cb?.invoke(CapturedPhysicalInput.CustomHardwareKey(capturedMouseCode))
            return true
        }
        return false
    }

    /**
     * Trả về `true` nếu sự kiện đã bị tiêu thụ (chỉ khi đang ở chế độ chờ bắt phím).
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val capturing = captureCallback != null
        if (!monitorActive && !capturing) return false

        val keyCode = event.keyCode
        if (isReservedSystemKey(keyCode)) return false

        if (capturing && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val cb = cancelCallback
                disarmCapture()
                cb?.invoke()
            }
            return true
        }

        val isGamepad = isGamepadSource(event.source)
        val stdCode = if (isGamepad) mapKeyCodeToStandardCode(keyCode) else null

        if (stdCode != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    heldKeys.add(stdCode)
                    if (monitorActive) publishLiveCodes()
                    if (capturing && event.repeatCount == 0) {
                        val now = SystemClock.uptimeMillis()
                        if (now - captureArmedAtUptimeMs >= 200L && !ignoredStdUntilRelease.contains(stdCode)) {
                            val cb = captureCallback
                            disarmCapture()
                            cb?.invoke(CapturedPhysicalInput.StandardGamepad(stdCode))
                        }
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    heldKeys.remove(stdCode)
                    ignoredStdUntilRelease.remove(stdCode)
                    if (monitorActive) publishLiveCodes()
                    if (capturing) return true
                }
            }
            return capturing
        }

        // Bất kỳ phím cứng nào khác (bàn phím Bluetooth/USB, điều khiển TV, phím âm lượng, nút tay cầm phụ...)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                heldCustomKeys.add(keyCode)
                if (monitorActive) publishLiveCodes()
                if (capturing && event.repeatCount == 0) {
                    val now = SystemClock.uptimeMillis()
                    if (now - captureArmedAtUptimeMs >= 200L && !ignoredCustomUntilRelease.contains(keyCode)) {
                        val cb = captureCallback
                        disarmCapture()
                        cb?.invoke(CapturedPhysicalInput.CustomHardwareKey(keyCode))
                    }
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                heldCustomKeys.remove(keyCode)
                ignoredCustomUntilRelease.remove(keyCode)
                if (monitorActive) publishLiveCodes()
                if (capturing) return true
            }
        }
        return capturing
    }

    /**
     * Trả về `true` nếu sự kiện trục analog / D-Pad hat đã bị tiêu thụ (chỉ khi đang chờ bắt phím).
     */
    fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        val capturing = captureCallback != null
        if (!monitorActive && !capturing) return false
        if (capturing && isMouseSource(event.source)) {
            return handleCaptureMouseEvent(event)
        }
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z).let { z ->
            if (abs(z) > 0.05f) z else event.getAxisValue(MotionEvent.AXIS_RX)
        }
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ).let { rz ->
            if (abs(rz) > 0.05f) rz else event.getAxisValue(MotionEvent.AXIS_RY)
        }
        val lt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        val rt = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        val liveThreshold = 0.40f
        val nextMotion = HashSet<Int>()
        if (lt >= 0.35f) nextMotion.add(6)
        if (rt >= 0.35f) nextMotion.add(7)
        if (hatY <= -0.5f) nextMotion.add(12)
        if (hatY >= 0.5f) nextMotion.add(13)
        if (hatX <= -0.5f) nextMotion.add(14)
        if (hatX >= 0.5f) nextMotion.add(15)
        if (ly <= -liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_L_UP)
        if (ly >= liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_L_DOWN)
        if (lx <= -liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_L_LEFT)
        if (lx >= liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_L_RIGHT)
        if (ry <= -liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_R_UP)
        if (ry >= liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_R_DOWN)
        if (rx <= -liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_R_LEFT)
        if (rx >= liveThreshold) nextMotion.add(SettingsRepository.PHYS_STICK_R_RIGHT)

        val released = heldMotionCodes - nextMotion
        released.forEach { ignoredStdUntilRelease.remove(it) }
        heldMotionCodes.clear()
        heldMotionCodes.addAll(nextMotion)
        if (monitorActive) publishLiveCodes()

        if (!capturing) return false

        val now = SystemClock.uptimeMillis()
        if (now - captureArmedAtUptimeMs < 200L) return true

        val candidates = mutableListOf<Pair<Int, Float>>()
        if (lt >= 0.55f) candidates.add(6 to lt)
        if (rt >= 0.55f) candidates.add(7 to rt)
        if (hatY <= -0.6f) candidates.add(12 to abs(hatY))
        if (hatY >= 0.6f) candidates.add(13 to abs(hatY))
        if (hatX <= -0.6f) candidates.add(14 to abs(hatX))
        if (hatX >= 0.6f) candidates.add(15 to abs(hatX))

        val stickThreshold = 0.65f
        if (abs(ly) >= stickThreshold && abs(ly) >= abs(lx) * 1.15f) {
            candidates.add((if (ly < 0f) SettingsRepository.PHYS_STICK_L_UP else SettingsRepository.PHYS_STICK_L_DOWN) to abs(ly))
        } else if (abs(lx) >= stickThreshold && abs(lx) >= abs(ly) * 1.15f) {
            candidates.add((if (lx < 0f) SettingsRepository.PHYS_STICK_L_LEFT else SettingsRepository.PHYS_STICK_L_RIGHT) to abs(lx))
        }
        if (abs(ry) >= stickThreshold && abs(ry) >= abs(rx) * 1.15f) {
            candidates.add((if (ry < 0f) SettingsRepository.PHYS_STICK_R_UP else SettingsRepository.PHYS_STICK_R_DOWN) to abs(ry))
        } else if (abs(rx) >= stickThreshold && abs(rx) >= abs(ry) * 1.15f) {
            candidates.add((if (rx < 0f) SettingsRepository.PHYS_STICK_R_LEFT else SettingsRepository.PHYS_STICK_R_RIGHT) to abs(rx))
        }

        val winner = candidates
            .filterNot { ignoredStdUntilRelease.contains(it.first) }
            .maxByOrNull { it.second }

        if (winner != null) {
            val cb = captureCallback
            disarmCapture()
            cb?.invoke(CapturedPhysicalInput.StandardGamepad(winner.first))
        }
        return true
    }

    private val MOUSE_BUTTON_MASK_PAIRS = listOf(
        MotionEvent.BUTTON_PRIMARY to KeySequenceCodec.MOUSE_LEFT,
        MotionEvent.BUTTON_SECONDARY to KeySequenceCodec.MOUSE_RIGHT,
        MotionEvent.BUTTON_TERTIARY to KeySequenceCodec.MOUSE_MIDDLE,
        MotionEvent.BUTTON_BACK to KeySequenceCodec.MOUSE_SIDE_1,
        MotionEvent.BUTTON_FORWARD to KeySequenceCodec.MOUSE_SIDE_2
    )
}
