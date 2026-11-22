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
        private const val AIM_FULL_VEL_RAD_S = 2.40f
        private const val AIM_GENTLE_ANTI_DEADZONE = 0.07f
        private const val AIM_MAX_STICK_OUT = 0.85f
        private const val AIM_MAX_SLEW_UP_PER_SEC = 4.5f
        private const val AIM_MAX_SLEW_DOWN_PER_SEC = 14.0f

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
    private var slewedAimOutX = 0f
    private var slewedAimOutY = 0f
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
        slewedAimOutX = 0f
        slewedAimOutY = 0f
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
        val clampedVx = rawVx.coerceIn(-4.5f, 4.5f)
        val clampedVy = rawVy.coerceIn(-4.5f, 4.5f)

        // Tự hiệu chuẩn sai số tĩnh (zero-bias) khi tay cầm yên để không bị trôi camera lên trời / xuống đất
        val corrVx: Float
        val corrVy: Float
        if (enableBiasTracking) {
            aimElapsedSec += safeDt
            if (abs(clampedVx - aimBiasX) < 0.14f && abs(clampedVy - aimBiasY) < 0.14f) {
                val biasTau = if (aimElapsedSec < 0.6f) 0.20f else 1.50f
                val biasAlpha = (safeDt / (biasTau + safeDt)).coerceIn(0f, 0.25f)
                aimBiasX = (aimBiasX + biasAlpha * (clampedVx - aimBiasX)).coerceIn(-0.25f, 0.25f)
                aimBiasY = (aimBiasY + biasAlpha * (clampedVy - aimBiasY)).coerceIn(-0.25f, 0.25f)
            }
            corrVx = clampedVx - aimBiasX
            corrVy = clampedVy - aimBiasY
        } else {
            corrVx = clampedVx
            corrVy = clampedVy
        }

        // Bộ lọc thích ứng tốc độ (kiểu One-Euro):
        // - Khi giữ yên / nhích nhỏ để ngắm bắn: lọc sâu (tau ~55ms) hãm rung tay li ti.
        // - Khi vẩy cổ tay nhanh: mở lọc (tau ~12ms) giữ phản hồi gọn.
        val speed = hypot(corrVx, corrVy)
        val fastRatio = ((speed - 0.30f) / 1.10f).coerceIn(0f, 1f)
        val tau = 0.055f * (1f - fastRatio) + 0.012f * fastRatio
        val blend = (safeDt / (tau + safeDt)).coerceIn(0.02f, 0.85f)
        if (!hasAimVelFilter) {
            filtAimVx = corrVx
            filtAimVy = corrVy
            hasAimVelFilter = true
        } else {
            filtAimVx += blend * (corrVx - filtAimVx)
            filtAimVy += blend * (corrVy - filtAimVy)
        }

        val targetX = shapeAimStickAxis(filtAimVx, sensitivityXPercent, deadzonePercent, invertX)
        val targetY = shapeAimStickAxis(filtAimVy, sensitivityYPercent, deadzonePercent, invertY)

        // Bộ hãm tốc độ biến thiên (slew-rate limiter):
        // - Khi tăng lực / đổi hướng: hãm mượt để cú va tay hay nhiễu đột biến không hất tung camera lên trời.
        // - Khi nhả về 0: trả nhanh để dừng tay là tâm đứng ngay.
        slewedAimOutX = applyAimSlewLimit(slewedAimOutX, targetX, safeDt)
        slewedAimOutY = applyAimSlewLimit(slewedAimOutY, targetY, safeDt)

        dispatchOutput(targetRightStick = true, outX = slewedAimOutX, outY = slewedAimOutY, force = false)
    }

    private fun applyAimSlewLimit(current: Float, target: Float, dt: Float): Float {
        if (target == 0f && abs(current) < 0.06f) return 0f
        val delta = target - current
        val movingTowardZero = abs(target) < abs(current) && (target == 0f || sign(target) == sign(current))
        val maxRate = if (movingTowardZero) AIM_MAX_SLEW_DOWN_PER_SEC else AIM_MAX_SLEW_UP_PER_SEC
        val maxStep = maxRate * dt
        return if (abs(delta) <= maxStep) {
            target
        } else {
            current + sign(delta) * maxStep
        }
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
                    slewedAimOutX = 0f
                    slewedAimOutY = 0f
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

                // Fallback cho máy không có Gyroscope vật lý: lọc sâu góc nghiêng + lọc tốc độ + sửa đúng dấu lên/xuống
                val alpha = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.82f else 0.55f
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
                // Ngẩng mặt lưng máy lên trời -> filtGz giảm âm -> pitchUp tăng dương -> -dPitch/dt âm (Cần Phải đi LÊN)
                val pitchUp = atan2(-filtGz, hypot(filtGx, filtGy).coerceAtLeast(0.01f))
                val nowNs = event.timestamp
                if (!hasPrevAimTilt) {
                    prevAimRollRad = rollRight
                    prevAimPitchRad = pitchUp
                    prevAimTimestampNs = nowNs
                    hasPrevAimTilt = true
                    return
                }
                val dt = ((nowNs - prevAimTimestampNs) * 1e-9f)
                val dRoll = normalizeAngleRad(rollRight - prevAimRollRad)
                val dPitch = normalizeAngleRad(pitchUp - prevAimPitchRad)
                prevAimRollRad = rollRight
                prevAimPitchRad = pitchUp
                prevAimTimestampNs = nowNs
                if (dt <= 0.004f) return
                if (dt > 0.15f) {
                    slewedAimOutX = 0f
                    slewedAimOutY = 0f
                    dispatchOutput(targetRightStick = true, outX = 0f, outY = 0f, force = true)
                    return
                }

                val velX = (dRoll / dt).coerceIn(-3.5f, 3.5f)
                val velY = (-dPitch / dt).coerceIn(-3.5f, 3.5f)
                processFilteredAimVelocity(velX, velY, dt, enableBiasTracking = false)
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

    /**
     * Biến tốc độ xoay (rad/s) thành tín hiệu Cần Phải (R) êm, có hãm rung và có giới hạn:
     * - Nhích nhỏ vẫn nhận đều để ngắm bắn tinh, nhưng được hãm bằng đường cong mũ 1.35 (không nhảy bậc giật).
     * - Bù vùng chết console êm (tối đa 7%, mượt theo smoothstep trong 30% đầu).
     * - Giới hạn biên độ tối đa (0.85) để camera không bị hất văng quá đà.
     */
    private fun shapeAimStickAxis(
        velRadPerSec: Float,
        sensPercent: Int,
        deadzonePct: Int,
        invert: Boolean
    ): Float {
        if (!velRadPerSec.isFinite()) return 0f
        val velDz = 0.035f + (deadzonePct.coerceIn(0, 20) / 100f) * 0.22f
        val absVel = abs(velRadPerSec)
        if (absVel <= velDz) return 0f

        val sensScale = sensPercent.coerceIn(10, 300) / 100f
        val norm = (((absVel - velDz) / AIM_FULL_VEL_RAD_S) * sensScale).coerceIn(0f, 1f)
        val curved = norm.pow(1.35f)
        val t = (norm / 0.30f).coerceIn(0f, 1f)
        val smoothRamp = t * t * (3f - 2f * t)
        val damped = (smoothRamp * AIM_GENTLE_ANTI_DEADZONE + (1f - AIM_GENTLE_ANTI_DEADZONE) * curved)
            .coerceIn(0f, AIM_MAX_STICK_OUT)
        val signed = damped * sign(velRadPerSec)
        return if (invert) -signed else signed
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
