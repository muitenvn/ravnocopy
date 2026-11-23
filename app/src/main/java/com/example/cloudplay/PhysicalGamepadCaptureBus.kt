package com.example.cloudplay

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Cầu nối bắt phím & hướng gạt cần trực tiếp từ tay cầm vật lý (Bluetooth/USB)
 * phục vụ Sơ đồ chỉnh nút tay cầm trong màn hình Cài đặt.
 *
 * - Khi `monitorActive = true`: theo dõi thụ động các nút/hướng đang bấm (`livePressedCodes`)
 *   để làm sáng trực tiếp trên sơ đồ tay cầm, không cản trở điều hướng D-Pad.
 * - Khi `captureCallback != null`: tạm thời nhận độc quyền phím/cần gạt tiếp theo từ tay cầm thật
 *   (có lớp chặn nhả nút cũ / thời gian đệm để không tự bắt lại nút A vừa dùng để mở bảng),
 *   trừ phím Back/Escape luôn hủy chế độ chờ bắt phím để tránh kẹt người dùng.
 */
object PhysicalGamepadCaptureBus {
    private val _livePressedCodes = MutableStateFlow<Set<Int>>(emptySet())
    val livePressedCodes: StateFlow<Set<Int>> = _livePressedCodes.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile
    private var monitorActive: Boolean = false

    @Volatile
    private var captureCallback: ((Int) -> Unit)? = null

    @Volatile
    private var cancelCallback: (() -> Unit)? = null

    @Volatile
    private var captureArmedAtUptimeMs: Long = 0L

    private val heldKeys = HashSet<Int>()
    private val heldMotionCodes = HashSet<Int>()
    private val ignoredUntilRelease = HashSet<Int>()

    fun setMonitorActive(active: Boolean) {
        monitorActive = active
        if (!active) {
            disarmCapture()
            heldKeys.clear()
            heldMotionCodes.clear()
            ignoredUntilRelease.clear()
            _livePressedCodes.value = emptySet()
        }
    }

    fun armCapture(
        onCaptured: (Int) -> Unit,
        onCancel: () -> Unit
    ) {
        ignoredUntilRelease.clear()
        ignoredUntilRelease.addAll(heldKeys)
        ignoredUntilRelease.addAll(heldMotionCodes)
        captureArmedAtUptimeMs = SystemClock.uptimeMillis()
        captureCallback = onCaptured
        cancelCallback = onCancel
        _isCapturing.value = true
    }

    fun disarmCapture() {
        captureCallback = null
        cancelCallback = null
        ignoredUntilRelease.clear()
        _isCapturing.value = false
    }

    private fun publishLiveCodes() {
        val combined = HashSet<Int>(heldKeys.size + heldMotionCodes.size)
        combined.addAll(heldKeys)
        combined.addAll(heldMotionCodes)
        _livePressedCodes.value = combined
    }

    private fun isGamepadSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
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
        if (capturing && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val cb = cancelCallback
                disarmCapture()
                cb?.invoke()
            }
            return true
        }

        if (!isGamepadSource(event.source)) return false
        val stdCode = mapKeyCodeToStandardCode(keyCode) ?: return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                heldKeys.add(stdCode)
                if (monitorActive) publishLiveCodes()
                if (capturing && event.repeatCount == 0) {
                    val now = SystemClock.uptimeMillis()
                    if (now - captureArmedAtUptimeMs >= 200L && !ignoredUntilRelease.contains(stdCode)) {
                        val cb = captureCallback
                        disarmCapture()
                        cb?.invoke(stdCode)
                    }
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(stdCode)
                ignoredUntilRelease.remove(stdCode)
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

        // Gỡ cờ chặn các hướng đã nhả về tâm
        val released = heldMotionCodes - nextMotion
        released.forEach { ignoredUntilRelease.remove(it) }
        heldMotionCodes.clear()
        heldMotionCodes.addAll(nextMotion)
        if (monitorActive) publishLiveCodes()

        if (!capturing) return false

        val now = SystemClock.uptimeMillis()
        if (now - captureArmedAtUptimeMs < 200L) return true

        // Tìm tín hiệu mạnh nhất vượt ngưỡng bắt phím rõ ràng (tránh trôi cần nhẹ)
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
            .filterNot { ignoredUntilRelease.contains(it.first) }
            .maxByOrNull { it.second }

        if (winner != null) {
            val cb = captureCallback
            disarmCapture()
            cb?.invoke(winner.first)
        }
        return true
    }
}
