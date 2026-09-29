package app.antigravity.agent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the chat screen shows. Rebuilt from the saved history, so history is the single source of truth. */
sealed interface ChatItem {
    val id: Int

    data class User(override val id: Int, val text: String) : ChatItem
    data class Model(override val id: Int, val text: String) : ChatItem
    data class Notice(override val id: Int, val text: String) : ChatItem
    data class ToolRun(
        override val id: Int,
        val name: String,
        val summary: String,
        val output: String? = null,
        val isError: Boolean = false,
    ) : ChatItem
}

fun summarizeArgs(name: String, args: JsonObject): String {
    fun s(k: String) = args[k]?.jsonPrimitive?.contentOrNull
    val v = when (name) {
        "run_shell" -> s("command")
        "read_file", "write_file", "edit_file" -> s("path")
        "list_dir" -> s("path") ?: "."
        "grep" -> s("pattern")
        "web_fetch" -> s("url")
        else -> args.toString()
    } ?: ""
    return if (v.length > 200) v.take(200) + "..." else v
}

fun historyToItems(history: List<JsonObject>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var id = 1
    for (content in history) {
        val role = content["role"]?.jsonPrimitive?.contentOrNull
        val parts = (content["parts"] as? JsonArray) ?: continue
        for (part in parts) {
            val o = part.jsonObject
            val text = o["text"]?.jsonPrimitive?.contentOrNull
            val isThought = o["thought"]?.jsonPrimitive?.booleanOrNull == true
            if (text != null && !isThought && text.isNotBlank()) {
                items += if (role == "user") ChatItem.User(id++, text) else ChatItem.Model(id++, text)
            }
            o["functionCall"]?.jsonObject?.let { fc ->
                val name = fc["name"]?.jsonPrimitive?.content.orEmpty()
                items += ChatItem.ToolRun(id++, name, summarizeArgs(name, fc["args"]?.jsonObject ?: JsonObject(emptyMap())))
            }
            o["functionResponse"]?.jsonObject?.let { fr ->
                val name = fr["name"]?.jsonPrimitive?.content.orEmpty()
                val resp = fr["response"]?.jsonObject
                val err = resp?.get("error")?.jsonPrimitive?.contentOrNull
                val out = err ?: resp?.get("result")?.jsonPrimitive?.contentOrNull ?: ""
                val idx = items.indexOfLast { it is ChatItem.ToolRun && it.name == name && it.output == null }
                if (idx >= 0) items[idx] = (items[idx] as ChatItem.ToolRun).copy(output = out, isError = err != null)
            }
        }
    }
    return items
}
