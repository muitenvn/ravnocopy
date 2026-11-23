package com.example.cloudplay

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import com.example.data.AppConfig
import com.example.data.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class KbmTestMode {
    OFF,
    VIRTUAL_GAMEPAD,
    NATIVE_PASSTHROUGH
}

data class KbmRuntimeState(
    val mode: KbmTestMode = KbmTestMode.OFF,
    val isPointerCaptured: Boolean = false,
    val isPausedByEsc: Boolean = false,
    val mouseSensitivity: Float = 1.0f,
    val nativePointerLockStatus: String = "",
    val lastKeyHint: String = ""
)

/**
 * Thử nghiệm nhanh hai phiên bản Bàn phím + Chuột cho Ravno:
 * - Bản A (VIRTUAL_GAMEPAD): Bàn phím OTG/BT + Chuột thật (View.requestPointerCapture API 26+) -> tay cầm ảo (__cpBtn/__cpStick).
 * - Bản B (NATIVE_PASSTHROUGH): Chuyển thẳng phím/chuột vào trang Xbox + kiểm tra Web Pointer Lock thực tế.
 */
object KeyboardMousePrototype {

    private const val MOUSE_FLUSH_INTERVAL_MS = 8L
    private const val MOUSE_STOP_WATCHDOG_MS = 45L
    private const val MOUSE_BASE_PIXELS_FOR_FULL_STICK = 26f
    private const val MOUSE_ANTI_DEADZONE = 0.14f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webViewRef: WeakReference<WebView>? = null

    @Volatile
    private var isInGameStream: Boolean = false

    @Volatile
    private var isAppUiRouting: Boolean = true

    private val _state = MutableStateFlow(KbmRuntimeState())
    val state: StateFlow<KbmRuntimeState> = _state.asStateFlow()

    // Theo dõi trạng thái nhấn đối xứng để không bao giờ kẹt nút
    private val heldKeyCodes = HashSet<Int>()
    private val buttonRefCounts = HashMap<Int, Int>()
    private var lastMouseButtonMask: Int = 0
    private var lastLeftStickX: Float = 0f
    private var lastLeftStickY: Float = 0f
    private var lastRightStickX: Float = 0f
    private var lastRightStickY: Float = 0f

    // Gom delta chuột mỗi ~8ms để chuột gaming 1000Hz không làm nghẽn luồng JS
    private var pendingMouseDx: Float = 0f
    private var pendingMouseDy: Float = 0f
    private var mouseFlushScheduled: Boolean = false
    private var lastMouseFlushMs: Long = 0L

    private val mouseFlushRunnable = Runnable {
        mouseFlushScheduled = false
        flushPendingMouseDelta()
    }

    private val mouseStopWatchdogRunnable = Runnable {
        if (lastRightStickX != 0f || lastRightStickY != 0f) {
            sendStick(2, 0f, 3, 0f)
        }
    }

    fun attachWebView(webView: WebView?) {
        val prev = webViewRef?.get()
        if (prev != null && prev !== webView) {
            releaseAllAndUncapture("webview_changed")
        }
        webViewRef = if (webView != null) WeakReference(webView) else null
    }

    fun updateRoutingContext(inGameStream: Boolean, routeToAppUi: Boolean) {
        isInGameStream = inGameStream
        isAppUiRouting = routeToAppUi
        if (!inGameStream || routeToAppUi) {
            releaseAllAndUncapture(if (routeToAppUi) "app_ui_open" else "left_stream")
        }
    }

    fun setMode(newMode: KbmTestMode) {
        val prev = _state.value.mode
        if (prev == newMode) return
        releaseAllAndUncapture("mode_switch")
        _state.update {
            it.copy(
                mode = newMode,
                isPausedByEsc = false,
                nativePointerLockStatus = "",
                lastKeyHint = ""
            )
        }
        AppLogger.info("KBM", "Đổi chế độ thử nghiệm KBM: $prev -> $newMode")
        if (newMode == KbmTestMode.VIRTUAL_GAMEPAD) {
            requestPointerCaptureNow()
        } else if (newMode == KbmTestMode.NATIVE_PASSTHROUGH) {
            probeAndRequestNativePointerLock()
        }
    }

