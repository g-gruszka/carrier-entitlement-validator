package com.carrier.entitlement.validator

import com.carrier.entitlement.validator.sim.EapAkaEngine
import org.junit.Assert.*
import org.junit.Test

class EapAkaEngineTest {

    @Test
    fun testParseRealServerChallenge() {
        // Paquete real capturado en vivo del Entitlement Server de laboratorio (Ronda 1)
        val serverEapPacket = "010100441701000001050000d2da577ecc76098e421683f532e0d9c702050000fe6b5885e2448000fd4e9ccfe46093880b05000082e54794e8127fb175b57f1dc98332b7"

        val challenge = EapAkaEngine.parseEapChallenge(serverEapPacket)

        assertEquals(1, challenge.eapId)
        assertEquals("d2da577ecc76098e421683f532e0d9c7", challenge.randHex)
        assertEquals("fe6b5885e2448000fd4e9ccfe4609388", challenge.autnHex)
        assertEquals("82e54794e8127fb175b57f1dc98332b7", challenge.macHex)
    }

    @Test
    fun testBuildEapResponse() {
        val eapId = 1
        val dummyRes = "0011223344556677" // 8 bytes (64 bits)
        val macHex = "82e54794e8127fb175b57f1dc98332b7"

        val responseHex = EapAkaEngine.buildEapResponse(eapId, dummyRes, macHex)

        // Verificaciones básicas del paquete RFC 4187
        assertTrue(responseHex.startsWith("0201")) // Code 2 (Response), ID 1
        assertTrue(responseHex.contains("17010000")) // Type 23, Subtype 1
        assertTrue(responseHex.contains("03030040")) // AT_RES, length 3 words (12B), 64 bits (0x0040)
        assertTrue(responseHex.contains(dummyRes)) // Contiene el RES
    }
}
