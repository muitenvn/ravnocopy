package com.example.cloudplay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.Surface
import com.example.data.GyroControlMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

/**
 * Bộ điều khiển Con quay hồi chuyển & Cảm biến nghiêng cho Ravno:
 * - Mặc định TẮT hoàn toàn: khi tắt không đăng ký cảm biến và không gửi tín hiệu.
 * - Khi người dùng BẬT:
 *   1. AIM_RIGHT_STICK (Ngắm bắn — Cần Phải R):
 *      + Hoạt động theo tốc độ xoay cổ tay (angular velocity): xoay tay tới đâu tâm đi tới đó, dừng tay tâm dừng ngay.
 *      + Ưu tiên TYPE_GYROSCOPE (SENSOR_DELAY_FASTEST, độ trễ cực thấp) -> GAME_ROTATION_VECTOR -> ROTATION_VECTOR -> GRAVITY -> ACCELEROMETER.
 *      + Bù vùng chết game console (anti-deadzone boost) + đường cong phản hồi nhanh để ngắm micro nhạy, không bị ì hay chậm.
 *      + Hỗ trợ xoay ngang tự nhiên cả khi cầm thẳng đứng lẫn nghiêng 45° (pha yaw + roll theo góc cầm tay).
 *   2. RACING_STEER (Đua xe — Cần Trái L):
 *      + Chỉ nghiêng trái/phải điều khiển Cần Trái (LS trục X), khóa trục dọc = 0.
 *      + Ưu tiên Cảm biến trọng lực (TYPE_GRAVITY) -> Gia tốc kế (TYPE_ACCELEROMETER) -> Rotation Vector -> Gyroscope.
 *   3. GAME_NATIVE (Theo game hỗ trợ gốc):
 *      + Không giả lập cần tay cầm, nhường cảm biến gốc trình duyệt cho game hỗ trợ.
 */
