package app.antigravity.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Talks to the Gemini REST API (generateContent) with function calling. Needs the user's own API key. */
class GeminiClient(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val retryDelayMs: Long = 2000,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(system: String, contents: List<JsonObject>, tools: List<ToolSpec>): LlmResponse {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException("No Gemini API key set. Add one in Settings.")
        val body = buildBody(system, contents, tools).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/${model().trim()}:generateContent")
            .header("x-goog-api-key", key)
            .post(body)
            .build()

        var attempt = 0
        while (true) {
            val (code, text) = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            }
            if (code in 200..299) return parse(text)
            val retryable = code == 429 || code in 500..599
            if (retryable && attempt < 3) {
                attempt++
                delay(retryDelayMs * attempt)
                continue
            }
            throw LlmException(errorMessage(code, text), code)
        }
    }

    internal fun buildBody(system: String, contents: List<JsonObject>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
        }
        put("contents", JsonArray(contents))
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (t in tools) add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        })
                    }
                })
            }
        }
    }

    internal fun parse(text: String): LlmResponse {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw LlmException("Unreadable response from Gemini")
        }
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        if (candidate == null) {
            val reason = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw LlmException("Gemini returned no answer" + (reason?.let { " (blocked: $it)" } ?: ""))
        }
        val content = candidate["content"]?.jsonObject
            ?: buildJsonObject { put("role", "model"); put("parts", JsonArray(emptyList())) }
        val parts = content["parts"]?.jsonArray ?: JsonArray(emptyList())
        val sb = StringBuilder()
        val calls = mutableListOf<FunctionCall>()
        for (p in parts) {
            val o = p.jsonObject
            val isThought = o["thought"]?.jsonPrimitive?.booleanOrNull == true
            o["text"]?.jsonPrimitive?.contentOrNull?.let { if (!isThought) sb.append(it) }
            o["functionCall"]?.jsonObject?.let { fc ->
                calls += FunctionCall(
                    fc["name"]?.jsonPrimitive?.content.orEmpty(),
                    fc["args"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
        return LlmResponse(content, sb.toString(), calls)
    }

    private fun errorMessage(code: Int, text: String): String {
        val msg = try {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
        val hint = when (code) {
            400 -> " (check the model name and API key)"
            403 -> " (API key not allowed for this model)"
            429 -> " (rate limit or quota reached, try again later)"
            else -> ""
        }
        return "Gemini error $code: ${msg ?: text.take(200)}$hint"
    }
}

