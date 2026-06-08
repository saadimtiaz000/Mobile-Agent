package com.nora.agent.network

import com.nora.agent.config.NoraConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NoraApi(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {
    suspend fun exchangeSdp(offerSdp: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${NoraConfig.BACKEND_BASE_URL}/api/realtime/sdp")
            .header("Content-Type", "application/sdp")
            .header("X-User-Id", "local-dev-user")
            .post(offerSdp.toRequestBody("application/sdp".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(readableBackendError(response.code, body))
            }
            body
        }
    }

    suspend fun executeToolCall(name: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        val argumentsObject = runCatching {
            JSONObject(argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            JSONObject().put("_raw", argumentsJson)
        }
        val payload = JSONObject()
            .put("name", name)
            .put("arguments", argumentsObject)
            .toString()

        val request = Request.Builder()
            .url("${NoraConfig.BACKEND_BASE_URL}/api/realtime/tool")
            .header("Content-Type", "application/json")
            .header("X-User-Id", "local-dev-user")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(readableBackendError(response.code, body))
            }
            body
        }
    }

    private fun readableBackendError(code: Int, body: String): String {
        val message = runCatching {
            JSONObject(body).optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()

        return message ?: when (code) {
            404 -> "Nora backend is not running at ${NoraConfig.BACKEND_BASE_URL}."
            503 -> "Nora backend is not ready. Add OPENAI_API_KEY to backend/.env and restart it."
            else -> "Nora backend returned HTTP $code."
        }
    }
}
