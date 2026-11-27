package com.muitenvn.cloudplay.pad

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
import com.muitenvn.cloudplay.protocol.RemoteRelayBrokers
import com.muitenvn.cloudplay.protocol.TvServiceStatus
import com.muitenvn.cloudplay.protocol.VersionCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class PadTransportMode {
    LAN,
    LAN_UPGRADED,
    REMOTE_RELAY
}

/**
 * Bộ kết nối kênh chuyển tiếp từ xa ngoài mạng LAN cho Ravno Pad (:pad).
 * - Không cần Firebase, không cần đăng nhập Google.
 * - Nhập mã 8 ký tự -> bắt tay ECDH P-256 + AES-256-GCM trực tiếp với TV qua MQTT-TLS (cổng 8883).
 * - Tự động thử nâng cấp nối thẳng LAN (~600ms) nếu hai máy ở cùng mạng nội bộ; nếu không thì giữ
 *   ngay kênh TLS mã hóa đầu-cuối (nút nhấn/nhả gửi tức thì, cần analog gộp ~30 Hz, nhịp tim 150ms).
 * - Tuyệt đối không ghi log mã ghép đôi hay khóa phiên.
 */
internal object PadRemoteRelayConnector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mqttClientRef = AtomicReference<RemotePadMqttTlsClient?>(null)
    private val activeSessionFlag = AtomicBoolean(false)

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var heartbeatJob: Job? = null

    @Volatile
    private var pingJob: Job? = null

    @Volatile
    private var sessionAesKey: ByteArray? = null

    @Volatile
    private var sessionC2tTopic: String = ""

    @Volatile
    private var sessionT2cTopic: String = ""

    @Volatile
    private var sessionUdpToken: ByteArray? = null

    private val lastAnalogSentElapsedMs = AtomicLong(0L)

    fun isActive(): Boolean = activeSessionFlag.get() && sessionAesKey != null && mqttClientRef.get()?.isConnected == true

    fun getSessionUdpToken(): ByteArray? = sessionUdpToken

    fun connectWithCode(
        rawCode: String,
        clientDeviceId: String,
        clientDeviceName: String
    ) {
        val normCode = RemotePadRelayCrypto.normalizeCode(rawCode)
        if (!RemotePadRelayCrypto.isValidCode(normCode)) {
            PadNetworkClient.onRemoteConnectStatus(
                isConnecting = false,
                statusCode = "INVALID_CODE"
            )
            return
        }

        disconnectQuietly(sendDisconnectFrame = false, notifyClient = false)
        connectJob?.cancel()
        connectJob = scope.launch {
            PadNetworkClient.onRemoteConnectStatus(
                isConnecting = true,
                statusCode = "CONNECTING"
            )

            val brokerSlot = RemotePadRelayCrypto.extractBrokerSlot(normCode)
            val broker = RemoteRelayBrokers.bySlot(brokerSlot)
            val pairKey = RemotePadRelayCrypto.derivePairKey(normCode)
            val pairTopicId = RemotePadRelayCrypto.derivePairTopicId(normCode)
            val pairC2t = RemotePadRelayCrypto.pairClientToTvTopic(pairTopicId)
            val pairT2c = RemotePadRelayCrypto.pairTvToClientTopic(pairTopicId)

            val clientKeyPair = RemotePadRelayCrypto.generateEcdhKeyPair()
            val clientNonceHex = RemotePadCrypto.generateNonceHex(16)
            val handshakeDeferred = CompletableDeferred<RelayHandshakeFrame?>()

            val client = RemotePadMqttTlsClient(broker)
            val connected = runCatching {
                client.connect(
                    onMessage = { topic, payload ->
                        when (topic) {
                            pairT2c -> {
                                val dec = RemotePadRelayCrypto.decryptAesGcm(pairKey, payload)
                                if (dec != null) {
                                    val parsed = RelayHandshakeCodec.parse(dec)
                                    if (parsed != null) {
                                        handshakeDeferred.complete(parsed)
                                    }
                                }
                            }
                            sessionT2cTopic -> handleSessionControlMessage(payload)
                        }
                    },
                    onDisconnected = {
                        if (!handshakeDeferred.isCompleted) {
                            handshakeDeferred.complete(null)
                        }
                        if (activeSessionFlag.compareAndSet(true, false)) {
                            stopBackgroundLoops()
                            clearSessionSecrets()
                            PadNetworkClient.onRemoteRelayDisconnected("CONNECTION_LOST")
                        }
                    }
                )
            }.isSuccess

            if (!connected) {
                client.close()
                PadNetworkClient.onRemoteConnectStatus(
                    isConnecting = false,
                    statusCode = "BROKER_ERROR"
                )
                return@launch
            }
            mqttClientRef.set(client)

            PadNetworkClient.onRemoteConnectStatus(
                isConnecting = true,
                statusCode = "HANDSHAKE"
            )
            client.subscribe(pairT2c)
            delay(140L) // Chờ broker hoàn tất đăng ký chủ đề t2c trước khi gửi ClientHello

            val hello = RelayHandshakeFrame.ClientHello(
                protocolVersion = RemotePadProtocol.PROTOCOL_VERSION,
                deviceId = clientDeviceId,
                deviceName = clientDeviceName,
                clientNonceHex = clientNonceHex,
                clientPubHex = RemotePadRelayCrypto.encodePublicKeyHex(clientKeyPair)
            )
            val encryptedHello = RemotePadRelayCrypto.encryptAesGcm(pairKey, RelayHandshakeCodec.serialize(hello))
            client.publishQos0(pairC2t, encryptedHello)

            // Chờ phản hồi lần 1 (3s); nếu chưa thấy, gửi lại 1 lần và chờ thêm 5s
            var reply = withTimeoutOrNull(3_000L) { handshakeDeferred.await() }
            if (reply == null && client.isConnected && isActive) {
                val retryHello = RemotePadRelayCrypto.encryptAesGcm(pairKey, RelayHandshakeCodec.serialize(hello))
                client.publishQos0(pairC2t, retryHello)
                reply = withTimeoutOrNull(5_000L) { handshakeDeferred.await() }
            }

            client.unsubscribe(pairT2c)

            when (reply) {
                is RelayHandshakeFrame.TvReject -> {
                    client.close()
                    mqttClientRef.compareAndSet(client, null)
                    PadNetworkClient.onRemoteHandshakeRejected(reply)
                }
                is RelayHandshakeFrame.TvWelcome -> {
                    val verCheck = RemotePadProtocol.checkVersionCompatibility(
                        localVersion = RemotePadProtocol.PROTOCOL_VERSION,
                        remoteVersion = reply.protocolVersion,
                        isLocalTv = false
                    )
                    if (verCheck != VersionCheckResult.COMPATIBLE) {
                        client.close()
                        mqttClientRef.compareAndSet(client, null)
                        PadNetworkClient.onRemoteVersionMismatch(verCheck)
                        return@launch
                    }

                    val derivedKey = RemotePadRelayCrypto.deriveSessionAesKey(
                        localPrivateKey = clientKeyPair.private,
                        peerPublicKeyHex = reply.tvPubHex,
                        clientNonceHex = clientNonceHex,
                        tvNonceHex = reply.tvNonceHex
                    )
                    val udpTokenBytes = RemotePadCrypto.hexToBytes(reply.udpSessionTokenHex)
                    if (derivedKey == null || udpTokenBytes.size != RemotePadProtocol.SESSION_TOKEN_BYTES) {
                        client.close()
                        mqttClientRef.compareAndSet(client, null)
                        PadNetworkClient.onRemoteConnectStatus(
                            isConnecting = false,
                            statusCode = "TIMEOUT"
                        )
                        return@launch
                    }

                    val remoteTv = DiscoveredTvDevice(
                        tvId = reply.tvId.ifBlank { "remote-${reply.sessionTopicHex.take(8)}" },
                        tvName = reply.tvName.ifBlank { "Ravno TV" },
                        hostAddress = reply.lanHost.ifBlank { broker.host },
                        tcpPort = reply.lanTcpPort,
                        udpPort = reply.lanUdpPort,
                        protocolVersion = reply.protocolVersion,
                        status = TvServiceStatus.HAS_CONTROLLER,
                        hasRememberToken = false
                    )

                    // Ưu tiên 1: Thử nâng cấp nối thẳng LAN (~600ms) nếu hai máy ở cùng mạng nội bộ
                    if (reply.lanHost.isNotBlank() && reply.lanQuickTokenHex.isNotBlank()) {
                        PadNetworkClient.onRemoteConnectStatus(
                            isConnecting = true,
                            statusCode = "TRYING_LAN"
                        )
                        val upgraded = PadNetworkClient.tryQuickLanUpgrade(
                            tv = remoteTv,
                            quickTokenHex = reply.lanQuickTokenHex
                        )
                        if (upgraded) {
                            client.close()
                            mqttClientRef.compareAndSet(client, null)
                            return@launch
                        }
                    }

                    // Ưu tiên 2: Giữ ngay kênh TLS mã hóa đầu-cuối (4G/5G / khác Wi-Fi)
                    val sessC2t = RemotePadRelayCrypto.sessionClientToTvTopic(reply.sessionTopicHex)
                    val sessT2c = RemotePadRelayCrypto.sessionTvToClientTopic(reply.sessionTopicHex)
                    sessionAesKey = derivedKey
                    sessionC2tTopic = sessC2t
                    sessionT2cTopic = sessT2c
                    sessionUdpToken = udpTokenBytes
                    activeSessionFlag.set(true)

                    client.subscribe(sessT2c)
                    PadNetworkClient.onRemoteRelaySessionReady(
                        tv = remoteTv,
                        playerIndex = reply.playerIndex,
                        activeGameKey = reply.activeGameKey,
                        activeGameTitle = reply.activeGameTitle,
                        activePresetName = reply.activePresetName
                    )
                    startSessionLoops(client)
                }
                else -> {
                    client.close()
                    mqttClientRef.compareAndSet(client, null)
                    PadNetworkClient.onRemoteConnectStatus(
                        isConnecting = false,
                        statusCode = "TIMEOUT"
                    )
                }
            }
        }
    }

    /**
     * Gửi gói trạng thái tay cầm 48 byte đã mã hóa AES-256-GCM:
     * - [isUrgent] = true (nhấn/nhả nút số, nhả toàn bộ nút): gửi tức thì không trễ.
     * - [isUrgent] = false (di chuyển cần analog / cò / gyro): gộp tối đa ~30 Hz (33ms).
     */
    fun sendEncryptedInputPacket(udpPacket48: ByteArray, isUrgent: Boolean): Boolean {
        val client = mqttClientRef.get() ?: return false
        val key = sessionAesKey ?: return false
        val topic = sessionC2tTopic
        if (!activeSessionFlag.get() || !client.isConnected || topic.isBlank()) return false

        val now = SystemClock.elapsedRealtime()
        if (!isUrgent) {
            val prev = lastAnalogSentElapsedMs.get()
            if (now - prev < RemotePadProtocol.REMOTE_RELAY_ANALOG_MIN_INTERVAL_MS) {
                return false
            }
            lastAnalogSentElapsedMs.set(now)
        } else {
            lastAnalogSentElapsedMs.set(now)
        }

        val wrapped = RemotePadRelayCrypto.wrapInputPacket(udpPacket48)
        val encrypted = RemotePadRelayCrypto.encryptAesGcm(key, wrapped)
        return client.publishQos0(topic, encrypted)
    }

    fun sendEncryptedControl(frame: RemoteControlFrame): Boolean {
        val client = mqttClientRef.get() ?: return false
        val key = sessionAesKey ?: return false
        val topic = sessionC2tTopic
        if (!activeSessionFlag.get() || !client.isConnected || topic.isBlank()) return false
        val wrapped = RemotePadRelayCrypto.wrapControlFrame(frame)
        val encrypted = RemotePadRelayCrypto.encryptAesGcm(key, wrapped)
        return client.publishQos0(topic, encrypted)
    }

    fun disconnectQuietly(
        reason: String = "PHONE_DISCONNECT",
        sendDisconnectFrame: Boolean = true,
        notifyClient: Boolean = false
    ) {
        connectJob?.cancel()
        val wasActive = activeSessionFlag.getAndSet(false)
        stopBackgroundLoops()
        val client = mqttClientRef.getAndSet(null)
        val key = sessionAesKey
        val topic = sessionC2tTopic
        clearSessionSecrets()
        if (wasActive && sendDisconnectFrame && client != null && key != null && topic.isNotBlank()) {
            runCatching {
                val wrapped = RemotePadRelayCrypto.wrapControlFrame(RemoteControlFrame.Disconnect(reason))
                val encrypted = RemotePadRelayCrypto.encryptAesGcm(key, wrapped)
                client.publishQos0(topic, encrypted)
            }
        }
        runCatching { client?.close() }
        if (notifyClient && wasActive) {
            PadNetworkClient.onRemoteRelayDisconnected(reason)
        }
    }

    private fun startSessionLoops(client: RemotePadMqttTlsClient) {
        stopBackgroundLoops()
        heartbeatJob = scope.launch {
            while (isActive && activeSessionFlag.get() && client.isConnected) {
                PadNetworkClient.sendRemoteHeartbeatSnapshot()
                delay(RemotePadProtocol.REMOTE_RELAY_HEARTBEAT_INTERVAL_MS)
            }
        }
        pingJob = scope.launch {
            while (isActive && activeSessionFlag.get() && client.isConnected) {
                sendEncryptedControl(RemoteControlFrame.Ping(SystemClock.elapsedRealtime()))
                delay(1_500L)
            }
        }
    }

    private fun handleSessionControlMessage(payload: ByteArray) {
        val key = sessionAesKey ?: return
        val plaintext = RemotePadRelayCrypto.decryptAesGcm(key, payload) ?: return
        if (plaintext.isEmpty() || plaintext[0] != RemotePadRelayCrypto.PACKET_TYPE_CONTROL) return
        val line = String(plaintext, 1, plaintext.size - 1, StandardCharsets.UTF_8)
        when (val frame = RemoteControlCodec.parse(line)) {
            is RemoteControlFrame.Pong -> {
                val rtt = (SystemClock.elapsedRealtime() - frame.clientSentRealtimeMs)
                    .coerceIn(1L, 9_999L)
                    .toInt()
                PadNetworkClient.onRemotePongReceived(rtt, frame.activeGameKey)
            }
            is RemoteControlFrame.Rumble -> {
                PadNetworkClient.triggerPhoneVibration(frame.durationMs, frame.strongMagnitude, frame.weakMagnitude)
            }
            is RemoteControlFrame.LayoutSync -> {
                PadNetworkClient.onRemoteLayoutSyncReceived(frame)
            }
            is RemoteControlFrame.Disconnect -> {
                disconnectQuietly(reason = frame.reason, sendDisconnectFrame = false, notifyClient = true)
            }
            else -> Unit
        }
    }

    private fun stopBackgroundLoops() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        pingJob?.cancel()
        pingJob = null
    }

    private fun clearSessionSecrets() {
        sessionAesKey = null
        sessionC2tTopic = ""
        sessionT2cTopic = ""
        sessionUdpToken = null
    }
}
