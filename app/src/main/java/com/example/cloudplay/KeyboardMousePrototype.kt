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

enum class PcOverlayMode {
    AUTO,           // Tự hiện bàn phím+chuột ảo PC khi chưa cắm phím/chuột thật; tự ẩn khi đã cắm
    TOUCH_PC,       // Luôn hiện bàn phím+chuột ảo PC trên màn hình
    HARDWARE_ONLY   // Chỉ dùng bàn phím+chuột thật (ẩn hết nút cảm ứng)
}

data class KbmRuntimeState(
    val mode: KbmTestMode = KbmTestMode.OFF,
    val preferredControlIsPc: Boolean = false,
    val preferredPcEngine: KbmTestMode = KbmTestMode.VIRTUAL_GAMEPAD,
    val pcOverlayMode: PcOverlayMode = PcOverlayMode.AUTO,
    val hasPhysicalKeyboard: Boolean = false,
    val hasPhysicalMouse: Boolean = false,
    val isPointerCaptured: Boolean = false,
    val isPausedByEsc: Boolean = false,
    val mouseSensitivity: Float = 1.0f,
    val invertMouseY: Boolean = false,
    val tapBackgroundMouseClick: Boolean = false,
    val directTouchPassThrough: Boolean = false,
    val nativePointerLockStatus: String = "",
    val lastKeyHint: String = ""
) {
    val isPcModeActive: Boolean
        get() = mode != KbmTestMode.OFF

    val hasAnyPhysicalKbm: Boolean
        get() = hasPhysicalKeyboard || hasPhysicalMouse

    val shouldShowTouchPcOverlay: Boolean
        get() = isPcModeActive && when (pcOverlayMode) {
            PcOverlayMode.TOUCH_PC -> true
            PcOverlayMode.HARDWARE_ONLY -> false
            PcOverlayMode.AUTO -> !hasAnyPhysicalKbm
        }
}

/**
 * Bộ điều khiển Bàn phím & Chuột (Chế độ PC — Hybrid Cách 3):
 * - Tự nhận phím/chuột vật lý (OTG/Bluetooth); khi chưa cắm sẽ hiện bộ phím & chuột ảo tối ưu cho người chơi PC.
 * - Hỗ trợ động cơ Mọi game (VIRTUAL_GAMEPAD qua __cpBtn/__cpStick + PointerCapture API 26+) và Gốc Web (NATIVE_PASSTHROUGH).
 */
object KeyboardMousePrototype {

