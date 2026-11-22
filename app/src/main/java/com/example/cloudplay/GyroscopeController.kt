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
        private const val MIN_DISPATCH_INTERVAL_NS = 8_000_000L // ~120 Hz cap to keep WebView responsive
        private const val AIM_FULL_VEL_RAD_S = 1.60f
        private const val AIM_ANTI_DEADZONE_BOOST = 0.14f

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

    // Trạng thái cho AIM_RIGHT_STICK (tốc độ xoay tức thì)
    private var hasAimVelFilter = false
    private var filtAimVx = 0f
    private var filtAimVy = 0f
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
        val preferFastest = targetMode == GyroControlMode.AIM_RIGHT_STICK
        for (sensor in candidates) {
            if (registerWithBestDelay(sm, sensor, preferFastest)) {
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

    private fun handleAimRightStickEvent(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val (sx, sy, sz) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                // Pha nhẹ roll (-0.55*sz) vào yaw (-sy) giúp quay trái/phải tự nhiên cả khi cầm đứng lẫn cầm nghiêng 45°
                val rawVx = -sy - 0.55f * sz
                val rawVy = -sx // Ngẩng máy lên (sx > 0) -> Cần Phải đi lên (outY < 0)

                // Lọc nhẹ 0.20 giữ độ trễ < 5ms nhưng loại nhiễu rung tay li ti
                val alpha = 0.20f
                if (!hasAimVelFilter) {
                    filtAimVx = rawVx
                    filtAimVy = rawVy
                    hasAimVelFilter = true
                } else {
                    filtAimVx = alpha * filtAimVx + (1f - alpha) * rawVx
                    filtAimVy = alpha * filtAimVy + (1f - alpha) * rawVy
                }

                val outX = shapeAimStickAxis(filtAimVx, sensitivityXPercent, deadzonePercent, invertX)
                val outY = shapeAimStickAxis(filtAimVy, sensitivityYPercent, deadzonePercent, invertY)
                dispatchOutput(targetRightStick = true, outX = outX, outY = outY, force = false)
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
                    val rawVx = (-sy - 0.55f * sz).coerceIn(-8f, 8f)
                    val rawVy = (-sx).coerceIn(-8f, 8f)

                    val alpha = 0.20f
                    if (!hasAimVelFilter) {
                        filtAimVx = rawVx
                        filtAimVy = rawVy
                        hasAimVelFilter = true
                    } else {
                        filtAimVx = alpha * filtAimVx + (1f - alpha) * rawVx
                        filtAimVy = alpha * filtAimVy + (1f - alpha) * rawVy
                    }
                    val outX = shapeAimStickAxis(filtAimVx, sensitivityXPercent, deadzonePercent, invertX)
                    val outY = shapeAimStickAxis(filtAimVy, sensitivityYPercent, deadzonePercent, invertY)
                    dispatchOutput(targetRightStick = true, outX = outX, outY = outY, force = false)
                }
                System.arraycopy(remappedMatrix, 0, prevRotMatrix, 0, 9)
                prevAimTimestampNs = nowNs
            }

            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ACCELEROMETER -> {
                val (rawSx, rawSy, rawSz) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                val alpha = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 0.55f else 0.25f
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
                val pitchDown = atan2(-filtGz, hypot(filtGx, filtGy).coerceAtLeast(0.01f))
                val nowNs = event.timestamp
                if (!hasPrevAimTilt) {
                    prevAimRollRad = rollRight
                    prevAimPitchRad = pitchDown
                    prevAimTimestampNs = nowNs
                    hasPrevAimTilt = true
                    return
                }
                val dt = ((nowNs - prevAimTimestampNs) * 1e-9f)
                val dRoll = normalizeAngleRad(rollRight - prevAimRollRad)
                val dPitch = normalizeAngleRad(pitchDown - prevAimPitchRad)
                prevAimRollRad = rollRight
                prevAimPitchRad = pitchDown
                prevAimTimestampNs = nowNs
                if (dt <= 0.002f || dt > 0.15f) return

                val velX = (dRoll / dt).coerceIn(-6f, 6f)
                val velY = (dPitch / dt).coerceIn(-6f, 6f)
                val outX = shapeAimStickAxis(velX, sensitivityXPercent, deadzonePercent, invertX)
                val outY = shapeAimStickAxis(velY, sensitivityYPercent, deadzonePercent, invertY)
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

    /**
     * Biến tốc độ xoay (rad/s) thành tín hiệu Cần Phải (R) nhạy và gọn:
     * - Lọc rung tay li ti (velDeadzone).
     * - Bù vùng chết trong game bắn súng console (anti-deadzone boost 14%) để nhích cổ tay nhẹ tâm vẫn bám ngay.
     * - Đường cong phản hồi nhanh (mũ 0.82) để vẩy tâm mượt, không bị ì.
     */
    private fun shapeAimStickAxis(
        velRadPerSec: Float,
        sensPercent: Int,
        deadzonePct: Int,
        invert: Boolean
    ): Float {
        if (!velRadPerSec.isFinite()) return 0f
        val velDz = 0.025f + (deadzonePct.coerceIn(0, 20) / 100f) * 0.25f
        val absVel = abs(velRadPerSec)
        if (absVel <= velDz) return 0f

        val sensScale = sensPercent.coerceIn(10, 300) / 100f
        val norm = (((absVel - velDz) / AIM_FULL_VEL_RAD_S) * sensScale).coerceIn(0f, 1f)
        val curved = norm.pow(0.82f)
        // Ramp mượt qua 6% đầu để tránh giật cục khi vừa vượt vùng chết, rồi nâng qua ngưỡng deadzone game Xbox
        val ramp = (curved / 0.06f).coerceIn(0f, 1f)
        val boosted = (ramp * AIM_ANTI_DEADZONE_BOOST + (1f - AIM_ANTI_DEADZONE_BOOST) * curved).coerceIn(0f, 1f)
        val signed = boosted * sign(velRadPerSec)
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
        if (!force && !returningToZero && targetRightStick == lastDispatchedRightStick && (nowNs - lastDispatchTimeNs) < MIN_DISPATCH_INTERVAL_NS) {
            return
        }
        if (force || targetRightStick != lastDispatchedRightStick || dx >= 0.005f || dy >= 0.005f || returningToZero) {
            lastDispatchedRightStick = targetRightStick
            lastDispatchedX = safeX
            lastDispatchedY = safeY
            lastDispatchTimeNs = nowNs
            onOutputChanged(targetRightStick, safeX, safeY)
        }
    }
}