class GyroscopeController(
    context: Context,
    private val onOutputChanged: (targetRightStick: Boolean, outX: Float, outY: Float) -> Unit
) : SensorEventListener {

    companion object {
        private const val MIN_DISPATCH_INTERVAL_NS = 16_000_000L // ~60 Hz cap to match cloud stream and prevent WebView decode lag
        private const val AIM_KEEPALIVE_INTERVAL_NS = 70_000_000L // refresh active aim every 70ms for JS stale watchdog

        // Thông số chuẩn công nghiệp (Steam Input Gyro-to-Joystick Camera + Jibb Smart GyroWiki / Fortnite):
        private const val AIM_MAX_SPEED_RAD_S = 3.1415927f // 180°/s (Steam Input default full-stick speed)
        private const val AIM_TIGHTENING_RAD_S = 0.08726646f // 5°/s (Jibb Smart tightening threshold)
        private const val AIM_SMOOTH_LOWER_RAD_S = 0.08726646f // 5°/s (1/2 ngưỡng trên theo JoyShockMapper)
        private const val AIM_SMOOTH_UPPER_RAD_S = 0.17453292f // 10°/s (GYRO_SMOOTH_THRESHOLD)
        private const val AIM_SMOOTH_TAU_SEC = 0.025f // cửa sổ lọc nhẹ 25ms chỉ áp dụng dưới 10°/s
        private const val AIM_BIAS_STILL_RAD_S = 0.06981317f // 4°/s (chỉ trừ trôi tĩnh khi thật sự cầm yên)
        private const val AIM_CONSOLE_ANTI_DEADZONE = 0.12f // 12% (vùng chết mặc định cần R của Fortnite / console Xbox)

        // Thông số chuẩn Steam Input Deflection (nghiêng theo góc tương đối cho máy chỉ có cảm biến Trọng lực / Gia tốc kế):
        private const val AIM_GRAV_MAX_ANGLE_RAD = 0.5235988f // 30° nghiêng cổ tay = 100% cần R
        private const val AIM_GRAV_BASE_DEADZONE_RAD = 0.034906585f // 2° vùng yên quanh tâm 0°

        @Volatile
        private var recenterRequestTick: Long = 0L

        fun requestRecenterNow() {
            recenterRequestTick = System.currentTimeMillis()
        }

        fun hasAvailableGyroSensor(context: Context): Boolean {
            if (findPhysicalControllerSensorManager() != null) {
                return true
            }
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                ?: return false
            return sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null ||
                sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        }

        private fun findPhysicalControllerSensorManager(): SensorManager? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            return try {
                for (id in InputDevice.getDeviceIds()) {
                    val dev = InputDevice.getDevice(id) ?: continue
                    val isGamepad = (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    if (isGamepad && !dev.isVirtual) {
                        val sm = dev.sensorManager
                        // Chỉ ưu tiên tay cầm vật lý nếu có cảm biến chuyển động thực (tránh tay cầm giả báo gia tốc kế rỗng chặn cảm biến điện thoại)
                        val hasTrueMotion = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null ||
                            sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
                            sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
                            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
                        if (hasTrueMotion) return sm
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private val appContext = context.applicationContext ?: context
    private var activeSensorManager: SensorManager? = null
    private var isUsingControllerSensor: Boolean = false
    private var isListening: Boolean = false
    private var isPaused: Boolean = false
    private var registeredForMode: GyroControlMode? = null

    var enabled: Boolean = false
        private set
    var mode: GyroControlMode = GyroControlMode.AIM_RIGHT_STICK
    var sensitivityXPercent: Int = 100
    var sensitivityYPercent: Int = 100
    var deadzonePercent: Int = 3
    var invertX: Boolean = false
    var invertY: Boolean = false

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val refMatrix = FloatArray(9)
    private val prevRotMatrix = FloatArray(9)
    private val relMatrix = FloatArray(9)

    private var hasFilteredGravity = false
    private var filtGx = 0f
    private var filtGy = 0f
    private var filtGz = 0f
    private var lastDisplayRotation = -1

    // Trạng thái cho RACING_STEER (góc tuyệt đối)
    private var hasReferencePose = false
    private var refRollRad = 0f
    private var lastHandledRecenterTick = 0L
    private var integratedX = 0f
    private var lastGyroTimestampNs = 0L

    // Trạng thái cho AIM_RIGHT_STICK (tốc độ xoay tức thì + tự cân bằng tư thế & hãm rung)
    private var primaryAimSensorType: Int = 0
    private var hasAimVelFilter = false
    private var filtAimVx = 0f
    private var filtAimVy = 0f
    private var aimBiasX = 0f
    private var aimBiasY = 0f
    private var aimElapsedSec = 0f
    private var lastAimGyroTsNs = 0L
    private var hasPrevAimRot = false
    private var prevAimTimestampNs = 0L
    private var hasPrevAimTilt = false
    private var prevAimRollRad = 0f
    private var prevAimPitchRad = 0f

    private var lastDispatchedX = 0f
    private var lastDispatchedY = 0f
    private var lastDispatchedRightStick = false
    private var lastDispatchTimeNs = 0L

    private fun shouldEmulateStick(): Boolean =
        enabled && !isPaused && (mode == GyroControlMode.AIM_RIGHT_STICK || mode == GyroControlMode.RACING_STEER)

    fun updateConfiguration(
        enabled: Boolean,
        mode: GyroControlMode,
        sensitivityXPercent: Int,
        sensitivityYPercent: Int,
        deadzonePercent: Int,
        invertX: Boolean,
        invertY: Boolean
    ) {
        val prevMode = this.mode
        val prevEnabled = this.enabled
        this.mode = mode
        this.enabled = enabled
        this.sensitivityXPercent = sensitivityXPercent.coerceIn(10, 300)
        this.sensitivityYPercent = sensitivityYPercent.coerceIn(10, 300)
        this.deadzonePercent = deadzonePercent.coerceIn(0, 20)
        this.invertX = invertX
        this.invertY = invertY

        val shouldListen = shouldEmulateStick()
        if (shouldListen && isListening && registeredForMode != mode) {
            // Đổi chế độ (VD: Đua xe <-> Ngắm bắn): đăng ký lại cảm biến tối ưu cho chế độ mới
            stopListening()
            startListening()
            return
        }

        if (prevMode != mode && shouldListen) {
            recenter()
        }

        if (shouldListen && !isListening) {
            startListening()
        } else if (!shouldListen && (isListening || prevEnabled != enabled || prevMode != mode)) {
            stopListening()
        }
    }

    fun setPaused(paused: Boolean) {
        if (this.isPaused == paused) return
        this.isPaused = paused
        if (paused) {
            stopListening()
        } else if (shouldEmulateStick()) {
            startListening()
        }
    }

    /**
     * Đẩy lại trạng thái hiện tại sang WebView sau khi trang tải xong.
     */
    fun syncCurrentOutput() {
        if (!shouldEmulateStick()) return
        val isRight = mode == GyroControlMode.AIM_RIGHT_STICK
        dispatchOutput(isRight, lastDispatchedX, if (isRight) lastDispatchedY else 0f, force = true)
    }

    fun recenter() {
        resetInternalFilters()
        if (shouldEmulateStick()) {
            val isRight = mode == GyroControlMode.AIM_RIGHT_STICK
            dispatchOutput(isRight, 0f, 0f, force = true)
        }
    }

    private fun resetInternalFilters() {
        hasFilteredGravity = false
        hasReferencePose = false
        integratedX = 0f
        lastGyroTimestampNs = 0L
        hasAimVelFilter = false
        filtAimVx = 0f
        filtAimVy = 0f
        aimBiasX = 0f
        aimBiasY = 0f
        aimElapsedSec = 0f
        lastAimGyroTsNs = 0L
        hasPrevAimRot = false
        prevAimTimestampNs = 0L
        hasPrevAimTilt = false
        prevAimRollRad = 0f
        prevAimPitchRad = 0f
    }

    private fun registerWithBestDelay(sm: SensorManager, sensor: Sensor, preferFastest: Boolean): Boolean {
        if (preferFastest) {
            try {
                if (sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)) {
                    return true
                }
            } catch (_: SecurityException) {
                // Android 12+ không có HIGH_SAMPLING_RATE_SENSORS -> fallback SENSOR_DELAY_GAME
            } catch (_: Exception) {
            }
        }
        return try {
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } catch (_: Exception) {
            false
        }
    }

    private fun tryRegisterBestSensor(sm: SensorManager, targetMode: GyroControlMode): Boolean {
        primaryAimSensorType = 0
        val candidates = if (targetMode == GyroControlMode.AIM_RIGHT_STICK) {
            // Ngắm bắn Cần Phải: ưu tiên Gyroscope vật lý độ trễ siêu thấp -> Rotation Vector -> Gravity/Accel dự phòng
            listOfNotNull(
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                sm.getDefaultSensor(Sensor.TYPE_GRAVITY),
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            )
        } else {
            // Đua xe Cần Trái: ưu tiên Trọng lực -> Gia tốc kế (hỗ trợ hầu hết điện thoại Android, không trôi tâm)
            listOfNotNull(
                sm.getDefaultSensor(Sensor.TYPE_GRAVITY),
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            )
        }
        for (sensor in candidates) {
            val preferFastest = targetMode == GyroControlMode.AIM_RIGHT_STICK &&
                (sensor.type == Sensor.TYPE_GYROSCOPE ||
                    sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    sensor.type == Sensor.TYPE_ROTATION_VECTOR)
            if (registerWithBestDelay(sm, sensor, preferFastest)) {
                if (targetMode == GyroControlMode.AIM_RIGHT_STICK) {
                    primaryAimSensorType = sensor.type
                    // Đăng ký thêm cảm biến trọng lực/gia tốc kế phụ trợ (50Hz) để chiếu trục xoay trái/phải
                    // đúng theo chiều trọng lực thực của tư thế cầm máy (ngồi, nghiêng 45°, đặt bàn, hay nằm ngửa)
                    if (sensor.type == Sensor.TYPE_GYROSCOPE) {
                        val orientHelper = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
                            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                        if (orientHelper != null) {
                            registerWithBestDelay(sm, orientHelper, preferFastest = false)
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    private fun startListening() {
        if (isListening || !shouldEmulateStick()) return
        val controllerSm = findPhysicalControllerSensorManager()
        val systemSm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        resetInternalFilters()
        lastDisplayRotation = -1
        lastDispatchTimeNs = 0L

        val targetMode = mode
        var registered = false
        if (controllerSm != null) {
            registered = tryRegisterBestSensor(controllerSm, targetMode)
            if (registered) {
                activeSensorManager = controllerSm
                isUsingControllerSensor = true
            }
        }
        if (!registered && systemSm != null) {
            registered = tryRegisterBestSensor(systemSm, targetMode)
            if (registered) {
                activeSensorManager = systemSm
                isUsingControllerSensor = false
            }
        }
        isListening = registered
        registeredForMode = if (registered) targetMode else null
        if (registered) {
            dispatchOutput(targetMode == GyroControlMode.AIM_RIGHT_STICK, 0f, 0f, force = true)
        }
    }

    fun stopListening() {
        val wasListening = isListening
        val wasRight = lastDispatchedRightStick
        if (isListening) {
            try {
                activeSensorManager?.unregisterListener(this)
            } catch (_: Exception) {
            }
            isListening = false
        }
        activeSensorManager = null
        isUsingControllerSensor = false
        registeredForMode = null
        primaryAimSensorType = 0
        resetInternalFilters()
        lastDisplayRotation = -1
        lastDispatchTimeNs = 0L
        if (wasListening || lastDispatchedX != 0f || lastDispatchedY != 0f) {
            dispatchOutput(wasRight, 0f, 0f, force = true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        if (!shouldEmulateStick() || event == null) return

        val reqTick = recenterRequestTick
        if (reqTick > lastHandledRecenterTick) {
            lastHandledRecenterTick = reqTick
            recenter()
        }

        val curRot = getDisplayRotation()
        if (lastDisplayRotation != -1 && lastDisplayRotation != curRot) {
            resetInternalFilters()
        }
        lastDisplayRotation = curRot

        if (mode == GyroControlMode.AIM_RIGHT_STICK) {
            handleAimRightStickEvent(event)
        } else {
            handleRacingSteerEvent(event)
        }
    }

    private fun processFilteredAimVelocity(
        rawVx: Float,
        rawVy: Float,
        dt: Float,
        enableBiasTracking: Boolean
    ) {
        val safeDt = dt.coerceIn(0.001f, 0.05f)
        val clampedVx = rawVx.coerceIn(-6.0f, 6.0f)
        val clampedVy = rawVy.coerceIn(-6.0f, 6.0f)

        // 1. Tự trừ sai số tĩnh (zero-bias) chỉ khi máy thật sự đứng yên (< 4°/s), không ăn mất thao tác ngắm chậm
        val corrVx: Float
        val corrVy: Float
        if (enableBiasTracking) {
            aimElapsedSec += safeDt
            val deltaMag = hypot(clampedVx - aimBiasX, clampedVy - aimBiasY)
            if (deltaMag < AIM_BIAS_STILL_RAD_S) {
                val biasTau = if (aimElapsedSec < 0.5f) 0.18f else 1.80f
                val biasAlpha = (safeDt / (biasTau + safeDt)).coerceIn(0f, 0.25f)
                aimBiasX = (aimBiasX + biasAlpha * (clampedVx - aimBiasX)).coerceIn(-0.20f, 0.20f)
                aimBiasY = (aimBiasY + biasAlpha * (clampedVy - aimBiasY)).coerceIn(-0.20f, 0.20f)
            }
            corrVx = clampedVx - aimBiasX
            corrVy = clampedVy - aimBiasY
        } else {
            corrVx = clampedVx
            corrVy = clampedVy
        }

        // 2. Jibb Smart Tiered Smoothing (JoyShockMapper):
        // - Dưới 5°/s: lọc nhẹ 25ms để êm tay cầm.
        // - Từ 5°/s -> 10°/s: giảm dần lọc về 0.
        // - Trên 10°/s: giữ nguyên tín hiệu thô 1:1 (độ trễ bằng 0, không bị ì/cứng).
        val rawSpeed = hypot(corrVx, corrVy)
        if (!hasAimVelFilter || rawSpeed >= AIM_SMOOTH_UPPER_RAD_S) {
            filtAimVx = corrVx
            filtAimVy = corrVy
            hasAimVelFilter = true
        } else {
            val smoothWeight = if (rawSpeed <= AIM_SMOOTH_LOWER_RAD_S) {
                1f
            } else {
                1f - ((rawSpeed - AIM_SMOOTH_LOWER_RAD_S) / (AIM_SMOOTH_UPPER_RAD_S - AIM_SMOOTH_LOWER_RAD_S))
            }
            val tau = AIM_SMOOTH_TAU_SEC * smoothWeight
            val blend = if (tau <= 0.0005f) 1f else (safeDt / (tau + safeDt)).coerceIn(0.05f, 1f)
            filtAimVx += blend * (corrVx - filtAimVx)
            filtAimVy += blend * (corrVy - filtAimVy)
        }

        // 3. Jibb Smart 2D Vector Tightening (5°/s) + Steam Input Linear 180°/s + 2D Radial Anti-Deadzone 12%
        val (outX, outY) = mapAimVelocityToStick(filtAimVx, filtAimVy)
        dispatchOutput(targetRightStick = true, outX = outX, outY = outY, force = false)
    }

    /**
     * Chuyển vận tốc xoay 2D (rad/s) sang Cần Phải (R) theo chuẩn Jibb Smart + Steam Input:
     * - Tightening dưới 5°/s (nén rung tay mượt mà không cắt cứng).
     * - Tuyến tính 1:1 tới 180°/s (Steam Input default).
     * - Bù vùng chết tròn 2D (Radial Anti-Deadzone 12%) khớp vùng chết mặc định của Fortnite/Xbox.
     */
    private fun mapAimVelocityToStick(vx: Float, vy: Float): Pair<Float, Float> {
        if (!vx.isFinite() || !vy.isFinite()) return 0f to 0f
        val rawMag = hypot(vx, vy)
        if (rawMag <= 1e-5f) return 0f to 0f

        // Tightening theo độ lớn vector 2D (Jibb Smart GyroWiki)
        val tightenedScale = if (rawMag < AIM_TIGHTENING_RAD_S) {
            (rawMag / AIM_TIGHTENING_RAD_S)
        } else {
            1f
        }
        val tvx = vx * tightenedScale
        val tvy = vy * tightenedScale

        val sensX = sensitivityXPercent.coerceIn(10, 300) / 100f
        val sensY = sensitivityYPercent.coerceIn(10, 300) / 100f
        val velDz = 0.015f + (deadzonePercent.coerceIn(0, 20) / 100f) * 0.14f
        val mag = hypot(tvx, tvy)
        if (mag <= velDz) return 0f to 0f

        val effMag = mag - velDz
        val dirX = tvx / mag
        val dirY = tvy / mag
        val normX = (dirX * effMag / AIM_MAX_SPEED_RAD_S) * sensX
        val normY = (dirY * effMag / AIM_MAX_SPEED_RAD_S) * sensY
        val normMag = hypot(normX, normY)
        if (normMag <= 1e-5f) return 0f to 0f

        val clampedNorm = normMag.coerceAtMost(1f)
        // Smoothstep cực ngắn (4% đầu) vào ngưỡng 12% Minimum Joystick Output để qua vùng chết game mà không giật
        val ramp = (clampedNorm / 0.04f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val outMag = (ramp * AIM_CONSOLE_ANTI_DEADZONE + (1f - AIM_CONSOLE_ANTI_DEADZONE) * clampedNorm)
            .coerceIn(0f, 1f)

        val scale = outMag / normMag
        val outX = (normX * scale).coerceIn(-1f, 1f)
        val outY = (normY * scale).coerceIn(-1f, 1f)
        return (if (invertX) -outX else outX) to (if (invertY) -outY else outY)
    }

    /**
     * Chuyển góc nghiêng tương đối (rad) quanh tư thế chuẩn 0° sang Cần Phải (R) theo chuẩn Steam Input Deflection
     * dành riêng cho máy chỉ dùng cảm biến Trọng lực / Gia tốc kế (mượt như cần thật, không bị khóa cứng hay giật nhiễu).
     */
    private fun mapTiltDeflectionToStick(dRollRad: Float, dPitchUpRad: Float): Pair<Float, Float> {
        if (!dRollRad.isFinite() || !dPitchUpRad.isFinite()) return 0f to 0f
        // Nghiêng phải (dRoll > 0) -> R phải (+X); ngẩng lên (dPitchUp > 0) -> R lên (-Y)
        val rawX = dRollRad
        val rawY = -dPitchUpRad
        val angleMag = hypot(rawX, rawY)
        val dzRad = AIM_GRAV_BASE_DEADZONE_RAD + (deadzonePercent.coerceIn(0, 20) / 100f) * 0.16f
        if (angleMag <= dzRad) return 0f to 0f

        val effRange = (AIM_GRAV_MAX_ANGLE_RAD - dzRad).coerceAtLeast(0.15f)
        val effMag = angleMag - dzRad
        val sensX = sensitivityXPercent.coerceIn(10, 300) / 100f
        val sensY = sensitivityYPercent.coerceIn(10, 300) / 100f
        val normX = ((rawX / angleMag) * effMag / effRange) * sensX
        val normY = ((rawY / angleMag) * effMag / effRange) * sensY
        val normMag = hypot(normX, normY)
        if (normMag <= 1e-5f) return 0f to 0f

        val clampedNorm = normMag.coerceAtMost(1f)
        val curved = clampedNorm.pow(1.15f)
        val ramp = (clampedNorm / 0.10f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val outMag = (ramp * AIM_CONSOLE_ANTI_DEADZONE + (1f - AIM_CONSOLE_ANTI_DEADZONE) * curved)
            .coerceIn(0f, 1f)

        val scale = outMag / normMag
        val outX = (normX * scale).coerceIn(-1f, 1f)
        val outY = (normY * scale).coerceIn(-1f, 1f)
        return (if (invertX) -outX else outX) to (if (invertY) -outY else outY)
    }

    private fun handleAimRightStickEvent(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val nowNs = event.timestamp
                val dt = if (lastAimGyroTsNs == 0L) {
                    0.01f
                } else {
                    ((nowNs - lastAimGyroTsNs) * 1e-9f).coerceIn(0.001f, 0.05f)
                }
                lastAimGyroTsNs = nowNs

                val (sx, sy, sz) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                // Chiếu tốc độ xoay ngang lên trục thẳng đứng thực theo trọng lực (Player-Space World Yaw):
                // đúng hướng trái/phải ở mọi tư thế (ngồi thẳng, nghiêng 45°, đặt bàn, hay nằm ngửa) và không bị roll làm lệch tâm.
                val rawVx = if (hasFilteredGravity) {
                    val yzLen = hypot(filtGy, filtGz)
                    if (yzLen > 1.5f) {
                        val uy = filtGy / yzLen
                        val uz = filtGz / yzLen
                        -(sy * uy + sz * uz)
                    } else {
                        -sy
                    }
                } else {
                    -(0.866f * sy + 0.50f * sz)
                }
                val rawVy = -sx // Ngẩng máy lên (sx > 0) -> Cần Phải đi lên (outY < 0)

                processFilteredAimVelocity(rawVx, rawVy, dt, enableBiasTracking = true)
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                remapForScreenRotation(rotationMatrix, remappedMatrix)

                val nowNs = event.timestamp
                if (!hasPrevAimRot) {
                    System.arraycopy(remappedMatrix, 0, prevRotMatrix, 0, 9)
                    prevAimTimestampNs = nowNs
                    hasPrevAimRot = true
                    return
                }
                val dt = ((nowNs - prevAimTimestampNs) * 1e-9f)
                if (dt > 0.001f && dt <= 0.12f) {
                    multiplyTransposeLeft(prevRotMatrix, remappedMatrix, relMatrix)
                    val sx = 0.5f * (relMatrix[7] - relMatrix[5]) / dt
                    val sy = 0.5f * (relMatrix[2] - relMatrix[6]) / dt
                    val sz = 0.5f * (relMatrix[3] - relMatrix[1]) / dt
                    // Hàng thứ 3 của remappedMatrix (index 6, 7, 8) là vector hướng LÊN của thế giới trong hệ tọa độ màn hình
                    val yzLen = hypot(remappedMatrix[7], remappedMatrix[8])
                    val rawVx = if (yzLen > 0.25f) {
                        val uy = remappedMatrix[7] / yzLen
                        val uz = remappedMatrix[8] / yzLen
                        -(sy * uy + sz * uz)
                    } else {
                        -sy
                    }
                    val rawVy = -sx
                    processFilteredAimVelocity(rawVx, rawVy, dt, enableBiasTracking = false)
                } else if (dt > 0.12f) {
                    // Nghẽn cảm biến: xả mượt về 0 để không kẹt camera
                    dispatchOutput(targetRightStick = true, outX = 0f, outY = 0f, force = true)
                }
                System.arraycopy(remappedMatrix, 0, prevRotMatrix, 0, 9)
                prevAimTimestampNs = nowNs
            }

            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ACCELEROMETER -> {
                val (rawSx, rawSy, rawSz) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                // Nếu đang dùng GYROSCOPE làm cảm biến chính thì trọng lực/gia tốc kế CHỈ cập nhật vector tư thế cầm máy
                if (primaryAimSensorType == Sensor.TYPE_GYROSCOPE) {
                    val gAlpha = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.88f else 0.70f
                    if (!hasFilteredGravity) {
                        filtGx = rawSx
                        filtGy = rawSy
                        filtGz = rawSz
                        hasFilteredGravity = true
                    } else {
                        filtGx = gAlpha * filtGx + (1f - gAlpha) * rawSx
                        filtGy = gAlpha * filtGy + (1f - gAlpha) * rawSy
                        filtGz = gAlpha * filtGz + (1f - gAlpha) * rawSz
                    }
                    return
                }

                // Chế độ Steam Input Deflection (nghiêng góc tương đối so với tư thế chuẩn 0°) cho máy dùng Trọng lực / Gia tốc kế:
                // phản hồi tức thì, nhẹ tay, không khóa cứng và không nhân nhiễu vi phân dAngle/dt.
                val alpha = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.55f else 0.20f
                if (!hasFilteredGravity) {
                    filtGx = rawSx
                    filtGy = rawSy
                    filtGz = rawSz
                    hasFilteredGravity = true
                } else {
                    filtGx = alpha * filtGx + (1f - alpha) * rawSx
                    filtGy = alpha * filtGy + (1f - alpha) * rawSy
                    filtGz = alpha * filtGz + (1f - alpha) * rawSz
                }

                val rollRight = atan2(-filtGx, hypot(filtGy, filtGz).coerceAtLeast(0.01f))
                val pitchUp = atan2(-filtGz, hypot(filtGx, filtGy).coerceAtLeast(0.01f))
                if (!hasPrevAimTilt) {
                    // Lưu tư thế cầm hiện tại làm tâm 0° (có thể bấm "Đặt lại tâm 0°" bất kỳ lúc nào)
                    prevAimRollRad = rollRight
                    prevAimPitchRad = pitchUp
                    hasPrevAimTilt = true
                    dispatchOutput(targetRightStick = true, outX = 0f, outY = 0f, force = true)
                    return
                }
                val dRoll = normalizeAngleRad(rollRight - prevAimRollRad)
                val dPitchUp = normalizeAngleRad(pitchUp - prevAimPitchRad)
                val (outX, outY) = mapTiltDeflectionToStick(dRoll, dPitchUp)
                dispatchOutput(targetRightStick = true, outX = outX, outY = outY, force = false)
            }
        }
    }

    private fun handleRacingSteerEvent(event: SensorEvent) {
        // Chế độ RACING_STEER: chỉ nghiêng trái/phải điều khiển Cần Trái (L - trục X), khóa trục dọc Y = 0
        val fullScaleRad = 0.56f
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ACCELEROMETER -> {
                val (rawSx, rawSy, rawSz) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                val alpha = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.78f else 0.35f
                if (!hasFilteredGravity) {
                    filtGx = rawSx
                    filtGy = rawSy
                    filtGz = rawSz
                    hasFilteredGravity = true
                } else {
                    filtGx = alpha * filtGx + (1f - alpha) * rawSx
                    filtGy = alpha * filtGy + (1f - alpha) * rawSy
                    filtGz = alpha * filtGz + (1f - alpha) * rawSz
                }

                val rollRight = atan2(-filtGx, hypot(filtGy, filtGz).coerceAtLeast(0.01f))
                if (!hasReferencePose) {
                    refRollRad = rollRight
                    hasReferencePose = true
                }
                val dRoll = normalizeAngleRad(rollRight - refRollRad)
                val outX = processAxis(dRoll / fullScaleRad, sensitivityXPercent, deadzonePercent, invertX)
                dispatchOutput(false, outX, 0f, force = false)
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                remapForScreenRotation(rotationMatrix, remappedMatrix)

                if (!hasReferencePose) {
                    System.arraycopy(remappedMatrix, 0, refMatrix, 0, 9)
                    hasReferencePose = true
                }

                multiplyTransposeLeft(refMatrix, remappedMatrix, relMatrix)
                val dRoll = atan2(relMatrix[1], relMatrix[0])

                val processedX = processAxis(
                    raw = dRoll / fullScaleRad,
                    sensPercent = sensitivityXPercent,
                    deadzonePct = deadzonePercent,
                    invert = invertX
                )
                dispatchOutput(false, processedX, 0f, force = false)
            }

            Sensor.TYPE_GYROSCOPE -> {
                val nowNs = event.timestamp
                if (lastGyroTimestampNs != 0L) {
                    val dt = ((nowNs - lastGyroTimestampNs) * 1e-9f).coerceIn(0f, 0.1f)
                    val (_, _, sz) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                    val dRoll = -sz * dt
                    integratedX = (integratedX + (dRoll / fullScaleRad)).coerceIn(-1.5f, 1.5f)
                    val outX = processAxis(integratedX, sensitivityXPercent, deadzonePercent, invertX)
                    dispatchOutput(false, outX, 0f, force = false)
                }
                lastGyroTimestampNs = nowNs
            }
        }
    }

    private fun getDisplayRotation(): Int {
        if (isUsingControllerSensor) return Surface.ROTATION_0
        return try {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_90
        } catch (_: Exception) {
            Surface.ROTATION_90
        }
    }

    private fun remapForScreenRotation(inR: FloatArray, outR: FloatArray) {
        if (isUsingControllerSensor) {
            System.arraycopy(inR, 0, outR, 0, 9)
            return
        }
        when (getDisplayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                inR,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                outR
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                inR,
                SensorManager.AXIS_MINUS_Y,
                SensorManager.AXIS_X,
                outR
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                inR,
                SensorManager.AXIS_MINUS_X,
                SensorManager.AXIS_MINUS_Y,
                outR
            )
            else -> System.arraycopy(inR, 0, outR, 0, 9)
        }
    }

    private fun remapVectorToScreenAxes(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        return when (getDisplayRotation()) {
            Surface.ROTATION_90 -> Triple(-y, x, z)
            Surface.ROTATION_270 -> Triple(y, -x, z)
            Surface.ROTATION_180 -> Triple(-x, -y, z)
            else -> Triple(x, y, z)
        }
    }

    private fun multiplyTransposeLeft(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (r in 0..2) {
            for (c in 0..2) {
                out[r * 3 + c] =
                    a[0 * 3 + r] * b[0 * 3 + c] +
                    a[1 * 3 + r] * b[1 * 3 + c] +
                    a[2 * 3 + r] * b[2 * 3 + c]
            }
        }
    }

    private fun normalizeAngleRad(angle: Float): Float {
        var a = angle
        val pi = Math.PI.toFloat()
        while (a > pi) a -= 2f * pi
        while (a < -pi) a += 2f * pi
        return a
    }

    private fun processAxis(
        raw: Float,
        sensPercent: Int,
        deadzonePct: Int,
        invert: Boolean
    ): Float {
        if (!raw.isFinite()) return 0f
        val dz = (deadzonePct.coerceIn(0, 20) / 100f)
        val absRaw = abs(raw)
        if (absRaw <= dz) return 0f
        val rescaled = ((absRaw - dz) / (1f - dz).coerceAtLeast(0.1f)) * sign(raw)
        val sensScale = (sensPercent.coerceIn(10, 300) / 100f)
        val scaled = (rescaled * sensScale).coerceIn(-1f, 1f)
        return if (invert) -scaled else scaled
    }

    private fun dispatchOutput(
        targetRightStick: Boolean,
        outX: Float,
        outY: Float,
        force: Boolean
    ) {
        val safeX = if (outX.isFinite()) outX.coerceIn(-1f, 1f) else 0f
        val safeY = if (outY.isFinite()) outY.coerceIn(-1f, 1f) else 0f
        val dx = abs(safeX - lastDispatchedX)
        val dy = abs(safeY - lastDispatchedY)
        val returningToZero = (safeX == 0f && lastDispatchedX != 0f) || (safeY == 0f && lastDispatchedY != 0f)
        val nowNs = System.nanoTime()
        val elapsedNs = nowNs - lastDispatchTimeNs
        val needsAimKeepalive = targetRightStick && (safeX != 0f || safeY != 0f) && elapsedNs >= AIM_KEEPALIVE_INTERVAL_NS
        if (!force && !returningToZero && !needsAimKeepalive && targetRightStick == lastDispatchedRightStick && elapsedNs < MIN_DISPATCH_INTERVAL_NS) {
            return
        }
        if (force || targetRightStick != lastDispatchedRightStick || dx >= 0.005f || dy >= 0.005f || returningToZero || needsAimKeepalive) {
            lastDispatchedRightStick = targetRightStick
            lastDispatchedX = safeX
            lastDispatchedY = safeY
            lastDispatchTimeNs = nowNs
            onOutputChanged(targetRightStick, safeX, safeY)
        }
    }
}
