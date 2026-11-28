package com.muitenvn.cloudplay.protocol

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Danh sách máy chủ chuyển tiếp TLS công cộng miễn phí (MQTT 3.1.1 trên cổng 8883).
 * Vị trí máy chủ (slot 0 hoặc 1) được mã hóa ngầm vào chữ số đầu (chẵn/lẻ) của mã ghép đôi 10 số
 * để điện thoại kết nối thẳng đúng máy chủ TV đang lắng nghe ngay lần đầu.
 */
data class RemoteRelayBroker(
    val slot: Int,
    val id: String,
    val host: String,
    val tlsPort: Int = 8883
)

object RemoteRelayBrokers {
    val PRIMARY = RemoteRelayBroker(slot = 0, id = "emqx", host = "broker.emqx.io", tlsPort = 8883)
    val BACKUP = RemoteRelayBroker(slot = 1, id = "hivemq", host = "broker.hivemq.com", tlsPort = 8883)

    val ALL: List<RemoteRelayBroker> = listOf(PRIMARY, BACKUP)

    fun bySlot(slot: Int): RemoteRelayBroker = ALL.firstOrNull { it.slot == slot } ?: PRIMARY
}

/**
 * Mật mã học đầu-cuối (End-to-End) cho chế độ kết nối Ravno Pad ngoài mạng LAN:
 * - Mã ghép đôi 10 chữ số (XXXXX-XXXXX), nhập nhanh hoàn toàn bằng bàn phím số (0–9).
 * - Khóa bắt tay và chủ đề (topic) trên máy chủ trung gian đều suy từ PBKDF2-HMAC-SHA256
 *   (60.000 vòng) nên không lộ mã và chống dò ngoại tuyến trong thời hạn 15 phút.
 * - ECDH P-256 tạm thời -> HKDF-SHA256 tạo khóa phiên AES-256-GCM riêng cho từng buổi chơi (Forward Secrecy).
 * - Không ghi log mã ghép đôi, khóa phiên hoặc dữ liệu nhạy cảm.
 */
object RemotePadRelayCrypto {
    // 10 chữ số thuần túy (0–9), nhập nhanh bằng bàn phím số trên điện thoại
    private const val CODE_ALPHABET = "0123456789"
    const val CODE_LENGTH = 10
    private const val PBKDF2_ITERATIONS = 60_000
    private const val AES_KEY_BITS = 256
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    const val PACKET_TYPE_INPUT: Byte = 0x01
    const val PACKET_TYPE_CONTROL: Byte = 0x02

    private val secureRandom = SecureRandom()