    fun cycleMode() {
        val next = when (_state.value.mode) {
            KbmTestMode.OFF -> KbmTestMode.VIRTUAL_GAMEPAD
            KbmTestMode.VIRTUAL_GAMEPAD -> KbmTestMode.NATIVE_PASSTHROUGH
            KbmTestMode.NATIVE_PASSTHROUGH -> KbmTestMode.OFF
        }
        setMode(next)
    }

    fun adjustMouseSensitivity(delta: Float) {
        _state.update { cur ->
            val next = ((cur.mouseSensitivity + delta) * 10f).roundToInt() / 10f
            cur.copy(mouseSensitivity = next.coerceIn(0.4f, 2.5f))
        }
    }

    fun requestPointerCaptureNow(): Boolean {
        val wv = webViewRef?.get() ?: return false
        if (_state.value.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting) return false
        _state.update { it.copy(isPausedByEsc = false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.post {
                try {
                    wv.isFocusable = true
                    wv.isFocusableInTouchMode = true
                    wv.requestFocus()
                    wv.requestPointerCapture()
                    val captured = wv.hasPointerCapture()
                    _state.update { it.copy(isPointerCaptured = captured) }
                    AppLogger.info("KBM", "Yêu cầu PointerCapture API 26+: captured=$captured")
                } catch (e: Exception) {
                    AppLogger.warn("KBM", "Không thể bật PointerCapture: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    fun onPointerCaptureChanged(hasCapture: Boolean) {
        _state.update { it.copy(isPointerCaptured = hasCapture) }
        if (!hasCapture) {
            releaseAllMouseAndRightStick()
        }
    }

    /**
     * Bản B: kiểm tra xem WebView hiện tại có hỗ trợ & cho phép Web Pointer Lock (requestPointerLock) không.
     */
    fun probeAndRequestNativePointerLock() {
        val wv = webViewRef?.get() ?: return
        val js = """
            (function(){
                try {
                    var el = document.querySelector('video#stream-video, video[data-testid="stream-video"], #segmented-video video, video') || document.documentElement;
                    if (!el || typeof el.requestPointerLock !== 'function') return 'UNSUPPORTED';
                    var already = (document.pointerLockElement === el);
                    if (already) return 'LOCKED';
                    var res = el.requestPointerLock();
                    if (res && typeof res.then === 'function') {
                        res.catch(function(){});
                    }
                    return (document.pointerLockElement ? 'LOCKED' : 'REQUESTED');
                } catch (e) {
                    return 'ERR:' + (e && e.name ? e.name : 'blocked');
                }
            })()
        """.trimIndent()
        wv.post {
            try {
                wv.requestFocus()
                wv.evaluateJavascript(js) { raw ->
                    val status = raw?.trim()?.removeSurrounding("\"") ?: "UNKNOWN"
                    _state.update { it.copy(nativePointerLockStatus = status) }
                    AppLogger.info("KBM", "Bản B (Native) kiểm tra PointerLock: $status")
                }
            } catch (_: Exception) {}
        }
    }

    fun releaseAllAndUncapture(reason: String) {
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mainHandler.removeCallbacks(mouseStopWatchdogRunnable)
        mouseFlushScheduled = false
        pendingMouseDx = 0f
        pendingMouseDy = 0f

        val wv = webViewRef?.get()
        if (wv != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.post {
                try {
                    if (wv.hasPointerCapture()) {
                        wv.releasePointerCapture()
                    }
                } catch (_: Exception) {}
            }
        }

        val hadAnyState = heldKeyCodes.isNotEmpty() ||
            buttonRefCounts.isNotEmpty() ||
            lastMouseButtonMask != 0 ||
            lastLeftStickX != 0f || lastLeftStickY != 0f ||
            lastRightStickX != 0f || lastRightStickY != 0f

        // Nhả đối xứng từng nút đang giữ thay vì chỉ xóa ngầm
        val activeButtons = buttonRefCounts.keys.toList()
        heldKeyCodes.clear()
        buttonRefCounts.clear()
        lastMouseButtonMask = 0

        if (hadAnyState && wv != null) {
            val sb = StringBuilder()
            for (btn in activeButtons) {
                sb.append("if(window.__cpBtn)window.__cpBtn(").append(btn).append(",false,0);")
            }
            if (lastLeftStickX != 0f || lastLeftStickY != 0f) {
                sb.append("if(window.__cpStick)window.__cpStick(0,0,1,0);")
            }
            if (lastRightStickX != 0f || lastRightStickY != 0f) {
                sb.append("if(window.__cpStick)window.__cpStick(2,0,3,0);")
            }
            if (sb.isNotEmpty()) {
                val js = sb.toString()
                wv.post {
                    try {
                        wv.evaluateJavascript(js, null)
                    } catch (_: Exception) {}
                }
            }
        }
        lastLeftStickX = 0f
        lastLeftStickY = 0f
        lastRightStickX = 0f
        lastRightStickY = 0f

        if (_state.value.isPointerCaptured) {
            _state.update { it.copy(isPointerCaptured = false) }
        }
        if (hadAnyState) {
            AppLogger.info("KBM", "Đã nhả toàn bộ phím/chuột KBM ($reason)")
        }
    }

    private fun releaseAllMouseAndRightStick() {
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mainHandler.removeCallbacks(mouseStopWatchdogRunnable)
        mouseFlushScheduled = false
        pendingMouseDx = 0f
        pendingMouseDy = 0f
        if (lastRightStickX != 0f || lastRightStickY != 0f) {
            sendStick(2, 0f, 3, 0f)
        }
        if (lastMouseButtonMask != 0) {
            applyMouseButtonMask(0)
        }
    }

    /**
     * Xử lý KeyEvent cho Bản A (VIRTUAL_GAMEPAD).
     * Trả về false ngay lập tức nếu đang Tắt, đang ở Bản B (Gốc), đang mở popup, ngoài luồng game, hoặc phím từ tay cầm.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val cur = _state.value
        if (cur.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting) return false
        if (isGamepadOrSystemKey(event)) return false

        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        val pressed = action == KeyEvent.ACTION_DOWN
        val keyCode = event.keyCode

        // Phím Esc: luôn là lối thoát an toàn, nhả chuột & tạm dừng bắt phím
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (pressed && event.repeatCount == 0) {
                releaseAllAndUncapture("escape_key")
                _state.update { it.copy(isPausedByEsc = true, lastKeyHint = "ESC") }
            }
            return true
        }

        if (cur.isPausedByEsc) return false

        // Chặn key-repeat của hệ điều hành để không gửi trùng JS
        if (pressed && event.repeatCount > 0) {
            return isMappedKeyCode(keyCode)
        }

        // WASD -> Cần trái (Left Stick)
        if (keyCode == KeyEvent.KEYCODE_W ||
            keyCode == KeyEvent.KEYCODE_A ||
            keyCode == KeyEvent.KEYCODE_S ||
            keyCode == KeyEvent.KEYCODE_D
        ) {
            val changed = if (pressed) heldKeyCodes.add(keyCode) else heldKeyCodes.remove(keyCode)
            if (changed) {
                recomputeAndSendLeftStick()
                if (pressed) updateLastHint(KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_"))
            }
            return true
        }

        val btnIdx = mapKeyCodeToXboxButton(keyCode) ?: return false
        val changed = if (pressed) heldKeyCodes.add(keyCode) else heldKeyCodes.remove(keyCode)
        if (changed) {
            updateButtonRefCount(btnIdx, pressed)
            if (pressed) updateLastHint("${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")}→${xboxButtonName(btnIdx)}")
        }
        return true
    }

    /**
     * Xử lý MotionEvent chuột đã bắt (View.OnCapturedPointerListener, API 26+) cho Bản A.
     */
    fun handleCapturedPointerEvent(event: MotionEvent): Boolean {
        val cur = _state.value
        if (cur.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting || cur.isPausedByEsc) return false

        if (!cur.isPointerCaptured) {
            _state.update { it.copy(isPointerCaptured = true) }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = event.x
                val dy = event.y
                if (dx != 0f || dy != 0f) {
                    queueMouseDelta(dx, dy)
                }
                applyMouseButtonMask(event.buttonState)
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                handleSingleActionButton(event.actionButton, true)
                applyMouseButtonMask(event.buttonState)
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                handleSingleActionButton(event.actionButton, false)
                applyMouseButtonMask(event.buttonState)
                return true
            }
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP -> {
                applyMouseButtonMask(event.buttonState)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAllMouseAndRightStick()
                return true
            }
        }
        return true
    }

    /**
     * Khi ở Bản A nhưng chưa bắt chuột (hoặc đang tạm dừng bởi Esc), chạm/click chuột vào WebView game để bắt chuột lại.
     */
    fun handleUnCapturedMouseTouch(event: MotionEvent): Boolean {
        if (_state.value.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting) return false
        val isMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        if (!isMouse) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestPointerCaptureNow()
                return true
            }
        }
        return false
    }

    private fun handleSingleActionButton(actionButton: Int, pressed: Boolean) {
        val bit = when (actionButton) {
            MotionEvent.BUTTON_PRIMARY -> MotionEvent.BUTTON_PRIMARY
            MotionEvent.BUTTON_SECONDARY -> MotionEvent.BUTTON_SECONDARY
            MotionEvent.BUTTON_TERTIARY -> MotionEvent.BUTTON_TERTIARY
            else -> return
        }
        val nextMask = if (pressed) (lastMouseButtonMask or bit) else (lastMouseButtonMask and bit.inv())
        applyMouseButtonMask(nextMask)
    }

    private fun applyMouseButtonMask(mask: Int) {
        val relevant = mask and (MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY)
        val diff = relevant xor lastMouseButtonMask
        if (diff == 0) return
        lastMouseButtonMask = relevant

        if ((diff and MotionEvent.BUTTON_PRIMARY) != 0) {
            val down = (relevant and MotionEvent.BUTTON_PRIMARY) != 0
            updateButtonRefCount(7, down) // Chuột trái -> RT
            if (down) updateLastHint("LMB→RT")
        }
        if ((diff and MotionEvent.BUTTON_SECONDARY) != 0) {
            val down = (relevant and MotionEvent.BUTTON_SECONDARY) != 0
            updateButtonRefCount(6, down) // Chuột phải -> LT
            if (down) updateLastHint("RMB→LT")
        }
        if ((diff and MotionEvent.BUTTON_TERTIARY) != 0) {
            val down = (relevant and MotionEvent.BUTTON_TERTIARY) != 0
            updateButtonRefCount(11, down) // Chuột giữa -> R3
            if (down) updateLastHint("MMB→R3")
        }
    }

    private fun queueMouseDelta(dx: Float, dy: Float) {
        pendingMouseDx += dx
        pendingMouseDy += dy
        val now = SystemClock.uptimeMillis()
        if (!mouseFlushScheduled) {
            val elapsed = now - lastMouseFlushMs
            if (elapsed >= MOUSE_FLUSH_INTERVAL_MS) {
                flushPendingMouseDelta()
            } else {
                mouseFlushScheduled = true
                mainHandler.postDelayed(mouseFlushRunnable, MOUSE_FLUSH_INTERVAL_MS - elapsed)
            }
        }
    }

    private fun flushPendingMouseDelta() {
        lastMouseFlushMs = SystemClock.uptimeMillis()
        val dx = pendingMouseDx
        val dy = pendingMouseDy
        pendingMouseDx = 0f
        pendingMouseDy = 0f
        if (abs(dx) < 0.05f && abs(dy) < 0.05f) return

        val sens = _state.value.mouseSensitivity
        val scale = sens / MOUSE_BASE_PIXELS_FOR_FULL_STICK
        val rawX = dx * scale
        val rawY = dy * scale
        val mag = hypot(rawX.toDouble(), rawY.toDouble()).toFloat()
        if (mag < 0.005f) return

        val clampedMag = mag.coerceAtMost(1f)
        val boostedMag = (MOUSE_ANTI_DEADZONE + (1f - MOUSE_ANTI_DEADZONE) * clampedMag).coerceIn(0f, 1f)
        val factor = boostedMag / mag
        val outX = (rawX * factor).coerceIn(-1f, 1f)
        val outY = (rawY * factor).coerceIn(-1f, 1f)

        sendStick(2, outX, 3, outY)
        mainHandler.removeCallbacks(mouseStopWatchdogRunnable)
        mainHandler.postDelayed(mouseStopWatchdogRunnable, MOUSE_STOP_WATCHDOG_MS)
    }

    private fun recomputeAndSendLeftStick() {
        var x = 0f
        var y = 0f
        if (heldKeyCodes.contains(KeyEvent.KEYCODE_A)) x -= 1f
        if (heldKeyCodes.contains(KeyEvent.KEYCODE_D)) x += 1f
        if (heldKeyCodes.contains(KeyEvent.KEYCODE_W)) y -= 1f
        if (heldKeyCodes.contains(KeyEvent.KEYCODE_S)) y += 1f
        val mag = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (mag > 1f) {
            x /= mag
            y /= mag
        }
        sendStick(0, x, 1, y)
    }

    private fun updateButtonRefCount(btnIdx: Int, pressed: Boolean) {
        val prev = buttonRefCounts[btnIdx] ?: 0
        if (pressed) {
            buttonRefCounts[btnIdx] = prev + 1
            if (prev == 0) sendButton(btnIdx, true)
        } else {
            val next = (prev - 1).coerceAtLeast(0)
            if (next == 0) {
                buttonRefCounts.remove(btnIdx)
                if (prev > 0) sendButton(btnIdx, false)
            } else {
                buttonRefCounts[btnIdx] = next
            }
        }
    }

    private fun sendButton(btnIdx: Int, pressed: Boolean) {
        val wv = webViewRef?.get() ?: return
        val rv = if (pressed) 1 else 0
        val js = "if(window.__cpBtn){window.__cpBtn($btnIdx,$pressed,$rv);}"
        wv.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun sendStick(xAxis: Int, xVal: Float, yAxis: Int, yVal: Float) {
        val rx = (xVal.coerceIn(-1f, 1f) * 100f).roundToInt() / 100f
        val ry = (yVal.coerceIn(-1f, 1f) * 100f).roundToInt() / 100f
        if (xAxis == 0) {
            if (rx == lastLeftStickX && ry == lastLeftStickY) return
            lastLeftStickX = rx
            lastLeftStickY = ry
        } else {
            if (rx == lastRightStickX && ry == lastRightStickY) return
            lastRightStickX = rx
            lastRightStickY = ry
        }
        val wv = webViewRef?.get() ?: return
        val js = "if(window.__cpStick){window.__cpStick($xAxis,$rx,$yAxis,$ry);}"
        wv.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }
    }

    private fun updateLastHint(label: String) {
        if (_state.value.lastKeyHint != label) {
            _state.update { it.copy(lastKeyHint = label) }
        }
    }

    private fun isGamepadOrSystemKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_CAMERA -> return true
        }
        val src = event.source
        val isGamepad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (isGamepad && KeyEvent.isGamepadButton(event.keyCode)) return true
        return KeyEvent.isGamepadButton(event.keyCode)
    }

