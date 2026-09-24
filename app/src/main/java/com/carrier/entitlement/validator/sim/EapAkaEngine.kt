package com.carrier.entitlement.validator.sim

import android.util.Base64
import com.carrier.entitlement.validator.data.model.EapAuthResult
import com.carrier.entitlement.validator.data.model.EapExtractedChallenge
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EapAkaEngine {

    /**
     * Parsea un paquete EAP-Request/AKA-Challenge en formato Hexadecimal (RFC 4187).
     * Devuelve el EAP Identifier, RAND (16 bytes), AUTN (16 bytes) y MAC (16 bytes).
     */
    fun parseEapChallenge(hexPacket: String): EapExtractedChallenge {
        val cleanHex = hexPacket.trim().lowercase()
        val bytes = hexToBytes(cleanHex)
        require(bytes.size >= 8) { "Paquete EAP demasiado corto: ${bytes.size} bytes" }

        val code = bytes[0].toInt() and 0xFF
        val id = bytes[1].toInt() and 0xFF
        val length = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val type = bytes[4].toInt() and 0xFF
        val subtype = bytes[5].toInt() and 0xFF

        require(code == 1) { "No es un EAP-Request (Code=$code)" }
        require(type == 23) { "No es EAP-AKA (Type=$type)" }
        require(subtype == 1) { "No es AKA-Challenge (Subtype=$subtype)" }

        var offset = 8 // Cabecera EAP (4 bytes) + Type (1) + Subtype (1) + Reserved (2)
        var randHex = ""
        var autnHex = ""
        var macHex = ""

        while (offset + 2 <= bytes.size) {
            val attrType = bytes[offset].toInt() and 0xFF
            val attrLengthWords = bytes[offset + 1].toInt() and 0xFF
            val attrLengthBytes = attrLengthWords * 4
            if (attrLengthBytes <= 0 || offset + attrLengthBytes > bytes.size) break

            when (attrType) {
                1 -> { // AT_RAND: 4 bytes header (type, len, 2 res) + 16 bytes RAND
                    if (attrLengthBytes >= 20) {
                        randHex = bytesToHex(bytes.copyOfRange(offset + 4, offset + 20))
                    }
                }
                2 -> { // AT_AUTN: 4 bytes header + 16 bytes AUTN
                    if (attrLengthBytes >= 20) {
                        autnHex = bytesToHex(bytes.copyOfRange(offset + 4, offset + 20))
                    }
                }
                11 -> { // AT_MAC: 4 bytes header + 16 bytes MAC
                    if (attrLengthBytes >= 20) {
                        macHex = bytesToHex(bytes.copyOfRange(offset + 4, offset + 20))
                    }
                }
            }
            offset += attrLengthBytes
        }

        require(randHex.isNotEmpty() && autnHex.isNotEmpty()) {
            "El paquete EAP-AKA no contiene los atributos requeridos AT_RAND o AT_AUTN"
        }

        return EapExtractedChallenge(
            eapId = id,
            randHex = randHex,
            autnHex = autnHex,
            macHex = macHex
        )
    }

    /**
     * Calcula la respuesta RES (64 bits / 8 bytes) utilizando el algoritmo Milenage f2 (3GPP TS 35.206).
     */
    fun computeMilenageRes(kiHex: String, opcHex: String, randHex: String): String {
        val ki = hexToBytes(kiHex)
        val opc = hexToBytes(opcHex)
        val rand = hexToBytes(randHex)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(ki, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)

        // temp = AES(ki, rand ^ opc) ^ opc
        val randXorOpc = xor(rand, opc)
        val enc1 = cipher.doFinal(randXorOpc)
        val temp = xor(enc1, opc)

        // in2 = temp con el último byte XOR 1 (constante c2 para f2)
        val in2 = temp.copyOf()
        in2[15] = (in2[15].toInt() xor 1).toByte()

        // out2 = AES(ki, in2) ^ opc
        val enc2 = cipher.doFinal(in2)
        val out2 = xor(enc2, opc)

        // res = out2[8..15]
        val res = out2.copyOfRange(8, 16)
        return bytesToHex(res)
    }

    /**
     * Construye el paquete EAP-Response/AKA-Challenge completo con derivación de K_aut y AT_MAC (RFC 4187 §4.1).
     * Derivación estándar telco:
     * K_aut = SHA256(IMSI + RAND + RES)[0..15]
     * AT_MAC = HMAC-SHA1-128(K_aut, EAP-Response con MAC en ceros)
     */
    fun buildEapResponseWithCrypto(eapId: Int, imsi: String, randHex: String, resHex: String): String {
        val rand = hexToBytes(randHex)
        val res = hexToBytes(resHex)

        // 1. Derivar K_aut
        val md = MessageDigest.getInstance("SHA-256")
        md.update(imsi.toByteArray(Charsets.UTF_8))
        md.update(rand)
        md.update(res)
        val sha256 = md.digest()
        val kAut = sha256.copyOfRange(0, 16)

        // 2. Construir AT_RES (12 bytes = 3 palabras)
        val atRes = byteArrayOf(3, 3, 0, 64) + res

        // 3. Atributo AT_MAC dummy con ceros (20 bytes = 5 palabras)
        val atMacDummy = byteArrayOf(11, 5, 0, 0) + ByteArray(16)

        val payloadLen = 8 + atRes.size + atMacDummy.size // 36 bytes

        val header = byteArrayOf(
            2, // Code: Response
            eapId.toByte(),
            ((payloadLen shr 8) and 0xFF).toByte(),
            (payloadLen and 0xFF).toByte(),
            23, // Type: EAP-AKA
            1,  // Subtype: AKA-Challenge
            0, 0 // Reserved
        )

        val eapZeroed = header + atRes + atMacDummy

        // 4. Calcular HMAC-SHA1 sobre el paquete EAP con MAC en ceros
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(kAut, "HmacSHA1"))
        val macFull = mac.doFinal(eapZeroed)
        val macVal = macFull.copyOfRange(0, 16)

        // 5. Ensamblar paquete final con AT_MAC real
        val atMacReal = byteArrayOf(11, 5, 0, 0) + macVal
        val eapFinal = header + atRes + atMacReal

        return bytesToHex(eapFinal)
    }

    /**
     * Construye el payload Base64 para pasar a TelephonyManager.getIccAuthentication:
     * 3GPP TS 31.102 §7.1.2:
     * [Longitud RAND (1 byte = 0x10)] [RAND (16 bytes)] [Longitud AUTN (1 byte = 0x10)] [AUTN (16 bytes)]
     */
    fun buildIccAuthChallenge(randHex: String, autnHex: String): String {
        val rand = hexToBytes(randHex)
        val autn = hexToBytes(autnHex)
        require(rand.size == 16) { "RAND debe tener 16 bytes" }
        require(autn.size == 16) { "AUTN debe tener 16 bytes" }

        val bos = ByteArrayOutputStream()
        bos.write(16)
        bos.write(rand)
        bos.write(16)
        bos.write(autn)

        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Parsea la respuesta Base64 de TelephonyManager.getIccAuthentication (3GPP TS 31.102).
     */
    fun parseIccAuthResponse(base64Response: String): EapAuthResult {
        val bytes = Base64.decode(base64Response.trim(), Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "Respuesta de USIM vacía" }

        val tag = bytes[0].toInt() and 0xFF
        if (tag == 0xDB) {
            var offset = 2
            val resLen = bytes[offset].toInt() and 0xFF
            offset += 1
            val resBytes = bytes.copyOfRange(offset, offset + resLen)
            val resHex = bytesToHex(resBytes)
            return EapAuthResult(resHex = resHex, source = "USIM_HARDWARE")
        } else if (tag == 0xDC) {
            var offset = 2
            val autsLen = bytes[offset].toInt() and 0xFF
            offset += 1
            val autsBytes = bytes.copyOfRange(offset, offset + autsLen)
            val autsHex = bytesToHex(autsBytes)
            return EapAuthResult(
                resHex = "",
                autsHex = autsHex,
                isSyncFailure = true,
                source = "USIM_HARDWARE"
            )
        } else {
            val resHex = bytesToHex(bytes)
            return EapAuthResult(resHex = resHex, source = "USIM_HARDWARE")
        }
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val res = ByteArray(a.size)
        for (i in a.indices) {
            res[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return res
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(" ", "")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