    /**
     * Sinh mã ghép đôi 10 chữ số dạng "XXXXX-XXXXX" nhúng ngầm slot máy chủ
     * (chữ số đầu chẵn = slot 0 EMQX, chữ số đầu lẻ = slot 1 HiveMQ).
     */
    fun generatePairingCode(brokerSlot: Int): String {
        val targetParity = if (brokerSlot == 1) 1 else 0
        val chars = CharArray(CODE_LENGTH)
        repeat(16) {
            val firstIdx = (secureRandom.nextInt(CODE_ALPHABET.length / 2) * 2) + targetParity
            chars[0] = CODE_ALPHABET[firstIdx]
            for (i in 1 until CODE_LENGTH) {
                chars[i] = CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]
            }
            val candidate = String(chars)
            if (!isTrivialDigitPattern(candidate)) {
                return formatCode(candidate)
            }
        }
        return formatCode(String(chars))
    }

    private fun isTrivialDigitPattern(digits: String): Boolean {
        if (digits.length != CODE_LENGTH) return true
        if (digits.all { it == digits[0] }) return true
        var allInc = true
        var allDec = true
        for (i in 1 until digits.length) {
            val prev = digits[i - 1] - '0'
            val cur = digits[i] - '0'
            if ((prev + 1) % 10 != cur) allInc = false
            if ((prev + 9) % 10 != cur) allDec = false
        }
        return allInc || allDec
    }

    fun normalizeCode(raw: String): String {
        val sb = StringBuilder(CODE_LENGTH)
        for (c in raw.trim()) {
            if (c == '-' || c == '–' || c == '—' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue
            sb.append(c)
        }
        return sb.toString()
    }

    fun formatCode(raw: String): String {
        val norm = normalizeCode(raw)
        if (norm.length <= 5) return norm
        return "${norm.substring(0, 5)}-${norm.substring(5)}"
    }

    fun isValidCode(raw: String): Boolean {
        val norm = normalizeCode(raw)
        if (norm.length != CODE_LENGTH) return false
        for (c in norm) {
            if (c !in '0'..'9') return false
        }
        return true
    }

    fun extractBrokerSlot(raw: String): Int {
        val norm = normalizeCode(raw)
        if (norm.isEmpty()) return 0
        val idx = CODE_ALPHABET.indexOf(norm[0])
        return if (idx >= 0 && (idx and 1) == 1) 1 else 0
    }

    fun derivePairTopicIdFromPairKey(pairKey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("ravno-pad-v3-topic|".toByteArray(StandardCharsets.UTF_8))
        md.update(pairKey)
        val digest = md.digest()
        return RemotePadCrypto.bytesToHex(digest.copyOfRange(0, 16))
    }

    fun derivePairTopicId(rawCode: String): String {
        return derivePairTopicIdFromPairKey(derivePairKey(rawCode))
    }

    fun pairClientToTvTopic(pairTopicId: String): String = "ravno/p3/p/$pairTopicId/c2t"
    fun pairTvToClientTopic(pairTopicId: String): String = "ravno/p3/p/$pairTopicId/t2c"
    fun sessionClientToTvTopic(sessionTopicHex: String): String = "ravno/p3/s/$sessionTopicHex/c2t"
    fun sessionTvToClientTopic(sessionTopicHex: String): String = "ravno/p3/s/$sessionTopicHex/t2c"

    fun generateSessionTopicHex(): String = RemotePadCrypto.generateNonceHex(16)

    fun derivePairKey(rawCode: String): ByteArray {
        val norm = normalizeCode(rawCode)
        val salt = "ravno-pad-v3-kdf-salt".toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(norm.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun encodePublicKeyHex(keyPair: KeyPair): String {
        return RemotePadCrypto.bytesToHex(keyPair.public.encoded)
    }

    fun deriveSessionAesKey(
        localPrivateKey: PrivateKey,
        peerPublicKeyHex: String,
        clientNonceHex: String,
        tvNonceHex: String
    ): ByteArray? {
        return try {
            val peerBytes = RemotePadCrypto.hexToBytes(peerPublicKeyHex)
            if (peerBytes.isEmpty()) return null
            val peerPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerBytes))
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(localPrivateKey)
            ka.doPhase(peerPub, true)
            val sharedSecret = ka.generateSecret()

            // HKDF-SHA256 Extract + Expand (32 bytes)
            val salt = "ravno-p2-salt|${clientNonceHex.lowercase()}|${tvNonceHex.lowercase()}"
                .toByteArray(StandardCharsets.UTF_8)
            val prkMac = Mac.getInstance("HmacSHA256")
            prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = prkMac.doFinal(sharedSecret)

            val okmMac = Mac.getInstance("HmacSHA256")
            okmMac.init(SecretKeySpec(prk, "HmacSHA256"))
            okmMac.update("ravno-p2-aes256-session".toByteArray(StandardCharsets.UTF_8))
            okmMac.update(0x01.toByte())
            okmMac.doFinal()
        } catch (_: Exception) {
            null
        }
    }

    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val cipherText = cipher.doFinal(plaintext)
        val out = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
        return out
    }

    fun decryptAesGcm(key: ByteArray, envelope: ByteArray, aad: ByteArray? = null): ByteArray? {
        if (envelope.size <= GCM_IV_BYTES + 16) return null
        return try {
            val iv = envelope.copyOfRange(0, GCM_IV_BYTES)
            val body = envelope.copyOfRange(GCM_IV_BYTES, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            cipher.doFinal(body)
        } catch (_: Exception) {
            null
        }
    }

    fun wrapInputPacket(udpPacket48: ByteArray): ByteArray {
        val out = ByteArray(1 + udpPacket48.size)
        out[0] = PACKET_TYPE_INPUT
        System.arraycopy(udpPacket48, 0, out, 1, udpPacket48.size)
        return out
    }

    fun wrapControlFrame(frame: RemoteControlFrame): ByteArray {
        val lineBytes = RemoteControlCodec.serialize(frame).toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(1 + lineBytes.size)
        out[0] = PACKET_TYPE_CONTROL
        System.arraycopy(lineBytes, 0, out, 1, lineBytes.size)
        return out
    }

    fun computeQuickTokenProofHmac(quickTokenHex: String, tvNonceHex: String, clientDeviceId: String): String {
        val clean = quickTokenHex.trim().lowercase()
        val message = "CLOUDPLAY_QUICK_V2|$tvNonceHex|${clientDeviceId.trim()}"
        return RemotePadCrypto.hmacSha256Hex(key = clean, message = message)
    }

    fun verifyQuickTokenProofHmac(
        expectedQuickTokenHex: String,
        tvNonceHex: String,
        clientDeviceId: String,
        candidateHmacHex: String
    ): Boolean {
        val expected = computeQuickTokenProofHmac(expectedQuickTokenHex, tvNonceHex, clientDeviceId)
        return RemotePadCrypto.constantTimeHexEquals(expected, candidateHmacHex)
    }
}

