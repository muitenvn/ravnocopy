package com.example.cloudplay

import android.content.Context
import android.os.SystemClock
import com.muitenvn.cloudplay.protocol.AuthFailReason
import com.muitenvn.cloudplay.protocol.RelayHandshakeCodec
import com.muitenvn.cloudplay.protocol.RelayHandshakeFrame
import com.muitenvn.cloudplay.protocol.RemoteControlCodec
import com.muitenvn.cloudplay.protocol.RemoteControlFrame
import com.muitenvn.cloudplay.protocol.RemotePadCrypto
import com.muitenvn.cloudplay.protocol.RemotePadMqttTlsClient
import com.muitenvn.cloudplay.protocol.RemotePadProtocol
import com.muitenvn.cloudplay.protocol.RemotePadRelayCrypto
import com.muitenvn.cloudplay.protocol.RemotePadUdpCodec
import com.muitenvn.cloudplay.protocol.RemoteRelayBrokers
import com.muitenvn.cloudplay.protocol.VersionCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class TvRemoteRelayStatus {
    OFF,
    CONNECTING,
    READY,
    CONNECTED,
    LOCKED_OUT,
    ERROR
}

enum class TvPadTransportMode {
    NONE,
    LAN,
    REMOTE_RELAY
}

/**
 * Bộ quản lý kênh chuyển tiếp từ xa ngoài mạng LAN cho TV (:app).
 * - Mặc định TẮT hoàn toàn; chỉ chạy khi người dùng chủ động bật & xác nhận.
 * - Dùng MQTT 3.1.1 trên TLS cổng 8883 (0 Firebase, 0 đăng nhập Google), tự dự phòng EMQX -> HiveMQ.
 * - Mã ghép đôi 10 chữ số (XXXXX-XXXXX) kèm ECDH P-256 + AES-256-GCM đầu-cuối, tự hết hạn sau 15 phút chờ.
 * - Sai 3 lần khóa tạm 3 phút. Tuyệt đối không ghi log mã, khóa phiên hay dữ liệu nhạy cảm.
 */
