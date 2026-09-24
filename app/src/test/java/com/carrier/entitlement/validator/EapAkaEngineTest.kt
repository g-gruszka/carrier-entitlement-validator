package com.carrier.entitlement.validator

import com.carrier.entitlement.validator.sim.EapAkaEngine
import org.junit.Assert.*
import org.junit.Test

class EapAkaEngineTest {

    @Test
    fun testParseRealServerChallenge() {
        val serverEapPacket = "010100441701000001050000d2da577ecc76098e421683f532e0d9c702050000fe6b5885e2448000fd4e9ccfe46093880b05000082e54794e8127fb175b57f1dc98332b7"

        val challenge = EapAkaEngine.parseEapChallenge(serverEapPacket)

        assertEquals(1, challenge.eapId)
        assertEquals("d2da577ecc76098e421683f532e0d9c7", challenge.randHex)
        assertEquals("fe6b5885e2448000fd4e9ccfe4609388", challenge.autnHex)
        assertEquals("82e54794e8127fb175b57f1dc98332b7", challenge.macHex)
    }

    @Test
    fun testUserSimMilenageCrypto() {
        // Credenciales reales de la SIM provistas por el usuario
        val ki = "51A609FE8A3B18CEE53A5EB2F3D6C051"
        val opc = "A7695F045F0488396480353433A90007"
        val rand = "061123778d00b58f215544d0e165c989"
        val imsi = "722340390000126"

        val res = EapAkaEngine.computeMilenageRes(ki, opc, rand)
        assertEquals("73f47343eba0ecfa", res)

        val eapResponse = EapAkaEngine.buildEapResponseWithCrypto(
            eapId = 1,
            imsi = imsi,
            randHex = rand,
            resHex = res
        )

        assertTrue(eapResponse.startsWith("0201")) // Code 2 (Response), ID 1
        assertTrue(eapResponse.contains("17010000")) // Type 23, Subtype 1
        assertTrue(eapResponse.contains("03030040")) // AT_RES, len 3 words, 64 bits
        assertTrue(eapResponse.contains(res)) // Contiene el RES
    }
}
