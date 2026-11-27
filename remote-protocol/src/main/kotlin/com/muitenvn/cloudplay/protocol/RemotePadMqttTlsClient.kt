package com.muitenvn.cloudplay.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Máy khách MQTT 3.1.1 trên TLS siêu nhẹ viết thuần Kotlin (0 thư viện ngoài).
 * - Chỉ truyền gói QoS 0, retain=false (không lưu trữ gì trên máy chủ trung gian).
 * - Bật TCP_NODELAY và SNI để độ trễ thấp nhất và tương thích mọi broker TLS cổng 8883.
 */
class RemotePadMqttTlsClient(
    val broker: RemoteRelayBroker,
    private val keepAliveSec: Int = 20,
    private val connectTimeoutMs: Int = 5_500
) {
    private val packetIdGen = AtomicInteger(1)
    private val connectedFlag = AtomicBoolean(false)
    private val closedFlag = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var outputStream: BufferedOutputStream? = null

    @Volatile
    private var inputStream: DataInputStream? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var keepAliveThread: Thread? = null

    val isConnected: Boolean
        get() = connectedFlag.get() && !closedFlag.get()

    /**
     * Kết nối đồng bộ TLS tới broker và chờ gói CONNACK hợp lệ.
     * Ném IOException nếu không nối được để tầng gọi tự chuyển sang broker dự phòng.
     */
    @Throws(IOException::class)
    fun connect(
        onMessage: (topic: String, payload: ByteArray) -> Unit,
        onDisconnected: (reason: String) -> Unit
    ) {
        if (closedFlag.get()) throw IOException("Client already closed")
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = (factory.createSocket() as SSLSocket).apply {
            tcpNoDelay = true
            soTimeout = connectTimeoutMs
            try {
                val params = sslParameters
                params.serverNames = listOf(SNIHostName(broker.host))
                params.endpointIdentificationAlgorithm = "HTTPS"
                sslParameters = params
            } catch (_: Exception) {
            }
        }
        try {
            sslSocket.connect(InetSocketAddress(broker.host, broker.tlsPort), connectTimeoutMs)
            sslSocket.startHandshake()

            val out = BufferedOutputStream(sslSocket.outputStream, 4096)
            val inp = DataInputStream(BufferedInputStream(sslSocket.inputStream, 8192))

            val clientId = "rvn-${RemotePadCrypto.generateNonceHex(6)}"
            val connectPacket = MqttWireCodec.encodeConnect(clientId, keepAliveSec)
            out.write(connectPacket)
            out.flush()

            val firstHeader = inp.readUnsignedByte()
            val packetType = (firstHeader ushr 4) and 0x0F
            val remLen = MqttWireCodec.readRemainingLength(inp)
            val body = ByteArray(remLen)
            inp.readFully(body)
            if (packetType != MqttWireCodec.TYPE_CONNACK || remLen < 2 || body[1].toInt() != 0) {
                throw IOException("MQTT CONNACK rejected (type=$packetType)")
            }

            sslSocket.soTimeout = (keepAliveSec * 2 + 6) * 1000
            socket = sslSocket
            outputStream = out
            inputStream = inp
            connectedFlag.set(true)

            startReaderLoop(inp, onMessage, onDisconnected)
            startKeepAliveLoop()
        } catch (e: Exception) {
            runCatching { sslSocket.close() }
            connectedFlag.set(false)
            if (e is IOException) throw e
            throw IOException(e.message ?: "TLS connect failed", e)
        }
    }

    fun subscribe(topic: String): Boolean {
        if (!isConnected) return false
        val pid = nextPacketId()
        return writePacket(MqttWireCodec.encodeSubscribe(pid, topic))
    }

    fun unsubscribe(topic: String): Boolean {
        if (!isConnected) return false
        val pid = nextPacketId()
        return writePacket(MqttWireCodec.encodeUnsubscribe(pid, topic))
    }

    fun publishQos0(topic: String, payload: ByteArray): Boolean {
        if (!isConnected) return false
        return writePacket(MqttWireCodec.encodePublishQos0(topic, payload))
    }

    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        val wasConnected = connectedFlag.getAndSet(false)
        if (wasConnected) {
            runCatching {
                synchronized(writeLock) {
                    outputStream?.write(MqttWireCodec.encodeDisconnect())
                    outputStream?.flush()
                }
            }
        }
        keepAliveThread?.interrupt()
        readerThread?.interrupt()
        runCatching { socket?.close() }
        socket = null
        outputStream = null
        inputStream = null
    }

    private fun nextPacketId(): Int {
        val next = packetIdGen.getAndIncrement() and 0xFFFF
        return if (next == 0) 1 else next
    }

    private fun writePacket(packet: ByteArray): Boolean {
        synchronized(writeLock) {
            val out = outputStream ?: return false
            if (!connectedFlag.get() || closedFlag.get()) return false
            return try {
                out.write(packet)
                out.flush()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun startReaderLoop(
        inp: DataInputStream,
        onMessage: (String, ByteArray) -> Unit,
        onDisconnected: (String) -> Unit
    ) {
        val thread = Thread({
            var reason = "CLOSED"
            try {
                while (connectedFlag.get() && !closedFlag.get()) {
                    val header = try {
                        inp.readUnsignedByte()
                    } catch (_: EOFException) {
                        reason = "EOF"
                        break
                    }
                    val packetType = (header ushr 4) and 0x0F
                    val flags = header and 0x0F
                    val remLen = MqttWireCodec.readRemainingLength(inp)
                    if (remLen < 0 || remLen > MAX_PACKET_BYTES) {
                        reason = "OVERSIZE"
                        break
                    }
                    val body = ByteArray(remLen)
                    if (remLen > 0) {
                        inp.readFully(body)
                    }
                    when (packetType) {
                        MqttWireCodec.TYPE_PUBLISH -> {
                            val qos = (flags ushr 1) and 0x03
                            val parsed = MqttWireCodec.decodePublishBody(body, qos)
                            if (parsed != null) {
                                runCatching { onMessage(parsed.first, parsed.second) }
                            }
                        }
                        MqttWireCodec.TYPE_PINGRESP,
                        MqttWireCodec.TYPE_SUBACK,
                        MqttWireCodec.TYPE_UNSUBACK -> Unit
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                if (!closedFlag.get()) {
                    reason = "IO_ERROR"
                }
            } finally {
                val notify = connectedFlag.getAndSet(false) && !closedFlag.get()
                runCatching { socket?.close() }
                if (notify) {
                    runCatching { onDisconnected(reason) }
                }
            }
        }, "RavnoMqttReader-${broker.id}").apply {
            isDaemon = true
        }
        readerThread = thread
        thread.start()
    }

    private fun startKeepAliveLoop() {
        val intervalMs = (keepAliveSec * 700L).coerceAtLeast(8_000L)
        val thread = Thread({
            try {
                while (connectedFlag.get() && !closedFlag.get()) {
                    Thread.sleep(intervalMs)
                    if (!connectedFlag.get() || closedFlag.get()) break
                    if (!writePacket(MqttWireCodec.encodePingReq())) {
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "RavnoMqttPing-${broker.id}").apply {
            isDaemon = true
        }
        keepAliveThread = thread
        thread.start()
    }

    companion object {
        private const val MAX_PACKET_BYTES = 64 * 1024
    }
}

/**
 * Bộ mã hóa / giải mã gói MQTT 3.1.1 thuần (tách riêng để kiểm thử đơn vị dễ dàng).
 */
object MqttWireCodec {
    const val TYPE_CONNECT = 1
    const val TYPE_CONNACK = 2
    const val TYPE_PUBLISH = 3
    const val TYPE_SUBSCRIBE = 8
    const val TYPE_SUBACK = 9
    const val TYPE_UNSUBSCRIBE = 10
    const val TYPE_UNSUBACK = 11
    const val TYPE_PINGREQ = 12
    const val TYPE_PINGRESP = 13
    const val TYPE_DISCONNECT = 14

    fun encodeConnect(clientId: String, keepAliveSec: Int): ByteArray {
        val body = ByteArrayOutputStream()
        // Protocol Name "MQTT"
        writeUtf8(body, "MQTT")
        // Protocol Level 4 (MQTT 3.1.1)
        body.write(4)
        // Connect Flags: Clean Session (0x02)
        body.write(0x02)
        // Keep Alive (big endian 16-bit)
        body.write((keepAliveSec ushr 8) and 0xFF)
        body.write(keepAliveSec and 0xFF)
        // Client Identifier
        writeUtf8(body, clientId)
        return frame(TYPE_CONNECT shl 4, body.toByteArray())
    }

    fun encodeSubscribe(packetId: Int, topic: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeUtf8(body, topic)
        body.write(0x00) // Requested QoS 0
        return frame((TYPE_SUBSCRIBE shl 4) or 0x02, body.toByteArray())
    }

    fun encodeUnsubscribe(packetId: Int, topic: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeUtf8(body, topic)
        return frame((TYPE_UNSUBSCRIBE shl 4) or 0x02, body.toByteArray())
    }

    fun encodePublishQos0(topic: String, payload: ByteArray): ByteArray {
        val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
        val bodyLen = 2 + topicBytes.size + payload.size
        val out = ByteArrayOutputStream(bodyLen + 5)
        out.write(TYPE_PUBLISH shl 4) // QoS 0, DUP 0, RETAIN 0
        writeRemainingLength(out, bodyLen)
        out.write((topicBytes.size ushr 8) and 0xFF)
        out.write(topicBytes.size and 0xFF)
        out.write(topicBytes)
        out.write(payload)
        return out.toByteArray()
    }

    fun encodePingReq(): ByteArray = byteArrayOf((TYPE_PINGREQ shl 4).toByte(), 0x00)

    fun encodeDisconnect(): ByteArray = byteArrayOf((TYPE_DISCONNECT shl 4).toByte(), 0x00)

    fun decodePublishBody(body: ByteArray, qos: Int): Pair<String, ByteArray>? {
        if (body.size < 2) return null
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val payloadOffset = 2 + topicLen + (if (qos > 0) 2 else 0)
        if (payloadOffset > body.size) return null
        val topic = String(body, 2, topicLen, StandardCharsets.UTF_8)
        val payload = body.copyOfRange(payloadOffset, body.size)
        return topic to payload
    }

    @Throws(IOException::class)
    fun readRemainingLength(inp: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        var count = 0
        while (true) {
            val encodedByte = inp.readUnsignedByte()
            value += (encodedByte and 0x7F) * multiplier
            if ((encodedByte and 0x80) == 0) break
            multiplier *= 128
            count++
            if (count > 4) throw IOException("Malformed MQTT remaining length")
        }
        return value
    }

    fun writeRemainingLength(out: ByteArrayOutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 0x80
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun frame(firstByte: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(firstByte)
        writeRemainingLength(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }
}
