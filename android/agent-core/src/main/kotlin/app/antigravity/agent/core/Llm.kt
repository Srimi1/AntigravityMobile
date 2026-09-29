package app.antigravity.agent.core

import kotlinx.serialization.json.JsonObject

data class FunctionCall(val name: String, val args: JsonObject)

/** [content] is the raw model turn (kept verbatim so provider extras such as thought signatures survive). */
data class LlmResponse(val content: JsonObject, val text: String, val calls: List<FunctionCall>)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

class LlmException(message: String, val code: Int = 0) : Exception(message)

interface LlmClient {
    suspend fun generate(system: String, contents: List<JsonObject>, tools: List<ToolSpec>): LlmResponse
}