/**
 * Khung bắt tay trên kênh ghép đôi từ xa (luôn được mã hóa AES-256-GCM bằng khóa suy từ mã 10 số).
 */
sealed class RelayHandshakeFrame {
    data class ClientHello(
        val protocolVersion: Int = RemotePadProtocol.PROTOCOL_VERSION,
        val deviceId: String,
        val deviceName: String,
        val clientNonceHex: String,
        val clientPubHex: String
    ) : RelayHandshakeFrame()

    data class TvWelcome(
        val protocolVersion: Int = RemotePadProtocol.PROTOCOL_VERSION,
        val tvId: String,
        val tvName: String,
        val tvNonceHex: String,
        val tvPubHex: String,
        val sessionTopicHex: String,
        val udpSessionTokenHex: String,
        val playerIndex: Int = 1,
        val lanHost: String = "",
        val lanTcpPort: Int = RemotePadProtocol.DEFAULT_TCP_CONTROL_PORT,
        val lanUdpPort: Int = RemotePadProtocol.DEFAULT_UDP_INPUT_PORT,
        val lanQuickTokenHex: String = "",
        val activeGameKey: String = "",
        val activeGameTitle: String = "",
        val activePresetName: String = ""
    ) : RelayHandshakeFrame()

    data class TvReject(
        val protocolVersion: Int = RemotePadProtocol.PROTOCOL_VERSION,
        val reason: AuthFailReason,
        val remainingAttempts: Int = 0,
        val lockoutRemainingSec: Int = 0
    ) : RelayHandshakeFrame()
}

object RelayHandshakeCodec {
    private fun escape(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("=", "\\e")
            .replace("\n", " ")
            .replace("\r", " ")
    }