    private fun isMappedKeyCode(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_W ||
            keyCode == KeyEvent.KEYCODE_A ||
            keyCode == KeyEvent.KEYCODE_S ||
            keyCode == KeyEvent.KEYCODE_D ||
            mapKeyCodeToXboxButton(keyCode) != null
    }

    private fun mapKeyCodeToXboxButton(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_SPACE -> 0           // A
        KeyEvent.KEYCODE_C,
        KeyEvent.KEYCODE_CTRL_LEFT -> 1       // B
        KeyEvent.KEYCODE_R,
        KeyEvent.KEYCODE_E -> 2               // X
        KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_1 -> 3               // Y
        KeyEvent.KEYCODE_Q -> 4               // LB
        KeyEvent.KEYCODE_G,
        KeyEvent.KEYCODE_2 -> 5               // RB
        KeyEvent.KEYCODE_TAB -> 8             // View / Select
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_M -> 9               // Menu / Start
        KeyEvent.KEYCODE_SHIFT_LEFT -> 10     // L3
        KeyEvent.KEYCODE_V -> 11              // R3
        KeyEvent.KEYCODE_DPAD_UP -> 12        // D-Pad Up
        KeyEvent.KEYCODE_DPAD_DOWN -> 13      // D-Pad Down
        KeyEvent.KEYCODE_DPAD_LEFT -> 14      // D-Pad Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15     // D-Pad Right
        KeyEvent.KEYCODE_F1 -> 16             // Xbox / Guide
        else -> null
    }

