package com.carrier.entitlement.validator.data.network

import com.carrier.entitlement.validator.data.model.CamaraVerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class CamaraClient(
    private var baseUrl: String = "http://127.0.0.1:8081"
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(WireLoggingInterceptor(tag = "CAMARA"))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    suspend fun checkHealth(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/health"
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
     * Endpoint CAMARA Number Verification 2.1.0:
     * POST /number-verification/v2/verify
     * Headers:
     *   Authorization: Bearer <token>
     *   x-correlator: <uuid>
     * Body:
     *   { "phoneNumber": "+541179999999" }
     */
    suspend fun verifyPhoneNumber(
        phoneNumber: String,
        bearerToken: String,
        correlator: String = UUID.randomUUID().toString()
    ): CamaraVerifyResult = withContext(Dispatchers.IO) {
        val cleanPhone = if (!phoneNumber.startsWith("+")) "+$phoneNumber" else phoneNumber
        val payload = JSONObject().apply {
            put("phoneNumber", cleanPhone)
        }

        val url = "$baseUrl/number-verification/v2/verify"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", if (bearerToken.startsWith("Bearer ")) bearerToken else "Bearer $bearerToken")
            .addHeader("x-correlator", correlator)
            .addHeader("Accept", "application/json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val raw = response.body?.string() ?: ""
        val xCorrelator = response.header("x-correlator") ?: correlator

        if (!response.isSuccessful) {
            return@withContext CamaraVerifyResult(
                devicePhoneNumberVerified = false,
                xCorrelator = xCorrelator,
                statusCode = response.code,
                rawResponse = raw
            )
        }

        val json = JSONObject(raw)
        val verified = json.optBoolean("devicePhoneNumberVerified", false)

        CamaraVerifyResult(
            devicePhoneNumberVerified = verified,
            xCorrelator = xCorrelator,
            statusCode = response.code,
            rawResponse = raw
        )
    }
}
