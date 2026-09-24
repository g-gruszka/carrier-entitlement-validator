package com.carrier.entitlement.validator.data.network

import com.carrier.entitlement.validator.data.model.TraceEntry
import com.carrier.entitlement.validator.data.repository.TraceRepository
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.StandardCharsets

class WireLoggingInterceptor(
    private val tag: String = "HTTP"
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val reqHeaders = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            reqHeaders[request.headers.name(i)] = request.headers.value(i)
        }

        var reqBodyString: String? = null
        request.body?.let { body ->
            try {
                val buffer = Buffer()
                body.writeTo(buffer)
                val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                reqBodyString = buffer.readString(charset)
            } catch (e: Exception) {
                reqBodyString = "[Error reading request body: ${e.message}]"
            }
        }

        val response: Response
        var durationMs = 0L
        try {
            response = chain.proceed(request)
            durationMs = System.currentTimeMillis() - startTime
        } catch (e: IOException) {
            durationMs = System.currentTimeMillis() - startTime
            TraceRepository.addTrace(
                TraceEntry(
                    tag = tag,
                    method = request.method,
                    url = request.url.toString(),
                    requestHeaders = reqHeaders,
                    requestBody = reqBodyString,
                    durationMs = durationMs,
                    isSuccess = false,
                    errorMessage = e.message ?: "Connection failure"
                )
            )
            throw e
        }

        val respHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            respHeaders[response.headers.name(i)] = response.headers.value(i)
        }

        var respBodyString: String? = null
        val responseBody = response.body
        val source = responseBody?.source()
        if (source != null) {
            try {
                source.request(Long.MAX_VALUE)
                val buffer = source.buffer.clone()
                val charset = responseBody.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                respBodyString = buffer.readString(charset)
            } catch (e: Exception) {
                respBodyString = "[Error reading response body: ${e.message}]"
            }
        }

        TraceRepository.addTrace(
            TraceEntry(
                tag = tag,
                method = request.method,
                url = request.url.toString(),
                requestHeaders = reqHeaders,
                requestBody = reqBodyString,
                responseCode = response.code,
                responseHeaders = respHeaders,
                responseBody = respBodyString,
                durationMs = durationMs,
                isSuccess = response.isSuccessful,
                errorMessage = if (!response.isSuccessful) "HTTP ${response.code} ${response.message}" else null
            )
        )

        return response
    }
}