internal object RemotePadTvRelayManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enabledFlag = AtomicBoolean(false)
    private val mqttClientRef = AtomicReference<RemotePadMqttTlsClient?>(null)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var idleExpiryJob: Job? = null

    @Volatile
    private var lockoutTickerJob: Job? = null

    @Volatile
    private var currentCode: String = ""

    @Volatile
    private var currentPairKey: ByteArray? = null

    @Volatile
    private var currentPairC2tTopic: String = ""

    @Volatile
    private var currentPairT2cTopic: String = ""

    @Volatile
    private var activeSessionAesKey: ByteArray? = null

    @Volatile
    private var activeSessionC2tTopic: String = ""

    @Volatile
    private var activeSessionT2cTopic: String = ""

    @Volatile
    private var activeSessionUdpToken: ByteArray? = null

    @Volatile
    private var activeDeviceId: String = ""

    @Volatile
    private var activeDeviceName: String = ""

    @Volatile
    private var codeExpiresAtElapsedMs: Long = 0L

    @Volatile
    private var lockoutUntilElapsedMs: Long = 0L

    @Volatile
    private var failedAttempts: Int = 0

    private val lastReceivedSeq = AtomicLong(-1L)

    fun isEnabled(): Boolean = enabledFlag.get()

    fun isRemoteSessionActive(): Boolean = enabledFlag.get() && activeSessionAesKey != null

    fun enable(context: Context) {
        appContext = context.applicationContext
        RemotePadTvServer.startServerIfNeeded(context.applicationContext)
        enabledFlag.set(true)
        failedAttempts = 0
        lockoutUntilElapsedMs = 0L
        startLockoutTickerIfNeeded()
        connectAndAdvertise(preferredSlot = 0)
    }

    fun disable() {
        enabledFlag.set(false)
        connectJob?.cancel()
        idleExpiryJob?.cancel()
        val hadSession = activeSessionAesKey != null
        if (hadSession) {
            sendEncryptedControl(RemoteControlFrame.Disconnect("TV_REMOTE_DISABLED"))
        }
        clearActiveSessionState(notifyServerDisconnected = hadSession)
        closeMqttClientQuietly()
        currentCode = ""
        currentPairKey = null
        currentPairC2tTopic = ""
        currentPairT2cTopic = ""
        codeExpiresAtElapsedMs = 0L
        failedAttempts = 0
        lockoutUntilElapsedMs = 0L
        RemotePadTvServer.updateRemoteRelayState(
            enabled = false,
            status = TvRemoteRelayStatus.OFF,
            pairingCode = "",
            brokerSlot = 0,
            expiresAtElapsedMs = 0L,
            failedAttempts = 0,
            lockoutRemainingSec = 0
        )
    }

    fun regenerateCode() {
        if (!enabledFlag.get()) return
        val client = mqttClientRef.get()
        if (client == null || !client.isConnected) {
            connectAndAdvertise(preferredSlot = 0)
            return
        }
        scope.launch {
            val hadSession = activeSessionAesKey != null
            if (hadSession) {
                sendEncryptedControl(RemoteControlFrame.Disconnect("TV_CODE_ROTATED"))
                clearActiveSessionState(notifyServerDisconnected = true)
            }
            if (currentPairC2tTopic.isNotBlank()) {
                client.unsubscribe(currentPairC2tTopic)
            }
            failedAttempts = 0
            lockoutUntilElapsedMs = 0L
            setupPairingTopicOnConnectedClient(client)
        }
    }

    fun disconnectActiveRemoteClient(reason: String = "TV_DISCONNECT", rotateCodeAfter: Boolean = false) {
        if (activeSessionAesKey == null) return
        scope.launch {
            sendEncryptedControl(RemoteControlFrame.Disconnect(reason))
            delay(25L)
            val client = mqttClientRef.get()
            if (activeSessionC2tTopic.isNotBlank()) {
                client?.unsubscribe(activeSessionC2tTopic)
            }
            clearActiveSessionState(notifyServerDisconnected = true)
            if (enabledFlag.get() && client != null && client.isConnected) {
                if (rotateCodeAfter) {
                    if (currentPairC2tTopic.isNotBlank()) client.unsubscribe(currentPairC2tTopic)
                    setupPairingTopicOnConnectedClient(client)
                } else if (currentPairC2tTopic.isNotBlank()) {
                    client.subscribe(currentPairC2tTopic)
                    resetIdleExpiryTimer()
                    RemotePadTvServer.updateRemoteRelayState(
                        enabled = true,
                        status = if (currentLockoutRemainingSec() > 0) TvRemoteRelayStatus.LOCKED_OUT else TvRemoteRelayStatus.READY,
                        pairingCode = currentCode,
                        brokerSlot = client.broker.slot,
                        expiresAtElapsedMs = codeExpiresAtElapsedMs,
                        failedAttempts = failedAttempts,
                        lockoutRemainingSec = currentLockoutRemainingSec()
                    )
                }
            }
        }
    }

    /**
     * Gọi khi điện thoại đã nâng cấp thành công sang kết nối LAN trực tiếp:
     * đóng phiên relay tạm mà không nhả nút bấm của phiên LAN mới.
     */
    fun onUpgradedToDirectLan() {
        val client = mqttClientRef.get()
        if (activeSessionC2tTopic.isNotBlank()) {
            client?.unsubscribe(activeSessionC2tTopic)
        }
        activeSessionAesKey = null
        activeSessionC2tTopic = ""
        activeSessionT2cTopic = ""
        activeSessionUdpToken = null
        activeDeviceId = ""
        activeDeviceName = ""
        lastReceivedSeq.set(-1L)
        if (enabledFlag.get() && client != null && client.isConnected && currentPairC2tTopic.isNotBlank()) {
            client.subscribe(currentPairC2tTopic)
            resetIdleExpiryTimer()
            RemotePadTvServer.updateRemoteRelayState(
                enabled = true,
                status = TvRemoteRelayStatus.READY,
                pairingCode = currentCode,
                brokerSlot = client.broker.slot,
                expiresAtElapsedMs = codeExpiresAtElapsedMs,
                failedAttempts = failedAttempts,
                lockoutRemainingSec = currentLockoutRemainingSec()
            )
        }
    }

    fun sendRumble(durationMs: Int, strongMagnitude: Float, weakMagnitude: Float): Boolean {
        if (!isRemoteSessionActive()) return false
        scope.launch {
            sendEncryptedControl(
                RemoteControlFrame.Rumble(
                    durationMs = durationMs.coerceIn(0, 2000),
                    strongMagnitude = strongMagnitude.coerceIn(0f, 1f),
                    weakMagnitude = weakMagnitude.coerceIn(0f, 1f)
                )
            )
        }
        return true
    }

    fun sendLayoutSync(presetName: String, gameKey: String, gameTitle: String) {
        if (!isRemoteSessionActive()) return
        scope.launch {
            sendEncryptedControl(
                RemoteControlFrame.LayoutSync(
                    presetName = presetName,
                    gameKey = gameKey,
                    gameTitle = gameTitle
                )
            )
        }
    }

    private fun connectAndAdvertise(preferredSlot: Int) {
        connectJob?.cancel()
        connectJob = scope.launch {
            var backoffMs = 1_200L
            while (isActive && enabledFlag.get()) {
                closeMqttClientQuietly()
                RemotePadTvServer.updateRemoteRelayState(
                    enabled = true,
                    status = TvRemoteRelayStatus.CONNECTING,
                    pairingCode = currentCode,
                    brokerSlot = preferredSlot,
                    expiresAtElapsedMs = codeExpiresAtElapsedMs,
                    failedAttempts = failedAttempts,
                    lockoutRemainingSec = currentLockoutRemainingSec()
                )

                val brokersToTry = if (preferredSlot == 1) {
                    listOf(RemoteRelayBrokers.BACKUP, RemoteRelayBrokers.PRIMARY)
                } else {
                    listOf(RemoteRelayBrokers.PRIMARY, RemoteRelayBrokers.BACKUP)
                }

                var connectedClient: RemotePadMqttTlsClient? = null
                for (broker in brokersToTry) {
                    if (!enabledFlag.get() || !isActive) break
                    val candidate = RemotePadMqttTlsClient(broker)
                    val ok = runCatching {
                        candidate.connect(
                            onMessage = { topic, payload -> handleIncomingMqttMessage(topic, payload) },
                            onDisconnected = { handleUnexpectedBrokerDrop() }
                        )
                    }.isSuccess
                    if (ok) {
                        connectedClient = candidate
                        break
                    } else {
                        candidate.close()
                    }
                }

                if (connectedClient != null && enabledFlag.get()) {
                    mqttClientRef.set(connectedClient)
                    setupPairingTopicOnConnectedClient(connectedClient)
                    return@launch
                }

                if (!enabledFlag.get()) return@launch
                RemotePadTvServer.updateRemoteRelayState(
                    enabled = true,
                    status = TvRemoteRelayStatus.ERROR,
                    pairingCode = "",
                    brokerSlot = 0,
                    expiresAtElapsedMs = 0L,
                    failedAttempts = failedAttempts,
                    lockoutRemainingSec = currentLockoutRemainingSec()
                )
                delay(backoffMs)
                backoffMs = (backoffMs * 2L).coerceAtMost(15_000L)
            }
        }
    }

    private fun setupPairingTopicOnConnectedClient(client: RemotePadMqttTlsClient) {
        val brokerSlot = client.broker.slot
        val newCode = RemotePadRelayCrypto.generatePairingCode(brokerSlot)
        val pairKey = RemotePadRelayCrypto.derivePairKey(newCode)
        val pairTopicId = RemotePadRelayCrypto.derivePairTopicIdFromPairKey(pairKey)
        val c2t = RemotePadRelayCrypto.pairClientToTvTopic(pairTopicId)
        val t2c = RemotePadRelayCrypto.pairTvToClientTopic(pairTopicId)

        currentCode = newCode
        currentPairKey = pairKey
        currentPairC2tTopic = c2t
        currentPairT2cTopic = t2c
        client.subscribe(c2t)
        resetIdleExpiryTimer()

        RemotePadTvServer.updateRemoteRelayState(
            enabled = true,
            status = if (currentLockoutRemainingSec() > 0) TvRemoteRelayStatus.LOCKED_OUT else TvRemoteRelayStatus.READY,
            pairingCode = newCode,
            brokerSlot = brokerSlot,
            expiresAtElapsedMs = codeExpiresAtElapsedMs,
            failedAttempts = failedAttempts,
            lockoutRemainingSec = currentLockoutRemainingSec()
        )
    }

    private fun handleIncomingMqttMessage(topic: String, payload: ByteArray) {
        if (!enabledFlag.get()) return
        when (topic) {
            activeSessionC2tTopic -> handleSessionPacket(payload)
            currentPairC2tTopic -> handlePairHandshakePacket(payload)
        }
    }

    private fun handlePairHandshakePacket(payload: ByteArray) {
        if (activeSessionAesKey != null) return
        val client = mqttClientRef.get() ?: return
        val pairKey = currentPairKey ?: return

        val lockSec = currentLockoutRemainingSec()
        val decrypted = RemotePadRelayCrypto.decryptAesGcm(pairKey, payload)
        if (decrypted == null) {
            recordFailedPairAttempt()
            return
        }

        val frame = RelayHandshakeCodec.parse(decrypted) as? RelayHandshakeFrame.ClientHello ?: run {
            recordFailedPairAttempt()
            return
        }

        if (lockSec > 0) {
            publishEncryptedHandshake(
                client = client,
                pairKey = pairKey,
                frame = RelayHandshakeFrame.TvReject(
                    protocolVersion = RemotePadProtocol.PROTOCOL_VERSION,
                    reason = AuthFailReason.LOCKED_OUT,
                    remainingAttempts = 0,
                    lockoutRemainingSec = lockSec
                )
            )
            return
        }

        val verCheck = RemotePadProtocol.checkVersionCompatibility(
            localVersion = RemotePadProtocol.PROTOCOL_VERSION,
            remoteVersion = frame.protocolVersion,
            isLocalTv = true
        )
        if (verCheck != VersionCheckResult.COMPATIBLE) {
            RemotePadTvServer.reportRemoteVersionMismatch(frame.protocolVersion, verCheck)
            publishEncryptedHandshake(
                client = client,
                pairKey = pairKey,
                frame = RelayHandshakeFrame.TvReject(
                    protocolVersion = RemotePadProtocol.PROTOCOL_VERSION,
                    reason = AuthFailReason.VERSION_MISMATCH
                )
            )
            return
        }

        // Bắt tay ECDH P-256 + HKDF-SHA256 tạo khóa phiên riêng
        val tvKeyPair = RemotePadRelayCrypto.generateEcdhKeyPair()
        val tvNonceHex = RemotePadCrypto.generateNonceHex(16)
        val sessionAesKey = RemotePadRelayCrypto.deriveSessionAesKey(
            localPrivateKey = tvKeyPair.private,
            peerPublicKeyHex = frame.clientPubHex,
            clientNonceHex = frame.clientNonceHex,
            tvNonceHex = tvNonceHex
        ) ?: run {
            recordFailedPairAttempt()
            return
        }

        val sessionTopicHex = RemotePadRelayCrypto.generateSessionTopicHex()
        val udpTokenBytes = RemotePadCrypto.hexToBytes(RemotePadCrypto.generateNonceHex(RemotePadProtocol.SESSION_TOKEN_BYTES))
        val udpTokenHex = RemotePadCrypto.bytesToHex(udpTokenBytes)
        val cleanId = frame.deviceId.trim().ifBlank { UUID.randomUUID().toString() }
        val cleanName = frame.deviceName.trim().ifBlank { "Ravno Pad" }

        // Đăng ký mã nâng cấp LAN 1 lần (hiệu lực 15 giây) để nếu cùng router sẽ nối thẳng LAN
        val quickTokenHex = RemotePadCrypto.generateNonceHex(16)
        RemotePadTvServer.registerPendingLanQuickToken(
            quickTokenHex = quickTokenHex,
            deviceId = cleanId,
            deviceName = cleanName,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + 15_000L
        )

        val sessC2t = RemotePadRelayCrypto.sessionClientToTvTopic(sessionTopicHex)
        val sessT2c = RemotePadRelayCrypto.sessionTvToClientTopic(sessionTopicHex)

        // Đăng ký kênh phiên TRƯỚC khi gửi TV_WELCOME để tránh mất gói đầu tiên
        activeSessionAesKey = sessionAesKey
        activeSessionC2tTopic = sessC2t
        activeSessionT2cTopic = sessT2c
        activeSessionUdpToken = udpTokenBytes
        activeDeviceId = cleanId
        activeDeviceName = cleanName
        lastReceivedSeq.set(-1L)
        failedAttempts = 0
        lockoutUntilElapsedMs = 0L
        idleExpiryJob?.cancel()

        client.subscribe(sessC2t)
        client.unsubscribe(currentPairC2tTopic)

        val serverSnap = RemotePadTvServer.state.value
        val gameInfo = RemotePadTvServer.getCurrentGameSyncInfo()
        val welcome = RelayHandshakeFrame.TvWelcome(
            protocolVersion = RemotePadProtocol.PROTOCOL_VERSION,
            tvId = serverSnap.tvId,
            tvName = serverSnap.tvDisplayName.ifBlank { "Ravno TV" },
            tvNonceHex = tvNonceHex,
            tvPubHex = RemotePadRelayCrypto.encodePublicKeyHex(tvKeyPair),
            sessionTopicHex = sessionTopicHex,
            udpSessionTokenHex = udpTokenHex,
            playerIndex = 1,
            lanHost = serverSnap.localIpAddress,
            lanTcpPort = serverSnap.tcpPort,
            lanUdpPort = serverSnap.udpPort,
            lanQuickTokenHex = quickTokenHex,
            activeGameKey = gameInfo.first,
            activeGameTitle = gameInfo.second,
            activePresetName = gameInfo.third
        )
        publishEncryptedHandshake(client, pairKey, welcome)

        RemotePadTvServer.onRemoteRelaySessionConnected(
            deviceId = cleanId,
            deviceName = cleanName,
            brokerSlot = client.broker.slot,
            pairingCode = currentCode
        )
    }

    private fun handleSessionPacket(payload: ByteArray) {
        val aesKey = activeSessionAesKey ?: return
        val udpToken = activeSessionUdpToken ?: return
        val plaintext = RemotePadRelayCrypto.decryptAesGcm(aesKey, payload) ?: return
        if (plaintext.isEmpty()) return

        when (plaintext[0]) {
            RemotePadRelayCrypto.PACKET_TYPE_INPUT -> {
                if (plaintext.size < 1 + RemotePadProtocol.UDP_PACKET_BYTES) return
                val udpBytes = plaintext.copyOfRange(1, 1 + RemotePadProtocol.UDP_PACKET_BYTES)
                val decoded = RemotePadUdpCodec.decodeAndVerify(
                    packetBytes = udpBytes,
                    length = udpBytes.size,
                    expectedSessionToken = udpToken,
                    lastReceivedSequence = lastReceivedSeq.get()
                ) ?: return
                lastReceivedSeq.set(decoded.sequenceNumber)
                RemotePadTvServer.onRemoteRelayInputState(decoded)
            }
            RemotePadRelayCrypto.PACKET_TYPE_CONTROL -> {
                val line = String(plaintext, 1, plaintext.size - 1, StandardCharsets.UTF_8)
                when (val frame = RemoteControlCodec.parse(line)) {
                    is RemoteControlFrame.Ping -> {
                        val gameInfo = RemotePadTvServer.getCurrentGameSyncInfo()
                        sendEncryptedControl(
                            RemoteControlFrame.Pong(
                                clientSentRealtimeMs = frame.clientSentRealtimeMs,
                                activeGameKey = gameInfo.first
                            )
                        )
                    }
                    is RemoteControlFrame.Disconnect -> {
                        val client = mqttClientRef.get()
                        if (activeSessionC2tTopic.isNotBlank()) {
                            client?.unsubscribe(activeSessionC2tTopic)
                        }
                        clearActiveSessionState(notifyServerDisconnected = true)
                        if (enabledFlag.get() && client != null && client.isConnected && currentPairC2tTopic.isNotBlank()) {
                            client.subscribe(currentPairC2tTopic)
                            resetIdleExpiryTimer()
                            RemotePadTvServer.updateRemoteRelayState(
                                enabled = true,
                                status = TvRemoteRelayStatus.READY,
                                pairingCode = currentCode,
                                brokerSlot = client.broker.slot,
                                expiresAtElapsedMs = codeExpiresAtElapsedMs,
                                failedAttempts = failedAttempts,
                                lockoutRemainingSec = currentLockoutRemainingSec()
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun publishEncryptedHandshake(
        client: RemotePadMqttTlsClient,
        pairKey: ByteArray,
        frame: RelayHandshakeFrame
    ) {
        val topic = currentPairT2cTopic
        if (topic.isBlank()) return
        val encrypted = RemotePadRelayCrypto.encryptAesGcm(pairKey, RelayHandshakeCodec.serialize(frame))
        client.publishQos0(topic, encrypted)
    }

    private fun sendEncryptedControl(frame: RemoteControlFrame) {
        val client = mqttClientRef.get() ?: return
        val aesKey = activeSessionAesKey ?: return
        val topic = activeSessionT2cTopic
        if (topic.isBlank()) return
        val raw = RemotePadRelayCrypto.wrapControlFrame(frame)
        val encrypted = RemotePadRelayCrypto.encryptAesGcm(aesKey, raw)
        client.publishQos0(topic, encrypted)
    }

    private fun handleUnexpectedBrokerDrop() {
        val hadSession = activeSessionAesKey != null
        clearActiveSessionState(notifyServerDisconnected = hadSession)
        if (enabledFlag.get()) {
            val prevSlot = mqttClientRef.get()?.broker?.slot ?: 0
            connectAndAdvertise(preferredSlot = if (prevSlot == 0) 1 else 0)
        }
    }

    private fun clearActiveSessionState(notifyServerDisconnected: Boolean) {
        activeSessionAesKey = null
        activeSessionC2tTopic = ""
        activeSessionT2cTopic = ""
        activeSessionUdpToken = null
        activeDeviceId = ""
        activeDeviceName = ""
        lastReceivedSeq.set(-1L)
        if (notifyServerDisconnected) {
            RemotePadTvServer.onRemoteRelaySessionDisconnected()
        }
    }

    private fun recordFailedPairAttempt() {
        failedAttempts += 1
        if (failedAttempts >= RemotePadProtocol.MAX_PIN_ATTEMPTS) {
            lockoutUntilElapsedMs = SystemClock.elapsedRealtime() + RemotePadProtocol.PIN_LOCKOUT_DURATION_MS
            val lockSec = (RemotePadProtocol.PIN_LOCKOUT_DURATION_MS / 1000L).toInt()
            RemotePadTvServer.updateRemoteRelayState(
                enabled = enabledFlag.get(),
                status = TvRemoteRelayStatus.LOCKED_OUT,
                pairingCode = currentCode,
                brokerSlot = mqttClientRef.get()?.broker?.slot ?: 0,
                expiresAtElapsedMs = codeExpiresAtElapsedMs,
                failedAttempts = failedAttempts,
                lockoutRemainingSec = lockSec
            )
        } else {
            RemotePadTvServer.updateRemoteRelayState(
                enabled = enabledFlag.get(),
                status = TvRemoteRelayStatus.READY,
                pairingCode = currentCode,
                brokerSlot = mqttClientRef.get()?.broker?.slot ?: 0,
                expiresAtElapsedMs = codeExpiresAtElapsedMs,
                failedAttempts = failedAttempts,
                lockoutRemainingSec = 0
            )
        }
    }

    private fun resetIdleExpiryTimer() {
        idleExpiryJob?.cancel()
        codeExpiresAtElapsedMs = SystemClock.elapsedRealtime() + RemotePadProtocol.REMOTE_CODE_IDLE_EXPIRY_MS
        idleExpiryJob = scope.launch {
            delay(RemotePadProtocol.REMOTE_CODE_IDLE_EXPIRY_MS)
            if (enabledFlag.get() && activeSessionAesKey == null) {
                disable()
            }
        }
    }

    private fun startLockoutTickerIfNeeded() {
        if (lockoutTickerJob?.isActive == true) return
        lockoutTickerJob = scope.launch {
            while (isActive) {
                val remSec = currentLockoutRemainingSec()
                val cur = RemotePadTvServer.state.value
                if (cur.remoteLockoutRemainingSec != remSec) {
                    val unlockedNow = remSec == 0 && cur.remoteLockoutRemainingSec > 0
                    if (unlockedNow) {
                        failedAttempts = 0
                    }
                    RemotePadTvServer.updateRemoteRelayState(
                        enabled = enabledFlag.get(),
                        status = when {
                            !enabledFlag.get() -> TvRemoteRelayStatus.OFF
                            activeSessionAesKey != null -> TvRemoteRelayStatus.CONNECTED
                            remSec > 0 -> TvRemoteRelayStatus.LOCKED_OUT
                            mqttClientRef.get()?.isConnected == true -> TvRemoteRelayStatus.READY
                            else -> cur.remoteRelayStatus
                        },
                        pairingCode = currentCode,
                        brokerSlot = mqttClientRef.get()?.broker?.slot ?: 0,
                        expiresAtElapsedMs = codeExpiresAtElapsedMs,
                        failedAttempts = failedAttempts,
                        lockoutRemainingSec = remSec
                    )
                }
                delay(1_000L)
            }
        }
    }

    private fun currentLockoutRemainingSec(): Int {
        val deadline = lockoutUntilElapsedMs
        if (deadline <= 0L) return 0
        val diff = deadline - SystemClock.elapsedRealtime()
        if (diff <= 0L) {
            lockoutUntilElapsedMs = 0L
            return 0
        }
        return ((diff + 999L) / 1000L).toInt()
    }

    private fun closeMqttClientQuietly() {
        mqttClientRef.getAndSet(null)?.close()
    }
}
