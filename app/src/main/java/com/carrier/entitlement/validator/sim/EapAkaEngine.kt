package com.carrier.entitlement.validator.sim

import android.util.Base64
import com.carrier.entitlement.validator.data.model.EapAuthResult
import com.carrier.entitlement.validator.data.model.EapExtractedChallenge
import java.io.ByteArrayOutputStream

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
     * Parsea la respuesta Base64 de TelephonyManager.getIccAuthentication (3GPP TS 31.102):
     * Tag 0xDB (Auth 3G exitoso):
     * [0xDB] [Len] [Len RES] [RES] [Len CK] [CK] [Len IK] [IK]
     * Tag 0xDC (Fallo sincronización):
     * [0xDC] [Len] [Len AUTS] [AUTS]
     */
    fun parseIccAuthResponse(base64Response: String): EapAuthResult {
        val bytes = Base64.decode(base64Response.trim(), Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "Respuesta de USIM vacía" }

        val tag = bytes[0].toInt() and 0xFF
        if (tag == 0xDB) {
            // Autenticación exitosa
            var offset = 2 // Saltamos Tag y Length total
            val resLen = bytes[offset].toInt() and 0xFF
            offset += 1
            val resBytes = bytes.copyOfRange(offset, offset + resLen)
            val resHex = bytesToHex(resBytes)
            return EapAuthResult(resHex = resHex, source = "USIM_HARDWARE")
        } else if (tag == 0xDC) {
            // Sincronización requerida (AUTS)
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
            // Formato directo sin Tag TLV
            val resHex = bytesToHex(bytes)
            return EapAuthResult(resHex = resHex, source = "USIM_HARDWARE")
        }
    }

    /**
     * Construye el paquete EAP-Response/AKA-Challenge en formato Hexadecimal (RFC 4187).
     * [Code=2 (Response)] [ID] [Length] [Type=23] [Subtype=1] [Reserved=0x0000]
     * Atributos:
     * - AT_RES (Tipo 3): [Type 0x03] [Len words] [RES bits (2B)] [RES bytes] [padding]
     * - AT_MAC (Tipo 11): 16 bytes MAC
     */
    fun buildEapResponse(eapId: Int, resHex: String, macHex: String = ""): String {
        val resBytes = hexToBytes(resHex)
        val resBits = resBytes.size * 8

        val attrResBos = ByteArrayOutputStream()
        attrResBos.write(3) // Atributo AT_RES

        // Longitud en palabras de 4 bytes
        // Header (1 byte type + 1 byte len + 2 bytes bit-len) + resBytes.size + padding
        val unpaddedPayloadLen = 4 + resBytes.size
        val paddingNeeded = (4 - (unpaddedPayloadLen % 4)) % 4
        val totalAttrBytes = unpaddedPayloadLen + paddingNeeded
        val lengthInWords = totalAttrBytes / 4

        attrResBos.write(lengthInWords)
        attrResBos.write((resBits shr 8) and 0xFF)
        attrResBos.write(resBits and 0xFF)
        attrResBos.write(resBytes)
        for (i in 0 until paddingNeeded) {
            attrResBos.write(0)
        }
        val attrRes = attrResBos.toByteArray()

        // AT_MAC (Tipo 11, length 5 words = 20 bytes)
        val attrMacBos = ByteArrayOutputStream()
        attrMacBos.write(11) // Tipo AT_MAC
        attrMacBos.write(5)  // Longitud = 5 palabras
        attrMacBos.write(0)  // Reservado
        attrMacBos.write(0)  // Reservado
        val macBytes = if (macHex.isNotEmpty()) hexToBytes(macHex) else ByteArray(16)
        attrMacBos.write(macBytes.copyOf(16))
        val attrMac = attrMacBos.toByteArray()

        val eapPayloadBos = ByteArrayOutputStream()
        eapPayloadBos.write(23) // Type: EAP-AKA
        eapPayloadBos.write(1)  // Subtype: AKA-Challenge
        eapPayloadBos.write(0)  // Reserved
        eapPayloadBos.write(0)  // Reserved
        eapPayloadBos.write(attrRes)
        eapPayloadBos.write(attrMac)

        val eapPayload = eapPayloadBos.toByteArray()
        val totalEapLength = 4 + eapPayload.size // 4 bytes EAP header + payload

        val finalEapBos = ByteArrayOutputStream()
        finalEapBos.write(2) // Code: Response
        finalEapBos.write(eapId and 0xFF)
        finalEapBos.write((totalEapLength shr 8) and 0xFF)
        finalEapBos.write(totalEapLength and 0xFF)
        finalEapBos.write(eapPayload)

        return bytesToHex(finalEapBos.toByteArray())
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