    private const val MOUSE_FLUSH_INTERVAL_MS = 8L
    private const val MOUSE_STOP_WATCHDOG_MS = 45L
    private const val MOUSE_BASE_PIXELS_FOR_FULL_STICK = 26f
    private const val TOUCHPAD_BASE_PIXELS_FOR_FULL_STICK = 22f
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
    private var pendingDeltaIsTouchpad: Boolean = false
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
        refreshPhysicalPeripherals()
        if (!inGameStream || routeToAppUi) {
            releaseAllAndUncapture(if (routeToAppUi) "app_ui_open" else "left_stream")
        }
    }

    fun refreshPhysicalPeripherals() {
        var foundKeyboard = false
        var foundMouse = false
        try {
            val ids = InputDevice.getDeviceIds()
            for (id in ids) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (dev.isVirtual) continue
                val src = dev.sources
                val isGamepad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                if (!isGamepad && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    val nameLower = (dev.name ?: "").lowercase()
                    if (!nameLower.contains("gpio") && !nameLower.contains("pwrkey") && !nameLower.contains("headset")) {
                        foundKeyboard = true
                    }
                }
                if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                    (src and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ) {
                    foundMouse = true
                }
            }
        } catch (_: Exception) {}

        _state.update { cur ->
            if (cur.hasPhysicalKeyboard == foundKeyboard && cur.hasPhysicalMouse == foundMouse) {
                cur
            } else {
                cur.copy(hasPhysicalKeyboard = foundKeyboard, hasPhysicalMouse = foundMouse)
            }
        }
    }

    fun setPcModeEnabled(enabled: Boolean, rememberStyle: Boolean = true) {
        refreshPhysicalPeripherals()
        if (rememberStyle) {
            _state.update { it.copy(preferredControlIsPc = enabled) }
        }
        if (!enabled) {
            setMode(KbmTestMode.OFF)
        } else {
            val engine = _state.value.preferredPcEngine.takeIf { it != KbmTestMode.OFF }
                ?: KbmTestMode.VIRTUAL_GAMEPAD
            setMode(engine)
        }
    }

    fun setPreferredPcEngine(engine: KbmTestMode) {
        val valid = if (engine == KbmTestMode.NATIVE_PASSTHROUGH) {
            KbmTestMode.NATIVE_PASSTHROUGH
        } else {
            KbmTestMode.VIRTUAL_GAMEPAD
        }
        _state.update { it.copy(preferredPcEngine = valid) }
        if (_state.value.isPcModeActive) {
            setMode(valid)
        }
    }

    fun setPcOverlayMode(overlayMode: PcOverlayMode) {
        refreshPhysicalPeripherals()
        _state.update { it.copy(pcOverlayMode = overlayMode) }
        if (_state.value.mode == KbmTestMode.VIRTUAL_GAMEPAD && overlayMode == PcOverlayMode.HARDWARE_ONLY) {
            requestPointerCaptureNow()
        }
    }

    fun setMode(newMode: KbmTestMode) {
        refreshPhysicalPeripherals()
        val prev = _state.value.mode
        if (prev == newMode) return
        releaseAllAndUncapture("mode_switch")
        _state.update {
            it.copy(
                mode = newMode,
                preferredPcEngine = if (newMode != KbmTestMode.OFF) newMode else it.preferredPcEngine,
                isPausedByEsc = false,
                nativePointerLockStatus = "",
                lastKeyHint = ""
            )
        }
        AppLogger.info("KBM", "Đổi chế độ Bàn phím & Chuột: $prev -> $newMode")
        if (newMode == KbmTestMode.VIRTUAL_GAMEPAD && _state.value.hasPhysicalMouse) {
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

    fun setMouseSensitivity(value: Float) {
        val clamped = ((value.coerceIn(0.4f, 2.5f)) * 10f).roundToInt() / 10f
        _state.update { it.copy(mouseSensitivity = clamped) }
    }

    fun setInvertMouseY(invert: Boolean) {
        _state.update { it.copy(invertMouseY = invert) }
    }

    fun setTapBackgroundMouseClick(enabled: Boolean) {
        _state.update { it.copy(tapBackgroundMouseClick = enabled) }
    }

    fun setDirectTouchPassThrough(enabled: Boolean) {
        _state.update { it.copy(directTouchPassThrough = enabled) }
        if (enabled) {
            dispatchTouchTrackpadRelease()
        }
    }

    /**
     * Tự động nhả khóa chuột vật lý nếu phát hiện người dùng chạm màn hình cảm ứng hoặc bấm tay cầm.
     */
    fun onTouchOrGamepadDetected(reason: String) {
        if (_state.value.isPointerCaptured) {
            releaseAllAndUncapture(reason)
            _state.update { it.copy(isPausedByEsc = true) }
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
     * Chế độ Gốc (Web): kiểm tra xem WebView hiện tại có hỗ trợ & cho phép Web Pointer Lock không.
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
                    AppLogger.info("KBM", "Bản Gốc (Web) kiểm tra PointerLock: $status")
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Dùng chung từ PcKeyboardMouseOverlay (vùng rê chuột cảm ứng nửa phải màn hình).
     */
    fun dispatchTouchTrackpadDelta(dx: Float, dy: Float) {
        if (!_state.value.isPcModeActive || !isInGameStream || isAppUiRouting) return
        if (dx == 0f && dy == 0f) return
        queueMouseDelta(dx, dy, isTouchpad = true)
    }

    fun dispatchTouchTrackpadRelease() {
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mainHandler.removeCallbacks(mouseStopWatchdogRunnable)
        mouseFlushScheduled = false
        pendingMouseDx = 0f
        pendingMouseDy = 0f
        if (lastRightStickX != 0f || lastRightStickY != 0f) {
            sendStick(2, 0f, 3, 0f)
        }
    }

    /**
     * Dùng chung từ PcKeyboardMouseOverlay cho nút phím ảo PC (có đối xứng ref-count).
     */
    fun dispatchVirtualPcButton(btnIdx: Int, pressed: Boolean, hintLabel: String = "") {
        if (btnIdx !in 0..16) return
        updateButtonRefCount(btnIdx, pressed)
        if (pressed && hintLabel.isNotBlank()) {
            updateLastHint(hintLabel)
        }
    }

    fun dispatchVirtualPcLeftStick(x: Float, y: Float) {
        sendStick(0, x, 1, y)
    }

    private val virtualWasdRefCounts = HashMap<String, Int>()
    private val activeWebKeys = HashSet<String>()

    /**
     * Gửi 1 hoặc nhiều phím PC (từ bàn phím ảo / nút gộp chọn trên bàn phím Full-Size).
     * Hỗ trợ đồng thời kiểu Mọi game (ánh xạ tay cầm + phím web bổ sung) và kiểu Gốc Web.
     */
    fun dispatchVirtualPcKeySpecs(keys: List<String>, pressed: Boolean) {
        if (keys.isEmpty()) return
        val curMode = _state.value.mode
        if (curMode == KbmTestMode.OFF) return

        for (rawKey in keys) {
            val k = rawKey.trim().uppercase()
            if (k.isEmpty()) continue
            if (curMode == KbmTestMode.VIRTUAL_GAMEPAD) {
                when (k) {
                    "W", "A", "S", "D" -> {
                        val prev = virtualWasdRefCounts[k] ?: 0
                        if (pressed) {
                            virtualWasdRefCounts[k] = prev + 1
                        } else {
                            val next = (prev - 1).coerceAtLeast(0)
                            if (next == 0) virtualWasdRefCounts.remove(k) else virtualWasdRefCounts[k] = next
                        }
                        recomputeVirtualWasdStick()
                    }
                    else -> {
                        val mappedBtn = mapPcKeyNameToXboxButton(k)
                        if (mappedBtn != null) {
                            updateButtonRefCount(mappedBtn, pressed)
                        } else {
                            dispatchSyntheticWebKey(k, pressed)
                        }
                    }
                }
            } else {
                // NATIVE_PASSTHROUGH: gửi trực tiếp sự kiện phím/chuột xuống trang Xbox
                dispatchSyntheticWebKey(k, pressed)
            }
        }
        if (pressed) {
            updateLastHint(keys.joinToString("+"))
        }
    }

    private fun recomputeVirtualWasdStick() {
        var x = 0f
        var y = 0f
        if ((virtualWasdRefCounts["A"] ?: 0) > 0) x -= 1f
        if ((virtualWasdRefCounts["D"] ?: 0) > 0) x += 1f
        if ((virtualWasdRefCounts["W"] ?: 0) > 0) y -= 1f
        if ((virtualWasdRefCounts["S"] ?: 0) > 0) y += 1f
        val mag = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (mag > 1f) {
            x /= mag
            y /= mag
        }
        sendStick(0, x, 1, y)
    }

    private fun mapPcKeyNameToXboxButton(key: String): Int? = when (key) {
        "SPACE" -> 0
        "CTRL", "C" -> 1
        "R", "E" -> 2
        "F", "1" -> 3
        "Q" -> 4
        "G", "2" -> 5
        "RMB" -> 6
        "LMB" -> 7
        "TAB" -> 8
        "ESC", "M", "ENTER" -> 9
        "SHIFT" -> 10
        "V", "MMB" -> 11
        "UP", "↑" -> 12
        "DOWN", "↓" -> 13
        "LEFT", "←" -> 14
        "RIGHT", "→" -> 15
        "F1" -> 16
        else -> null
    }

    private fun dispatchSyntheticWebKey(key: String, pressed: Boolean) {
        val wv = webViewRef?.get() ?: return
        if (pressed) activeWebKeys.add(key) else activeWebKeys.remove(key)

        if (key == "LMB" || key == "RMB" || key == "MMB") {
            val btnNum = when (key) {
                "LMB" -> 0
                "MMB" -> 1
                else -> 2
            }
            val type = if (pressed) "mousedown" else "mouseup"
            val js = "(function(){var el=document.querySelector('video#stream-video,video')||document.activeElement||document.body;if(el){el.dispatchEvent(new MouseEvent('$type',{bubbles:true,cancelable:true,button:$btnNum}));}})();"
            wv.post { try { wv.evaluateJavascript(js, null) } catch (_: Exception) {} }
            return
        }

        val (jsKey, jsCode) = mapPcKeyNameToWebKeyCode(key)
        val type = if (pressed) "keydown" else "keyup"
        val safeKey = jsKey.replace("'", "\\'")
        val safeCode = jsCode.replace("'", "\\'")
        val js = "(function(){var el=document.activeElement||document.querySelector('video#stream-video,video')||document.body;var ev=new KeyboardEvent('$type',{key:'$safeKey',code:'$safeCode',bubbles:true,cancelable:true});(el||window).dispatchEvent(ev);})();"
        wv.post { try { wv.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }

    private fun mapPcKeyNameToWebKeyCode(key: String): Pair<String, String> = when (key) {
        "ESC" -> "Escape" to "Escape"
        "TAB" -> "Tab" to "Tab"
        "CAPS" -> "CapsLock" to "CapsLock"
        "SHIFT" -> "Shift" to "ShiftLeft"
        "CTRL" -> "Control" to "ControlLeft"
        "ALT" -> "Alt" to "AltLeft"
        "SPACE" -> " " to "Space"
        "ENTER" -> "Enter" to "Enter"
        "BKSP", "BACKSPACE" -> "Backspace" to "Backspace"
        "UP", "↑" -> "ArrowUp" to "ArrowUp"
        "DOWN", "↓" -> "ArrowDown" to "ArrowDown"
        "LEFT", "←" -> "ArrowLeft" to "ArrowLeft"
        "RIGHT", "→" -> "ArrowRight" to "ArrowRight"
        else -> when {
            key.length == 1 && key[0] in 'A'..'Z' -> key.lowercase() to "Key$key"
            key.length == 1 && key[0] in '0'..'9' -> key to "Digit$key"
            key.startsWith("F") && key.drop(1).toIntOrNull() in 1..12 -> key to key
            else -> key to key
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
            virtualWasdRefCounts.isNotEmpty() ||
            activeWebKeys.isNotEmpty() ||
            lastMouseButtonMask != 0 ||
            lastLeftStickX != 0f || lastLeftStickY != 0f ||
            lastRightStickX != 0f || lastRightStickY != 0f

        val activeButtons = buttonRefCounts.keys.toList()
        val staleWebKeys = activeWebKeys.toList()
        heldKeyCodes.clear()
        buttonRefCounts.clear()
        virtualWasdRefCounts.clear()
        activeWebKeys.clear()
        lastMouseButtonMask = 0

        for (wk in staleWebKeys) {
            dispatchSyntheticWebKey(wk, false)
        }

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
     * Xử lý KeyEvent từ bàn phím thật cho chế độ Mọi game (VIRTUAL_GAMEPAD).
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val cur = _state.value
        if (cur.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting) return false
        if (isGamepadOrSystemKey(event)) return false

        if (!cur.hasPhysicalKeyboard) {
            _state.update { it.copy(hasPhysicalKeyboard = true) }
        }

        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        val pressed = action == KeyEvent.ACTION_DOWN
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (pressed && event.repeatCount == 0) {
                releaseAllAndUncapture("escape_key")
                _state.update { it.copy(isPausedByEsc = true, lastKeyHint = "ESC") }
            }
            return true
        }

        if (cur.isPausedByEsc) return false

        if (pressed && event.repeatCount > 0) {
            return isMappedKeyCode(keyCode)
        }

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
     * Xử lý MotionEvent chuột đã bắt (View.OnCapturedPointerListener, API 26+).
     */
    fun handleCapturedPointerEvent(event: MotionEvent): Boolean {
        val cur = _state.value
        if (cur.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting || cur.isPausedByEsc) return false

        if (!cur.isPointerCaptured || !cur.hasPhysicalMouse) {
            _state.update { it.copy(isPointerCaptured = true, hasPhysicalMouse = true) }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                val dx = event.x
                val dy = event.y
                if (dx != 0f || dy != 0f) {
                    queueMouseDelta(dx, dy, isTouchpad = false)
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

    fun handleUnCapturedMouseTouch(event: MotionEvent): Boolean {
        if (_state.value.mode != KbmTestMode.VIRTUAL_GAMEPAD) return false
        if (!isInGameStream || isAppUiRouting) return false
        val isMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        if (!isMouse) return false
        if (!_state.value.hasPhysicalMouse) {
            _state.update { it.copy(hasPhysicalMouse = true) }
        }
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
            updateButtonRefCount(7, down)
            if (down) updateLastHint("LMB→RT")
        }
        if ((diff and MotionEvent.BUTTON_SECONDARY) != 0) {
            val down = (relevant and MotionEvent.BUTTON_SECONDARY) != 0
            updateButtonRefCount(6, down)
            if (down) updateLastHint("RMB→LT")
        }
        if ((diff and MotionEvent.BUTTON_TERTIARY) != 0) {
            val down = (relevant and MotionEvent.BUTTON_TERTIARY) != 0
            updateButtonRefCount(11, down)
            if (down) updateLastHint("MMB→R3")
        }
    }

    private fun queueMouseDelta(dx: Float, dy: Float, isTouchpad: Boolean) {
        pendingMouseDx += dx
        pendingMouseDy += dy
        pendingDeltaIsTouchpad = isTouchpad
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

        val cur = _state.value
        val basePx = if (pendingDeltaIsTouchpad) TOUCHPAD_BASE_PIXELS_FOR_FULL_STICK else MOUSE_BASE_PIXELS_FOR_FULL_STICK
        val scale = cur.mouseSensitivity / basePx
        val rawX = dx * scale
        val rawY = (if (cur.invertMouseY) -dy else dy) * scale
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
 * Chuỗi giao diện đủ 12 ngôn ngữ cho giao diện "Tay cầm & Bàn phím" và Chế độ PC Hybrid.
 */
object KbmPrototypeStrings {
    private fun pick(config: AppConfig, map: Map<String, String>): String {
        val code = config.appLanguage.effectiveLanguageCode()
        return map[code] ?: map["en"] ?: ""
    }

    fun controllerAndKeyboardTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Tay cầm & Bàn phím",
        "en" to "Controller & Keyboard",
        "es" to "Mando y Teclado",
        "pt" to "Controle e Teclado",
        "fr" to "Manette et Clavier",
        "de" to "Controller & Tastatur",
        "ja" to "コントローラー＆キーボード",
        "ko" to "컨트롤러 및 키보드",
        "zh" to "手柄与键鼠",
        "id" to "Kontroler & Keyboard",
        "th" to "จอยและคีย์บอร์ด",
        "ru" to "Геймпад и Клавиатура"
    ))

    fun gamepadModeLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Tay cầm", "en" to "Gamepad", "es" to "Mando", "pt" to "Controle",
        "fr" to "Manette", "de" to "Gamepad", "ja" to "パッド", "ko" to "게임패드",
        "zh" to "手柄", "id" to "Gamepad", "th" to "จอย", "ru" to "Геймпад"
    ))

    fun pcKbmModeLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Phím & Chuột", "en" to "PC KBM", "es" to "Teclado/Ratón", "pt" to "Teclado/Mouse",
        "fr" to "Clavier/Souris", "de" to "Tastatur/Maus", "ja" to "キー/マウス", "ko" to "키보드/마우스",
        "zh" to "键鼠模式", "id" to "Keyboard/Mouse", "th" to "คีย์บอร์ด/เมาส์", "ru" to "Клава/Мышь"
    ))

    fun hideModeLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Ẩn", "en" to "Hide", "es" to "Ocultar", "pt" to "Ocultar",
        "fr" to "Masquer", "de" to "Aus", "ja" to "非表示", "ko" to "숨김",
        "zh" to "隐藏", "id" to "Sembunyi", "th" to "ซ่อน", "ru" to "Скрыть"
    ))

    fun pcSectionTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Cài đặt Bàn phím & Chuột (PC)",
        "en" to "PC Keyboard & Mouse Settings",
        "es" to "Ajustes de Teclado y Ratón (PC)",
        "pt" to "Ajustes de Teclado e Mouse (PC)",
        "fr" to "Réglages Clavier & Souris (PC)",
        "de" to "PC Tastatur- & Mauseinstellungen",
        "ja" to "PCキーボード・マウス設定",
        "ko" to "PC 키보드/마우스 설정",
        "zh" to "PC 键鼠设置",
        "id" to "Pengaturan Keyboard & Mouse (PC)",
        "th" to "ตั้งค่าคีย์บอร์ดและเมาส์ (PC)",
        "ru" to "Настройки клавиатуры и мыши (ПК)"
    ))

    fun overlayDisplayTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Hiển thị phím PC trên màn hình",
        "en" to "On-screen PC keys",
        "es" to "Teclas PC en pantalla",
        "pt" to "Teclas PC na tela",
        "fr" to "Touches PC à l'écran",
        "de" to "PC-Tasten auf Bildschirm",
        "ja" to "画面上のPCキー表示",
        "ko" to "화면 PC 키 표시",
        "zh" to "屏幕 PC 按键显示",
        "id" to "Tombol PC di layar",
        "th" to "ปุ่ม PC บนหน้าจอ",
        "ru" to "Экранные клавиши ПК"
    ))

    fun overlayAutoLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Tự động", "en" to "Auto", "es" to "Auto", "pt" to "Auto",
        "fr" to "Auto", "de" to "Auto", "ja" to "自動", "ko" to "자동",
        "zh" to "自动", "id" to "Otomatis", "th" to "อัตโนมัติ", "ru" to "Авто"
    ))

    fun overlayTouchPcLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Phím ảo PC", "en" to "PC Touch", "es" to "Táctil PC", "pt" to "Toque PC",
        "fr" to "Tactile PC", "de" to "PC-Touch", "ja" to "仮想PC", "ko" to "가상 PC",
        "zh" to "虚拟键鼠", "id" to "Sentuh PC", "th" to "ปุ่มเสมือน PC", "ru" to "Экран ПК"
    ))

    fun overlayHardwareLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Phím thật", "en" to "Physical", "es" to "Físico", "pt" to "Físico",
        "fr" to "Physique", "de" to "Physisch", "ja" to "物理のみ", "ko" to "외장 전용",
        "zh" to "仅外接", "id" to "Fisik saja", "th" to "อุปกรณ์จริง", "ru" to "Только физ."
    ))

    fun engineTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Kiểu gửi vào game",
        "en" to "Game input method",
        "es" to "Método de entrada",
        "pt" to "Modo de entrada",
        "fr" to "Mode d'entrée",
        "de" to "Eingabemodus",
        "ja" to "ゲーム入力方式",
        "ko" to "게임 입력 방식",
        "zh" to "游戏输入模式",
        "id" to "Metode input game",
        "th" to "วิธีส่งปุ่มเข้าเกม",
        "ru" to "Режим ввода в игру"
    ))

    fun engineAllGamesLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Mọi game (Tay cầm)", "en" to "All games (Pad)", "es" to "Todos (Mando)", "pt" to "Todos (Controle)",
        "fr" to "Tous (Manette)", "de" to "Alle (Gamepad)", "ja" to "全ゲーム(パッド)", "ko" to "모든 게임(패드)",
        "zh" to "全游戏(手柄)", "id" to "Semua game (Pad)", "th" to "ทุกเกม (จอย)", "ru" to "Все игры (Пад)"
    ))

    fun engineNativeWebLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Gốc (Web Xbox)", "en" to "Native (Web)", "es" to "Nativo (Web)", "pt" to "Nativo (Web)",
        "fr" to "Natif (Web)", "de" to "Nativ (Web)", "ja" to "ネイティブ(Web)", "ko" to "네이티브(웹)",
        "zh" to "原生(网页)", "id" to "Asli (Web)", "th" to "ต้นฉบับ (เว็บ)", "ru" to "Нативный (Веб)"
    ))

    fun invertYLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Đảo trục dọc chuột (Y)", "en" to "Invert Mouse Y-Axis", "es" to "Invertir eje Y del ratón",
        "pt" to "Inverter eixo Y do mouse", "fr" to "Inverser l'axe Y souris", "de" to "Maus Y-Achse umkehren",
        "ja" to "マウスY軸反転", "ko" to "마우스 Y축 반전", "zh" to "反转鼠标 Y 轴",
        "id" to "Balik sumbu Y mouse", "th" to "กลับแกน Y เมาส์", "ru" to "Инверсия оси Y мыши"
    ))

    fun peripheralStatus(c: AppConfig, s: KbmRuntimeState): String {
        return if (s.hasAnyPhysicalKbm) {
            pick(c, mapOf(
                "vi" to "Đã nhận phím/chuột thật — tự ẩn nút cảm ứng ở chế độ Tự động.",
                "en" to "Physical keyboard/mouse detected — touch keys auto-hidden in Auto mode.",
                "es" to "Teclado/ratón físico detectado — teclas táctiles ocultas en Auto.",
                "pt" to "Teclado/mouse físico detectado — teclas ocultas no modo Auto.",
                "fr" to "Clavier/souris physique détecté — touches masquées en Auto.",
                "de" to "Physische Tastatur/Maus erkannt — Touch-Tasten in Auto ausgeblendet.",
                "ja" to "物理キーボード/マウス検出 — 自動モードで画面キーを非表示。",
                "ko" to "외장 키보드/마우스 감지됨 — 자동 모드에서 화면 키 숨김.",
                "zh" to "检测到外接键鼠 — 自动模式下已隐藏屏幕按键。",
                "id" to "Keyboard/mouse fisik terdeteksi — tombol layar disembunyikan di Auto.",
                "th" to "พบคีย์บอร์ด/เมาส์จริง — ซ่อนปุ่มเสมือนอัตโนมัติในโหมดอัตโนมัติ",
                "ru" to "Найдены физ. клавиатура/мышь — экранные клавиши скрыты в Авто."
            ))
        } else {
            pick(c, mapOf(
                "vi" to "Chưa cắm phím/chuột rời — dùng bộ phím & chuột ảo PC trên màn hình.",
                "en" to "No external KBM — using on-screen PC keyboard & touchpad mouse.",
                "es" to "Sin KBM externo — usando teclado PC y panel táctil en pantalla.",
                "pt" to "Sem KBM externo — usando teclado PC e mouse tátil na tela.",
                "fr" to "Sans KBM externe — clavier PC et pavé souris tactiles actifs.",
                "de" to "Kein externes KBM — Bildschirm-PC-Tastatur & Touchpad aktiv.",
                "ja" to "外付け未接続 — 画面上のPCキー＆タッチパッドを使用中。",
                "ko" to "외장 장치 없음 — 화면 PC 키보드 및 터치패드 사용 중.",
                "zh" to "未连接外接键鼠 — 正在使用屏幕 PC 键鼠。",
                "id" to "Belum ada KBM fisik — memakai keyboard PC & touchpad layar.",
                "th" to "ยังไม่เชื่อมต่อเมาส์/คีย์บอร์ดจริง — ใช้ปุ่ม PC และทัชแพดบนจอ",
                "ru" to "Нет внешней периферии — включены экранные клавиши и тачпад ПК."
            ))
        }
    }

    fun toastSwitchedToGamepad(c: AppConfig) = pick(c, mapOf(
        "vi" to "Chế độ: Tay cầm ảo", "en" to "Mode: Virtual Gamepad", "es" to "Modo: Mando virtual",
        "pt" to "Modo: Controle virtual", "fr" to "Mode : Manette virtuelle", "de" to "Modus: Virtuelles Gamepad",
        "ja" to "モード: 仮想コントローラー", "ko" to "모드: 가상 게임패드", "zh" to "模式：虚拟手柄",
        "id" to "Mode: Gamepad Virtual", "th" to "โหมด: จอยเสมือน", "ru" to "Режим: Виртуальный геймпад"
    ))

    fun toastSwitchedToPc(c: AppConfig) = pick(c, mapOf(
        "vi" to "Chế độ: Bàn phím & Chuột (PC)", "en" to "Mode: PC Keyboard & Mouse", "es" to "Modo: Teclado y Ratón (PC)",
        "pt" to "Modo: Teclado e Mouse (PC)", "fr" to "Mode : Clavier & Souris (PC)", "de" to "Modus: PC Tastatur & Maus",
        "ja" to "モード: PCキーボード＆マウス", "ko" to "모드: PC 키보드 및 마우스", "zh" to "模式：PC 键鼠",
        "id" to "Mode: Keyboard & Mouse (PC)", "th" to "โหมด: คีย์บอร์ดและเมาส์ (PC)", "ru" to "Режим: Клавиатура и Мышь (ПК)"
    ))

    fun toastSwitchedToHidden(c: AppConfig) = pick(c, mapOf(
        "vi" to "Đã ẩn điều khiển trên màn hình", "en" to "On-screen controls hidden", "es" to "Controles en pantalla ocultos",
        "pt" to "Controles na tela ocultos", "fr" to "Commandes à l'écran masquées", "de" to "Bildschirmsteuerung ausgeblendet",
        "ja" to "画面操作を非表示にしました", "ko" to "화면 컨트롤 숨김", "zh" to "已隐藏屏幕操控",
        "id" to "Kontrol layar disembunyikan", "th" to "ซ่อนปุ่มควบคุมบนหน้าจอแล้ว", "ru" to "Экранное управление скрыто"
    ))

    fun pillModeShort(c: AppConfig, mode: KbmTestMode): String = when (mode) {
        KbmTestMode.OFF -> hideModeLabel(c)
        KbmTestMode.VIRTUAL_GAMEPAD -> pick(c, mapOf(
            "vi" to "PC: Mọi game", "en" to "PC: Pad", "es" to "PC: Mando", "pt" to "PC: Pad",
            "fr" to "PC: Pad", "de" to "PC: Pad", "ja" to "PC: パッド", "ko" to "PC: 패드",
            "zh" to "PC: 手柄", "id" to "PC: Pad", "th" to "PC: จอย", "ru" to "ПК: Пад"
        ))
        KbmTestMode.NATIVE_PASSTHROUGH -> pick(c, mapOf(
            "vi" to "PC: Gốc Web", "en" to "PC: Native", "es" to "PC: Nativo", "pt" to "PC: Nativo",
            "fr" to "PC: Natif", "de" to "PC: Nativ", "ja" to "PC: 原生", "ko" to "PC: 기본",
            "zh" to "PC: 原生", "id" to "PC: Asli", "th" to "PC: ต้นฉบับ", "ru" to "ПК: Натив"
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
        "vi" to "WASD đi • Chuột/vuốt phải nhìn (Trái=RT, Phải=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB, Shift=L3 • Esc nhả chuột.",
        "en" to "WASD move • Mouse/right-drag aim (LMB=RT, RMB=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB, Shift=L3 • Esc release.",
        "es" to "WASD mover • Ratón/arrastre mirar (Izq=RT, Der=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc suelta.",
        "pt" to "WASD mover • Mouse/arraste mirar (Esq=RT, Dir=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc solta.",
        "fr" to "WASD bouger • Souris/glisser viser (G=RT, D=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc libère.",
        "de" to "WASD bewegen • Maus/Wischen zielen (L=RT, R=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc löst.",
        "ja" to "WASD移動・マウス/右ドラッグ視点(左=RT,右=LT)・Space=A,Ctrl/C=B,R/E=X,F=Y,Q=LB,G=RB・Esc解除。",
        "ko" to "WASD 이동 • 마우스/우측드래그 시점(좌=RT,우=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc 해제.",
        "zh" to "WASD移动 • 鼠标/右滑视角(左=RT,右=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc释放。",
        "id" to "WASD gerak • Mouse/geser kanan bidik (Kiri=RT, Kanan=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc lepas.",
        "th" to "WASD เดิน • เมาส์/ลากขวาเล็ง (ซ้าย=RT, ขวา=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc ปล่อย",
        "ru" to "WASD шаг • Мышь/свайп обзор (ЛКМ=RT, ПКМ=LT) • Space=A, Ctrl/C=B, R/E=X, F=Y, Q=LB, G=RB • Esc отпуск."
    ))

    fun modeBHint(c: AppConfig) = pick(c, mapOf(
        "vi" to "Gửi phím & chuột thẳng vào trang Xbox (cho game hỗ trợ KBM gốc). Bấm Thử khóa Web để kiểm tra Pointer Lock.",
        "en" to "Passes keyboard & mouse straight to Xbox page (native KBM games). Tap Test Web Lock to check Pointer Lock.",
        "es" to "Envía teclado y ratón directo a Xbox (juegos KBM). Pulsa Probar bloqueo para verificar Pointer Lock.",
        "pt" to "Envia teclado e mouse direto ao Xbox (jogos KBM). Toque em Testar trava para checar Pointer Lock.",
        "fr" to "Envoie clavier et souris directement à Xbox (jeux KBM). Touchez Test Lock Web pour vérifier Pointer Lock.",
        "de" to "Leitet Tastatur & Maus direkt an Xbox (KBM-Spiele). Tippe Web-Lock Test für Pointer Lock.",
        "ja" to "Xboxページへ直接入力（ネイティブKBM対応ゲーム向け）。Webロック試験でPointer Lockを確認。",
        "ko" to "Xbox 페이지로 키보드/마우스 직접 전달(네이티브 게임용). 웹 잠금 시험으로 확인하세요.",
        "zh" to "将键盘鼠标直通 Xbox 页面（原生键鼠游戏）。点测试网页锁定检查 Pointer Lock。",
        "id" to "Kirim keyboard & mouse langsung ke Xbox (game KBM asli). Ketuk Uji Kunci Web untuk cek Pointer Lock.",
        "th" to "ส่งคีย์บอร์ดและเมาส์ตรงไปหน้า Xbox (เกมที่รองรับ) กดลองล็อกเว็บเพื่อตรวจ Pointer Lock",
        "ru" to "Передаёт клавиатуру и мышь напрямую в Xbox (KBM-игры). Нажмите Тест Web Lock для проверки."
    ))

    fun touchLookAreaHint(c: AppConfig) = pick(c, mapOf(
        "vi" to "Vuốt nhìn", "en" to "Drag look", "es" to "Mirar", "pt" to "Olhar",
        "fr" to "Viser", "de" to "Umsehen", "ja" to "視点操作", "ko" to "시점 이동",
        "zh" to "滑动视角", "id" to "Geser lihat", "th" to "ลากเล็ง", "ru" to "Обзор"
    ))

    fun allKeyboardTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Cả bàn phím", "en" to "All Keyboard", "es" to "Todo el teclado", "pt" to "Todo o teclado",
        "fr" to "Tout le clavier", "de" to "Ganze Tastatur", "ja" to "キーボード全体", "ko" to "전체 키보드",
        "zh" to "全部键鼠", "id" to "Semua Keyboard", "th" to "คีย์บอร์ดทั้งหมด", "ru" to "Вся клавиатура"
    ))

    fun tapBgMouseLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Chạm nền bấm chuột", "en" to "Tap screen = Mouse", "es" to "Tocar fondo = Ratón",
        "pt" to "Tocar tela = Mouse", "fr" to "Toucher écran = Souris", "de" to "Tippen = Mausklick",
        "ja" to "画面タップでクリック", "ko" to "화면 탭 = 마우스 클릭", "zh" to "轻触背景=鼠标点击",
        "id" to "Ketuk layar = Klik", "th" to "แตะจอ = คลิกเมาส์", "ru" to "Тап по фону = Клик"
    ))

    fun directTouchLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Chạm trực tiếp màn hình", "en" to "Direct Screen Touch", "es" to "Toque directo",
        "pt" to "Toque direto na tela", "fr" to "Tactile direct", "de" to "Direkte Berührung",
        "ja" to "直接画面タッチ", "ko" to "화면 직접 터치", "zh" to "直接触摸屏幕",
        "id" to "Sentuh langsung layar", "th" to "แตะหน้าจอโดยตรง", "ru" to "Прямое касание экрана"
    ))

    fun addKeyLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "+ Thêm phím", "en" to "+ Add Key", "es" to "+ Tecla", "pt" to "+ Tecla",
        "fr" to "+ Touche", "de" to "+ Taste", "ja" to "+ キー追加", "ko" to "+ 키 추가",
        "zh" to "+ 添加按键", "id" to "+ Tambah", "th" to "+ เพิ่มปุ่ม", "ru" to "+ Клавиша"
    ))

    fun doneEditLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "✓ Xong", "en" to "✓ Done", "es" to "✓ Listo", "pt" to "✓ Pronto",
        "fr" to "✓ Fini", "de" to "✓ Fertig", "ja" to "✓ 完了", "ko" to "✓ 완료",
        "zh" to "✓ 完成", "id" to "✓ Selesai", "th" to "✓ เสร็จ", "ru" to "✓ Готово"
    ))

    fun createComboBtnLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Tạo nút gộp", "en" to "Combo Button", "es" to "Botón combo", "pt" to "Botão combo",
        "fr" to "Bouton combo", "de" to "Kombi-Taste", "ja" to "同時押しボタン", "ko" to "조합 버튼",
        "zh" to "组合单键", "id" to "Tombol Kombo", "th" to "สร้างปุ่มรวม", "ru" to "Комбо-кнопка"
    ))

    fun createClusterBtnLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Tạo cụm phím", "en" to "Key Cluster", "es" to "Grupo teclas", "pt" to "Grupo teclas",
        "fr" to "Groupe touches", "de" to "Tastengruppe", "ja" to "キーグループ", "ko" to "키 묶음",
        "zh" to "按键组", "id" to "Grup Tombol", "th" to "สร้างกลุ่มปุ่ม", "ru" to "Группа клавиш"
    ))

    fun fullKeyboardTitle(c: AppConfig) = pick(c, mapOf(
        "vi" to "Chọn phím trên bàn phím PC", "en" to "Select PC Keys", "es" to "Elegir teclas PC",
        "pt" to "Escolher teclas PC", "fr" to "Choisir touches PC", "de" to "PC-Tasten wählen",
        "ja" to "PCキーを選択", "ko" to "PC 키 선택", "zh" to "选择 PC 按键",
        "id" to "Pilih Tombol PC", "th" to "เลือกปุ่ม PC", "ru" to "Выбор клавиш ПК"
    ))

    fun editButtonsPcLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "✥ Sửa & thêm phím", "en" to "✥ Edit & Add Keys", "es" to "✥ Editar y añadir",
        "pt" to "✥ Editar e adicionar", "fr" to "✥ Modifier touches", "de" to "✥ Tasten bearbeiten",
        "ja" to "✥ キー編集・追加", "ko" to "✥ 키 편집 및 추가", "zh" to "✥ 编辑与添加按键",
        "id" to "✥ Edit & Tambah", "th" to "✥ แก้ไขและเพิ่มปุ่ม", "ru" to "✥ Изменить клавиши"
    ))

    fun deleteKeyLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Xóa nút", "en" to "Delete", "es" to "Borrar", "pt" to "Excluir",
        "fr" to "Supprimer", "de" to "Löschen", "ja" to "削除", "ko" to "삭제",
        "zh" to "删除", "id" to "Hapus", "th" to "ลบปุ่ม", "ru" to "Удалить"
    ))

    fun hideKeyLabel(c: AppConfig) = pick(c, mapOf(
        "vi" to "Ẩn nút", "en" to "Hide", "es" to "Ocultar", "pt" to "Ocultar",
        "fr" to "Masquer", "de" to "Ausblenden", "ja" to "非表示", "ko" to "숨기기",
        "zh" to "隐藏", "id" to "Sembunyikan", "th" to "ซ่อนปุ่ม", "ru" to "Скрыть"
    ))

    fun analogWasdName(c: AppConfig) = pick(c, mapOf(
        "vi" to "Cần WASD (+Shift)", "en" to "WASD Stick (+Shift)", "es" to "Stick WASD (+Shift)",
        "pt" to "Stick WASD (+Shift)", "fr" to "Stick WASD (+Shift)", "de" to "WASD-Stick (+Shift)",
        "ja" to "WASDスティック(+Shift)", "ko" to "WASD 스틱 (+Shift)", "zh" to "WASD 摇杆(+Shift)",
        "id" to "Stik WASD (+Shift)", "th" to "คันโยก WASD (+Shift)", "ru" to "Стик WASD (+Shift)"
    ))

    fun doubleTapEditHint(c: AppConfig) = pick(c, mapOf(
        "vi" to "Nhấn đúp một phím để chỉnh riêng phím đó",
        "en" to "Double-tap any key to customize it",
        "es" to "Toca dos veces una tecla para ajustarla",
        "pt" to "Toque duas vezes em uma tecla para ajustar",
        "fr" to "Double-tapez une touche pour la régler",
        "de" to "Doppeltippe eine Taste zum Anpassen",
        "ja" to "キーをダブルタップして個別調整",
        "ko" to "키를 두 번 탭해 개별 설정",
        "zh" to "双击任意按键单独调整",
        "id" to "Ketuk dua kali tombol untuk atur",
        "th" to "แตะสองครั้งที่ปุ่มเพื่อปรับแต่ง",
        "ru" to "Дважды нажмите клавишу для настройки"
    ))
}
