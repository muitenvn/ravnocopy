package com.muitenvn.cloudplay.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class RemotePadRelayTest {

    @Test
    fun pairingCodeEncodesBrokerSlotAndValidates() {
        repeat(20) {
            val code0 = RemotePadRelayCrypto.generatePairingCode(0)
            assertTrue(RemotePadRelayCrypto.isValidCode(code0))
            assertEquals(0, RemotePadRelayCrypto.extractBrokerSlot(code0))
            assertEquals(11, code0.length) // XXXXX-XXXXX
            assertEquals('-', code0[5])
            assertTrue(RemotePadRelayCrypto.normalizeCode(code0).all { it in '0'..'9' })

            val code1 = RemotePadRelayCrypto.generatePairingCode(1)
            assertTrue(RemotePadRelayCrypto.isValidCode(code1))
            assertEquals(1, RemotePadRelayCrypto.extractBrokerSlot(code1))
            assertEquals(code1, RemotePadRelayCrypto.formatCode(code1.replace("-", " ")))
        }
        assertTrue(RemotePadRelayCrypto.isValidCode("4829103756"))
        assertTrue(RemotePadRelayCrypto.isValidCode("48291-03756"))
        assertTrue(RemotePadRelayCrypto.isValidCode("48291 03756"))
        assertFalse(RemotePadRelayCrypto.isValidCode("ABCD-EFGH"))   // letters rejected
        assertFalse(RemotePadRelayCrypto.isValidCode("12345-6789A")) // letter rejected
        assertFalse(RemotePadRelayCrypto.isValidCode("12345-6789"))  // too short (9 digits)
        assertFalse(RemotePadRelayCrypto.isValidCode("123456-78901")) // too long (11 digits)
    }

    @Test
    fun ecdhAndAesGcmRoundTripAndTamperRejected() {
        val code = RemotePadRelayCrypto.generatePairingCode(0)
        val pairKey = RemotePadRelayCrypto.derivePairKey(code)
        assertEquals(32, pairKey.size)
        assertEquals(
            RemotePadRelayCrypto.derivePairTopicId(code),
            RemotePadRelayCrypto.derivePairTopicIdFromPairKey(pairKey)
        )

        val phoneKp = RemotePadRelayCrypto.generateEcdhKeyPair()
        val tvKp = RemotePadRelayCrypto.generateEcdhKeyPair()
        val cNonce = RemotePadCrypto.generateNonceHex(16)
        val tNonce = RemotePadCrypto.generateNonceHex(16)

        val hello = RelayHandshakeFrame.ClientHello(
            deviceId = "dev-123",
            deviceName = "Pixel | Pro = 1",
            clientNonceHex = cNonce,
            clientPubHex = RemotePadRelayCrypto.encodePublicKeyHex(phoneKp)
        )
        val encryptedHello = RemotePadRelayCrypto.encryptAesGcm(pairKey, RelayHandshakeCodec.serialize(hello))
        val decryptedHello = RemotePadRelayCrypto.decryptAesGcm(pairKey, encryptedHello)
        assertNotNull(decryptedHello)
        val parsedHello = RelayHandshakeCodec.parse(decryptedHello!!) as RelayHandshakeFrame.ClientHello
        assertEquals(hello, parsedHello)

        // Wrong code must fail GCM authentication
        val wrongKey = RemotePadRelayCrypto.derivePairKey(RemotePadRelayCrypto.generatePairingCode(1))
        assertNull(RemotePadRelayCrypto.decryptAesGcm(wrongKey, encryptedHello))

        // Tampered ciphertext must fail GCM authentication
        val tampered = encryptedHello.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(RemotePadRelayCrypto.decryptAesGcm(pairKey, tampered))

        // Both sides derive identical 256-bit AES session key via ECDH + HKDF
        val tvSessionKey = RemotePadRelayCrypto.deriveSessionAesKey(
            localPrivateKey = tvKp.private,
            peerPublicKeyHex = RemotePadRelayCrypto.encodePublicKeyHex(phoneKp),
            clientNonceHex = cNonce,
            tvNonceHex = tNonce
        )
        val phoneSessionKey = RemotePadRelayCrypto.deriveSessionAesKey(
            localPrivateKey = phoneKp.private,
            peerPublicKeyHex = RemotePadRelayCrypto.encodePublicKeyHex(tvKp),
            clientNonceHex = cNonce,
            tvNonceHex = tNonce
        )
        assertNotNull(tvSessionKey)
        assertNotNull(phoneSessionKey)
        assertArrayEquals(tvSessionKey, phoneSessionKey)
        assertEquals(32, tvSessionKey!!.size)
        assertNotEquals(
            RemotePadCrypto.bytesToHex(pairKey),
            RemotePadCrypto.bytesToHex(tvSessionKey)
        )
    }

    @Test
    fun mqttWireCodecHandlesMultiByteLengthAndPublishRoundTrip() {
        val topic = "ravno/p2/s/0123456789abcdef0123456789abcdef/c2t"
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        val packet = MqttWireCodec.encodePublishQos0(topic, payload)

        val inp = DataInputStream(ByteArrayInputStream(packet))
        val header = inp.readUnsignedByte()
        assertEquals(MqttWireCodec.TYPE_PUBLISH, (header ushr 4) and 0x0F)
        val remLen = MqttWireCodec.readRemainingLength(inp)
        val body = ByteArray(remLen)
        inp.readFully(body)

        val decoded = MqttWireCodec.decodePublishBody(body, qos = 0)
        assertNotNull(decoded)
        assertEquals(topic, decoded!!.first)
        assertArrayEquals(payload, decoded.second)

        // Verify Remaining Length roundtrip across boundary values
        val testLengths = intArrayOf(0, 127, 128, 16383, 16384, 65535)
        for (len in testLengths) {
            val out = ByteArrayOutputStream()
            MqttWireCodec.writeRemainingLength(out, len)
            val parsedLen = MqttWireCodec.readRemainingLength(DataInputStream(ByteArrayInputStream(out.toByteArray())))
            assertEquals(len, parsedLen)
        }
    }

    @Test
    fun quickTokenFrameRoundTripAndHmacVerify() {
        val quickToken = RemotePadCrypto.generateNonceHex(16)
        val nonce = RemotePadCrypto.generateNonceHex(16)
        val hmac = RemotePadRelayCrypto.computeQuickTokenProofHmac(quickToken, nonce, "phone-9")
        assertTrue(RemotePadRelayCrypto.verifyQuickTokenProofHmac(quickToken, nonce, "phone-9", hmac))
        assertFalse(RemotePadRelayCrypto.verifyQuickTokenProofHmac(quickToken, nonce, "phone-other", hmac))

        val frame = RemoteControlFrame.AuthWithQuickToken(
            deviceId = "phone-9",
            deviceName = "My Phone",
            quickTokenHmacHex = hmac
        )
        val serialized = RemoteControlCodec.serialize(frame)
        assertEquals(frame, RemoteControlCodec.parse(serialized))
    }
}
