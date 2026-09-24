package com.carrier.entitlement.validator.data.network

import com.carrier.entitlement.validator.data.model.EapChallengeData
import com.carrier.entitlement.validator.data.model.TemporaryTokenResult
import com.carrier.entitlement.validator.data.model.Ts43VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class Ts43Client(
    private var baseUrl: String = "http://127.0.0.1:18080"
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(WireLoggingInterceptor(tag = "TS.43"))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    suspend fun checkHealth(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/health"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Pair(true, body)
            } else {
                Pair(false, "HTTP ${response.code}: $body")
            }
        } catch (e: Exception) {
            Pair(false, "Fallo de conexión: ${e.message}")
        }
    }

    /**
     * Ronda 1: Inicia el flujo EAP-AKA con el Entitlement Server.
     * GET /?app=ap2014&operation=AcquireTemporaryToken&EAP_ID=0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org
     */
    suspend fun acquireTemporaryTokenRound1(eapId: String): EapChallengeData = withContext(Dispatchers.IO) {
        val url = "$baseUrl/?app=ap2014&operation=AcquireTemporaryToken&EAP_ID=$eapId"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/vnd.gsma.eap-relay.v1.0+json, text/vnd.wap.connectivity-xml")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val raw = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val diameterCode = response.header("X-Diameter-Result-Code") ?: "N/A"
            throw IllegalStateException("Ronda 1 Falló (HTTP ${response.code}, Diameter: $diameterCode): $raw")
        }

        val json = JSONObject(raw)
        val eapPacket = json.optString("eap-relay-packet", "")
        val sessionId = json.optString("session_id", "")
        var eapSession = json.optString("eap_session", "")

        if (eapSession.isEmpty()) {
            val cookie = response.header("Set-Cookie") ?: ""
            val pattern = Pattern.compile("eap_session=([^;]+)")
            val matcher = pattern.matcher(cookie)
            if (matcher.find()) {
                eapSession = matcher.group(1) ?: ""
            }
        }

        if (eapPacket.isEmpty() || eapSession.isEmpty()) {
            throw IllegalStateException("Respuesta de Ronda 1 incompleta: falta eap-relay-packet o eap_session")
        }

        EapChallengeData(
            eapRelayPacket = eapPacket,
            sessionId = sessionId,
            eapSession = eapSession,
            rawJson = raw
        )
    }

    /**
     * Ronda 2: Envía el desafío respondido (EAP-Response/AKA-Challenge) al Entitlement Server.
     * POST / con JSON: { "eap-relay-packet": "<hex>", "eap_session": "<token>" }
     */
    suspend fun acquireTemporaryTokenRound2(
        eapResponsePacketHex: String,
        eapSession: String
    ): TemporaryTokenResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("eap-relay-packet", eapResponsePacketHex)
            put("eap_session", eapSession)
        }

        val request = Request.Builder()
            .url(baseUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .addHeader("Accept", "text/vnd.wap.connectivity-xml, application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val raw = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val diameterCode = response.header("X-Diameter-Result-Code") ?: "N/A"
            throw IllegalStateException("Ronda 2 Falló (HTTP ${response.code}, Diameter: $diameterCode): $raw")
        }

        // Parsear XML de TemporaryToken
        // <wap-provisioningdoc><characteristic type="TOKEN"><parm name="token" value="..."/><parm name="validity" value="..."/></characteristic>
        // O buscar con regex en el cuerpo XML
        var token = ""
        var exp = 0L

        val tokenMatcher = Pattern.compile("name=[\"']token[\"']\\s+value=[\"']([^\"']+)[\"']").matcher(raw)
        if (tokenMatcher.find()) {
            token = tokenMatcher.group(1) ?: ""
        } else {
            // Regex alternativa para tags <token>...</token>
            val altMatcher = Pattern.compile("<token>([^<]+)</token>").matcher(raw)
            if (altMatcher.find()) token = altMatcher.group(1) ?: ""
        }

        val expMatcher = Pattern.compile("name=[\"']validity[\"']\\s+value=[\"']([^\"']+)[\"']").matcher(raw)
        if (expMatcher.find()) {
            exp = expMatcher.group(1)?.toLongOrNull() ?: 0L
        }

        if (token.isEmpty()) {
            // Si el XML contiene el token en otro formato, conservamos el raw
            token = raw
        }

        TemporaryTokenResult(
            token = token,
            validUntilTimestamp = exp,
            rawXml = raw
        )
    }

    /**
     * Ronda 3 / Server-Side Operation: VerifyPhoneNumber (GSMA TS.43 Sección 13.1.2)
     * POST / con JSON o GET:
     * {
     *   "app": "ap2014",
     *   "operation": "VerifyPhoneNumber",
     *   "temporary_token": "<token>",
     *   "msisdn": "<msisdn>",
     *   "entitlement_version": "10.0",
     *   "requestor_id": "<requestor_id>"
     * }
     */
    suspend fun verifyPhoneNumber(
        temporaryToken: String,
        msisdn: String,
        requestorId: String = "00000000-0000-4000-8000-0000000000b1",
        entitlementVersion: String = "10.0"
    ): Ts43VerifyResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("app", "ap2014")
            put("operation", "VerifyPhoneNumber")
            put("temporary_token", temporaryToken)
            put("msisdn", msisdn)
            put("entitlement_version", entitlementVersion)
            put("requestor_id", requestorId)
        }

        val request = Request.Builder()
            .url(baseUrl)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .addHeader("Accept", "text/vnd.wap.connectivity-xml, application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val raw = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IllegalStateException("VerifyPhoneNumber Falló (HTTP ${response.code}): $raw")
        }

        // Buscar OperationResult en el XML devuelto por TS43Engine
        var opResult = -1
        val matchPattern = Pattern.compile("OperationResult>(\\d+)<")
        val m = matchPattern.matcher(raw)
        if (m.find()) {
            opResult = m.group(1)?.toIntOrNull() ?: -1
        }

        val isMatch = opResult == 1

        Ts43VerifyResult(
            operationResult = opResult,
            isMatch = isMatch,
            rawXml = raw
        )
    }
}
