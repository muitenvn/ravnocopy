package com.example.cloudplay

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Kết quả bắt phím vật lý bất kỳ từ người dùng:
 * - [StandardGamepad]: nút tay cầm chuẩn (`0..16`) hoặc 8 hướng cần analog (`17..24`).
 * - [CustomHardwareKey]: BẤT KỲ phím cứng nào khác (bàn phím Bluetooth/USB, điều khiển TV,
 *   phím âm lượng, nút tay cầm không chuẩn `BUTTON_1..16`, `BUTTON_C/Z`, v.v.).
 */
sealed class CapturedPhysicalInput {
    data class StandardGamepad(val code: Int) : CapturedPhysicalInput()
    data class CustomHardwareKey(val androidKeyCode: Int) : CapturedPhysicalInput()
}

/**
 * Cầu nối bắt phím & hướng gạt cần trực tiếp từ MỌI thiết bị nhập vật lý
 * (tay cầm Bluetooth/USB, bàn phím, điều khiển TV, phím âm lượng, tay cầm bên thứ 3):
 *
 * - Trong Cài đặt (`monitorActive = true`): làm sáng trực tiếp nút/hướng cần đang bấm trên sơ đồ.
 * - Khi chờ gán (`captureCallback != null`): bắt độc quyền nút/cần/phím cứng bất kỳ tiếp theo
 *   (trừ phím Back/Escape để hủy an toàn và phím hệ thống Home/Power).
 * - Khi đang chơi game (`handleInGameKeyEvent`): chuyển các phím cứng ngoài đã gán trong
 *   `physicalCustomKeyMap` thành tín hiệu tay cầm Xbox (`window.__cpCustomKey`), nhả đối xứng
 *   đầy đủ khi mất tiêu điểm / mở popup / thoát màn hình.
 */
object PhysicalGamepadCaptureBus {
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

    // Runtime in-game cho phím cứng ngoài (physicalCustomKeyMap)
    @Volatile
    private var inGameWebViewRef: WeakReference<WebView>? = null
    private val heldInGameCustomKeys = HashMap<Int, Int>() // androidKeyCode -> targetXboxSlot

    fun attachInGameWebView(webView: WebView?) {
        if (webView == null) {
            releaseAllInGameKeys()
            inGameWebViewRef = null
        } else {
            inGameWebViewRef = WeakReference(webView)
        }
    }

    fun releaseAllInGameKeys() {
        val hadHeld = heldInGameCustomKeys.isNotEmpty()
        heldInGameCustomKeys.clear()
        val wv = inGameWebViewRef?.get() ?: return
        if (hadHeld) {
            wv.post {
                runCatching {
                    wv.evaluateJavascript("if (window.__cpCustomKeyReleaseAll) window.__cpCustomKeyReleaseAll();", null)
                }
            }
        }
    }

    fun handleInGameKeyEvent(event: KeyEvent): Boolean {
        val rawMap = SettingsRepository.config.value.physicalCustomKeyMap
        if (rawMap.isBlank() && heldInGameCustomKeys.isEmpty()) return false
        val keyMap = SettingsRepository.parsePhysicalCustomKeyMap(rawMap)
        val keyCode = event.keyCode
        val mappedSlot = keyMap[keyCode] ?: heldInGameCustomKeys[keyCode] ?: return false
        val wv = inGameWebViewRef?.get() ?: return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                if (heldInGameCustomKeys[keyCode] == mappedSlot) return true
                heldInGameCustomKeys[keyCode] = mappedSlot
                wv.post {
                    runCatching {
                        wv.evaluateJavascript(
                            "if (window.__cpCustomKey) window.__cpCustomKey($mappedSlot, true);",
                            null
                        )
                    }
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                val releasedSlot = heldInGameCustomKeys.remove(keyCode) ?: mappedSlot
                // Chỉ nhả slot nếu không còn phím cứng nào khác đang giữ cùng slot này
                val stillHeldByAnother = heldInGameCustomKeys.values.any { it == releasedSlot }
                if (!stillHeldByAnother) {
                    wv.post {
                        runCatching {
                            wv.evaluateJavascript(
                                "if (window.__cpCustomKey) window.__cpCustomKey($releasedSlot, false);",
                                null
                            )
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

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
}
