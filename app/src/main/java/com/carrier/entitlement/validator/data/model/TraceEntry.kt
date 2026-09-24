package com.carrier.entitlement.validator.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TraceEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val responseCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    val durationMs: Long = 0,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    fun toCurl(): String {
        val sb = StringBuilder("curl -i -X $method '$url'")
        requestHeaders.forEach { (k, v) ->
            sb.append(" \\\n  -H '$k: $v'")
        }
        if (!requestBody.isNullOrBlank()) {
            sb.append(" \\\n  --data '$requestBody'")
        }
        return sb.toString()
    }
}