    private fun xboxButtonName(idx: Int): String = when (idx) {
        0 -> "A"; 1 -> "B"; 2 -> "X"; 3 -> "Y"
        4 -> "LB"; 5 -> "RB"; 6 -> "LT"; 7 -> "RT"
        8 -> "View"; 9 -> "Menu"; 10 -> "L3"; 11 -> "R3"
        12 -> "↑"; 13 -> "↓"; 14 -> "←"; 15 -> "→"; 16 -> "Guide"
        else -> "#$idx"
    }
}

/**
 * Chuỗi giao diện đủ 12 ngôn ngữ cho thử nghiệm Bàn phím + Chuột (Bản A & Bản B).
 */
object KbmPrototypeStrings {
    private fun pick(config: AppConfig, map: Map<String, String>): String {
        val code = config.appLanguage.effectiveLanguageCode()
        return map[code] ?: map["en"] ?: ""
    }

    fun sectionTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Thử nghiệm Phím + Chuột",
        "en" to "Keyboard + Mouse Test",
        "es" to "Prueba Teclado + Ratón",
        "pt" to "Teste Teclado + Mouse",
        "fr" to "Test Clavier + Souris",
        "de" to "Tastatur + Maus Test",
        "ja" to "キーボード・マウス試験",
        "ko" to "키보드 + 마우스 테스트",
        "zh" to "键鼠测试",
        "id" to "Uji Keyboard + Mouse",
        "th" to "ทดสอบคีย์บอร์ด+เมาส์",
        "ru" to "Тест Клавиатура + Мышь"
    ))

    fun offLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Tắt", "en" to "Off", "es" to "No", "pt" to "Off",
        "fr" to "Arrêt", "de" to "Aus", "ja" to "オフ", "ko" to "끔",
        "zh" to "关闭", "id" to "Mati", "th" to "ปิด", "ru" to "Выкл"
    ))

    fun modeALabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Bản A: Giả tay cầm", "en" to "Ver A: Gamepad", "es" to "Ver A: Mando",
        "pt" to "Ver A: Controle", "fr" to "Ver A: Manette", "de" to "Ver A: Gamepad",
        "ja" to "A版: パッド化", "ko" to "A판: 패드변환", "zh" to "A版: 模拟手柄",
        "id" to "Ver A: Gamepad", "th" to "แบบ A: จอยจำลอง", "ru" to "Вер. A: Геймпад"
    ))

    fun modeBLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Bản B: Gốc (Web)", "en" to "Ver B: Native", "es" to "Ver B: Nativo",
        "pt" to "Ver B: Nativo", "fr" to "Ver B: Natif", "de" to "Ver B: Nativ",
        "ja" to "B版: ネイティブ", "ko" to "B판: 기본입력", "zh" to "B版: 原生键鼠",
        "id" to "Ver B: Asli", "th" to "แบบ B: ต้นฉบับ", "ru" to "Вер. B: Нативный"
    ))

    fun pillModeShort(c: AppConfig, mode: KbmTestMode): String = when (mode) {
        KbmTestMode.OFF -> pick(c, mapOf(
            "vi" to "KBM: Tắt", "en" to "KBM: Off", "es" to "KBM: No", "pt" to "KBM: Off",
            "fr" to "KBM: Off", "de" to "KBM: Aus", "ja" to "KBM: 切", "ko" to "KBM: 끔",
            "zh" to "键鼠: 关", "id" to "KBM: Mati", "th" to "KBM: ปิด", "ru" to "KBM: Выкл"
        ))
        KbmTestMode.VIRTUAL_GAMEPAD -> pick(c, mapOf(
            "vi" to "KBM A: Tay cầm", "en" to "KBM A: Pad", "es" to "KBM A: Mando", "pt" to "KBM A: Pad",
            "fr" to "KBM A: Pad", "de" to "KBM A: Pad", "ja" to "KBM A: パッド", "ko" to "KBM A: 패드",
            "zh" to "键鼠 A: 手柄", "id" to "KBM A: Pad", "th" to "KBM A: จอย", "ru" to "KBM A: Пад"
        ))
        KbmTestMode.NATIVE_PASSTHROUGH -> pick(c, mapOf(
            "vi" to "KBM B: Gốc", "en" to "KBM B: Native", "es" to "KBM B: Nativo", "pt" to "KBM B: Nativo",
            "fr" to "KBM B: Natif", "de" to "KBM B: Nativ", "ja" to "KBM B: 原生", "ko" to "KBM B: 기본",
            "zh" to "键鼠 B: 原生", "id" to "KBM B: Asli", "th" to "KBM B: ต้นฉบับ", "ru" to "KBM B: Натив"
        ))
    }

    fun captureMouseLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Bắt chuột", "en" to "Lock Mouse", "es" to "Fijar ratón", "pt" to "Travar mouse",
        "fr" to "Capturer", "de" to "Maus fangen", "ja" to "マウス固定", "ko" to "마우스 고정",
        "zh" to "锁定鼠标", "id" to "Kunci Mouse", "th" to "จับเมาส์", "ru" to "Захват мыши"
    ))

    fun releaseEscLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Nhả (Esc)", "en" to "Release (Esc)", "es" to "Soltar (Esc)", "pt" to "Soltar (Esc)",
        "fr" to "Libérer (Esc)", "de" to "Frei (Esc)", "ja" to "解除(Esc)", "ko" to "해제(Esc)",
        "zh" to "释放(Esc)", "id" to "Lepas (Esc)", "th" to "ปล่อย (Esc)", "ru" to "Отпустить (Esc)"
    ))

    fun testWebLockLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Thử khóa Web", "en" to "Test Web Lock", "es" to "Probar bloqueo", "pt" to "Testar trava",
        "fr" to "Test Lock Web", "de" to "Web-Lock Test", "ja" to "Webロック試験", "ko" to "웹 잠금 시험",
        "zh" to "测试网页锁定", "id" to "Uji Kunci Web", "th" to "ลองล็อกเว็บ", "ru" to "Тест Web Lock"
    ))

    fun mouseSensitivityLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Độ nhạy chuột", "en" to "Mouse sensitivity", "es" to "Sensibilidad", "pt" to "Sensibilidade",
        "fr" to "Sensibilité", "de" to "Mausempfindlichkeit", "ja" to "マウス感度", "ko" to "마우스 감도",
        "zh" to "鼠标灵敏度", "id" to "Sensitivitas", "th" to "ความไวเมาส์", "ru" to "Чувствительность"
    ))

    fun modeAHint(c: AppConfig) = pick(c, mapOf(
        "vi" to "WASD đi • Chuột nhìn (Trái=RT, Phải=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB, Shift=L3 • Esc để nhả chuột.",
        "en" to "WASD move • Mouse aim (LMB=RT, RMB=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB, Shift=L3 • Esc to release.",
        "es" to "WASD mover • Ratón mirar (Izq=RT, Der=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc suelta.",
        "pt" to "WASD mover • Mouse mirar (Esq=RT, Dir=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc solta.",
        "fr" to "WASD bouger • Souris viser (G=RT, D=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc libère.",
        "de" to "WASD bewegen • Maus zielen (L=RT, R=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc löst.",
        "ja" to "WASD移動・マウス視点(左=RT,右=LT)・Space=A,C=B,R/E=X,F=Y,Q=LB,G=RB・Escで解除。",
        "ko" to "WASD 이동 • 마우스 시점(좌=RT,우=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc 해제.",
        "zh" to "WASD移动 • 鼠标视角(左=RT,右=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc释放。",
        "id" to "WASD gerak • Mouse bidik (Kiri=RT, Kanan=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc lepas.",
        "th" to "WASD เดิน • เมาส์เล็ง (ซ้าย=RT, ขวา=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc ปล่อยเมาส์",
        "ru" to "WASD шаг • Мышь обзор (ЛКМ=RT, ПКМ=LT) • Space=A, C=B, R/E=X, F=Y, Q=LB, G=RB • Esc отпуск."
    ))

    fun modeBHint(c: AppConfig) = pick(c, mapOf(
        "vi" to "Gửi phím & chuột thẳng vào trang Xbox (chỉ game hỗ trợ KBM gốc). Bấm Thử khóa Web để kiểm tra Pointer Lock.",
        "en" to "Passes keyboard & mouse straight to Xbox page (native KBM games only). Tap Test Web Lock to check Pointer Lock.",
        "es" to "Envía teclado y ratón directo a Xbox (solo juegos KBM). Pulsa Probar bloqueo para verificar Pointer Lock.",
        "pt" to "Envia teclado e mouse direto ao Xbox (jogos KBM). Toque em Testar trava para checar Pointer Lock.",
        "fr" to "Envoie clavier et souris directement à Xbox (jeux KBM). Touchez Test Lock Web pour vérifier Pointer Lock.",
        "de" to "Leitet Tastatur & Maus direkt an Xbox (nur KBM-Spiele). Tippe Web-Lock Test für Pointer Lock.",
        "ja" to "Xboxページへ直接入力（ネイティブKBM対応ゲーム向け）。Webロック試験でPointer Lockを確認。",
        "ko" to "Xbox 페이지로 키보드/마우스 직접 전달(네이티브 게임 전용). 웹 잠금 시험으로 확인하세요.",
        "zh" to "将键盘鼠标直通 Xbox 页面（仅原生支持游戏）。点测试网页锁定检查 Pointer Lock。",
        "id" to "Kirim keyboard & mouse langsung ke Xbox (game KBM asli). Ketuk Uji Kunci Web untuk cek Pointer Lock.",
        "th" to "ส่งคีย์บอร์ดและเมาส์ตรงไปหน้า Xbox (เฉพาะเกมที่รองรับ) กดลองล็อกเว็บเพื่อตรวจ Pointer Lock",
        "ru" to "Передаёт клавиатуру и мышь напрямую в Xbox (только KBM-игры). Нажмите Тест Web Lock для проверки."
    ))
}
