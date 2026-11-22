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
import kotlin.math.sign

/**
 * Bộ điều khiển Con quay hồi chuyển (Gyroscope) cho Ravno:
 * - Mặc định TẮT hoàn toàn: khi tắt không đăng ký cảm biến và không gửi tín hiệu.
 * - Khi người dùng BẬT:
 *   1. GAME_NATIVE (Mặc định khi bật): Không đăng ký cảm biến giả lập cần tay cầm, nhường hoàn toàn cảm biến gốc trình duyệt cho game hỗ trợ.
 *   2. RACING_STEER (Thủ công cho game đua xe): Chỉ nghiêng trái/phải điều khiển Cần Trái (LS trục X), khóa trục dọc = 0.
 *      + Ưu tiên cảm biến con quay trên tay cầm thật (Android 12+) và tự dự phòng sang cảm biến điện thoại.
 *      + Chuỗi dự phòng: GAME_ROTATION_VECTOR -> ROTATION_VECTOR -> GYROSCOPE -> GRAVITY -> ACCELEROMETER.
 *      + Dùng ma trận xoay tương đối R_rel = R_ref^T * R_cur tránh khóa khớp (gimbal lock).
 */
class GyroscopeController(
    context: Context,
    private val onOutputChanged: (targetRightStick: Boolean, outX: Float, outY: Float) -> Unit
) : SensorEventListener {

    companion object {
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
            return sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null ||
                sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
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
                        // Chỉ ưu tiên tay cầm vật lý nếu thực sự có cảm biến xoay/gyro (tránh bị gia tốc kế giả chặn mất gyro điện thoại)
                        val hasTrueGyro = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
                            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
                            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
                        if (hasTrueGyro) return sm
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

    var enabled: Boolean = false
        private set
    var mode: GyroControlMode = GyroControlMode.GAME_NATIVE
    var sensitivityXPercent: Int = 100
    var sensitivityYPercent: Int = 100
    var deadzonePercent: Int = 2
    var invertX: Boolean = false
    var invertY: Boolean = false

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val refMatrix = FloatArray(9)
    private val relMatrix = FloatArray(9)

    private var hasReferencePose = false
    private var refRollRad = 0f
    private var lastHandledRecenterTick = 0L

    // Dùng khi thiết bị chỉ có GYROSCOPE rời (không có RotationVector)
    private var integratedX = 0f
    private var lastGyroTimestampNs = 0L

    private var lastDispatchedX = 0f
    private var lastDispatchedY = 0f
    private var lastDispatchedRightStick = false

    private fun shouldEmulateSteering(): Boolean =
        enabled && mode == GyroControlMode.RACING_STEER && !isPaused

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

        val shouldListen = shouldEmulateSteering()
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
        } else if (shouldEmulateSteering()) {
            startListening()
        }
    }

    /**
     * Đẩy lại trạng thái hiện tại sang WebView sau khi trang tải xong (chỉ ở chế độ RACING_STEER).
     */
    fun syncCurrentOutput() {
        if (!shouldEmulateSteering()) return
        dispatchOutput(false, lastDispatchedX, 0f, force = true)
    }

    fun recenter() {
        hasReferencePose = false
        integratedX = 0f
        lastGyroTimestampNs = 0L
        if (shouldEmulateSteering()) {
            dispatchOutput(false, 0f, 0f, force = true)
        }
    }

    private fun tryRegisterBestSensor(sm: SensorManager): Boolean {
        val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null && sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME)) {
            return true
        }
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null && sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)) {
            return true
        }
        val accel = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null && sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)) {
            return true
        }
        return false
    }

    private fun startListening() {
        if (isListening || !shouldEmulateSteering()) return
        val controllerSm = findPhysicalControllerSensorManager()
        val systemSm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        hasReferencePose = false
        integratedX = 0f
        lastGyroTimestampNs = 0L

        var registered = false
        if (controllerSm != null) {
            registered = tryRegisterBestSensor(controllerSm)
            if (registered) {
                activeSensorManager = controllerSm
                isUsingControllerSensor = true
            }
        }
        if (!registered && systemSm != null) {
            registered = tryRegisterBestSensor(systemSm)
            if (registered) {
                activeSensorManager = systemSm
                isUsingControllerSensor = false
            }
        }
        isListening = registered
        if (registered) {
            dispatchOutput(false, 0f, 0f, force = true)
        }
    }

    fun stopListening() {
        val wasListening = isListening
        if (isListening) {
            try {
                activeSensorManager?.unregisterListener(this)
            } catch (_: Exception) {
            }
            isListening = false
        }
        activeSensorManager = null
        isUsingControllerSensor = false
        hasReferencePose = false
        integratedX = 0f
        lastGyroTimestampNs = 0L
        if (wasListening || lastDispatchedX != 0f || lastDispatchedY != 0f) {
            dispatchOutput(false, 0f, 0f, force = true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        if (!shouldEmulateSteering() || event == null) return

        val reqTick = recenterRequestTick
        if (reqTick > lastHandledRecenterTick) {
            lastHandledRecenterTick = reqTick
            recenter()
        }

        // Chế độ RACING_STEER: chỉ nghiêng trái/phải điều khiển Cần Trái (L - trục X), khóa trục dọc Y = 0
        val fullScaleRad = 0.56f
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                remapForScreenRotation(rotationMatrix, remappedMatrix)

                if (!hasReferencePose) {
                    System.arraycopy(remappedMatrix, 0, refMatrix, 0, 9)
                    hasReferencePose = true
                }

                // R_rel = R_ref^T * R_cur: góc xoay tương đối trong hệ tọa độ màn hình
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

            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_ACCELEROMETER -> {
                val (sx, sy, _) = remapVectorToScreenAxes(event.values[0], event.values[1], event.values[2])
                val rollRight = atan2(-sx, sy)
                if (!hasReferencePose) {
                    refRollRad = rollRight
                    hasReferencePose = true
                }
                val dRoll = normalizeAngleRad(rollRight - refRollRad)
                val outX = processAxis(dRoll / fullScaleRad, sensitivityXPercent, deadzonePercent, invertX)
                dispatchOutput(false, outX, 0f, force = false)
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
        if (force || targetRightStick != lastDispatchedRightStick || dx >= 0.008f || dy >= 0.008f || (safeX == 0f && lastDispatchedX != 0f) || (safeY == 0f && lastDispatchedY != 0f)) {
            lastDispatchedRightStick = targetRightStick
            lastDispatchedX = safeX
            lastDispatchedY = safeY
            onOutputChanged(targetRightStick, safeX, safeY)
        }
    }
}