    private fun unescape(escaped: String): String {
        val sb = StringBuilder(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c == '\\' && i + 1 < escaped.length) {
                when (val next = escaped[i + 1]) {
                    '\\' -> sb.append('\\')
                    'p' -> sb.append('|')
                    'e' -> sb.append('=')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun serialize(frame: RelayHandshakeFrame): ByteArray {
        val text = when (frame) {
            is RelayHandshakeFrame.ClientHello ->
                "R_HELLO|v=${frame.protocolVersion}|id=${escape(frame.deviceId)}|name=${escape(frame.deviceName)}|cn=${escape(frame.clientNonceHex)}|cpk=${escape(frame.clientPubHex)}"
            is RelayHandshakeFrame.TvWelcome ->
                "R_WELCOME|v=${frame.protocolVersion}|id=${escape(frame.tvId)}|name=${escape(frame.tvName)}|tn=${escape(frame.tvNonceHex)}|tpk=${escape(frame.tvPubHex)}|st=${escape(frame.sessionTopicHex)}|uk=${escape(frame.udpSessionTokenHex)}|p=${frame.playerIndex}|lh=${escape(frame.lanHost)}|ltp=${frame.lanTcpPort}|lup=${frame.lanUdpPort}|lqt=${escape(frame.lanQuickTokenHex)}|gk=${escape(frame.activeGameKey)}|gt=${escape(frame.activeGameTitle)}|pre=${escape(frame.activePresetName)}"
            is RelayHandshakeFrame.TvReject ->
                "R_REJECT|v=${frame.protocolVersion}|err=${frame.reason.name}|rem=${frame.remainingAttempts}|lock=${frame.lockoutRemainingSec}"
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    fun parse(bytes: ByteArray): RelayHandshakeFrame? {
        val line = runCatching { String(bytes, StandardCharsets.UTF_8).trim() }.getOrNull() ?: return null
        if (line.isEmpty()) return null
        val parts = splitUnescaped(line, '|')
        if (parts.isEmpty()) return null
        val type = parts[0].uppercase()
        val map = LinkedHashMap<String, String>()
        for (i in 1 until parts.size) {
            val kv = splitUnescaped(parts[i], '=', limit = 2)
            if (kv.size == 2) {
                map[unescape(kv[0])] = unescape(kv[1])
            }
        }
        return when (type) {
            "R_HELLO" -> RelayHandshakeFrame.ClientHello(
                protocolVersion = map["v"]?.toIntOrNull() ?: RemotePadProtocol.PROTOCOL_VERSION,
                deviceId = map["id"].orEmpty(),
                deviceName = map["name"].orEmpty().ifBlank { "Ravno Pad" },
                clientNonceHex = map["cn"].orEmpty(),
                clientPubHex = map["cpk"].orEmpty()
            )
            "R_WELCOME" -> RelayHandshakeFrame.TvWelcome(
                protocolVersion = map["v"]?.toIntOrNull() ?: RemotePadProtocol.PROTOCOL_VERSION,
                tvId = map["id"].orEmpty(),
                tvName = map["name"].orEmpty().ifBlank { "Ravno TV" },
                tvNonceHex = map["tn"].orEmpty(),
                tvPubHex = map["tpk"].orEmpty(),
                sessionTopicHex = map["st"].orEmpty(),
                udpSessionTokenHex = map["uk"].orEmpty(),
                playerIndex = map["p"]?.toIntOrNull() ?: 1,
                lanHost = map["lh"].orEmpty(),
                lanTcpPort = map["ltp"]?.toIntOrNull() ?: RemotePadProtocol.DEFAULT_TCP_CONTROL_PORT,
                lanUdpPort = map["lup"]?.toIntOrNull() ?: RemotePadProtocol.DEFAULT_UDP_INPUT_PORT,
                lanQuickTokenHex = map["lqt"].orEmpty(),
                activeGameKey = map["gk"].orEmpty(),
                activeGameTitle = map["gt"].orEmpty(),
                activePresetName = map["pre"].orEmpty()
            )
            "R_REJECT" -> RelayHandshakeFrame.TvReject(
                protocolVersion = map["v"]?.toIntOrNull() ?: RemotePadProtocol.PROTOCOL_VERSION,
                reason = runCatching { AuthFailReason.valueOf(map["err"].orEmpty()) }
                    .getOrDefault(AuthFailReason.WRONG_PIN),
                remainingAttempts = map["rem"]?.toIntOrNull() ?: 0,
                lockoutRemainingSec = map["lock"]?.toIntOrNull() ?: 0
            )
            else -> null
        }
    }

    private fun splitUnescaped(input: String, delimiter: Char, limit: Int = Int.MAX_VALUE): List<String> {
        val result = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                cur.append(c).append(input[i + 1])
                i += 2
                continue
            }
            if (c == delimiter && result.size + 1 < limit) {
                result.add(cur.toString())
                cur.setLength(0)
            } else {
                cur.append(c)
            }
            i++
        }
        result.add(cur.toString())
        return result
    }
}
