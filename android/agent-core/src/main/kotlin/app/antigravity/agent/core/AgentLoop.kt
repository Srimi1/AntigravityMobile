package app.antigravity.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

sealed interface AgentEvent {
    data class ModelText(val text: String) : AgentEvent
    data class ToolStarted(val id: Int, val name: String, val args: JsonObject) : AgentEvent
    data class ToolFinished(val id: Int, val output: String, val isError: Boolean) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Finished : AgentEvent
}

class AgentLoop(
    private val llm: LlmClient,
    private val tools: List<Tool>,
    private val systemPrompt: String,
    private val approve: suspend (tool: String, args: JsonObject) -> Boolean,
    private val maxSteps: Int = 40,
) {
    /** Conversation in Gemini "contents" form. Persist and restore via [restore]. */
    val history: MutableList<JsonObject> = mutableListOf()
    private var nextId = 1

    fun restore(saved: List<JsonObject>) {
        history.clear()
        history.addAll(saved)
    }

    suspend fun send(userText: String, emit: suspend (AgentEvent) -> Unit) {
        history += userContent(userText)
        val specs = tools.map { it.spec }
        try {
            repeat(maxSteps) {
                val resp = llm.generate(systemPrompt, history, specs)
                if (resp.content["parts"]?.let { (it as? JsonArray)?.isNotEmpty() } == true) history += resp.content
                if (resp.text.isNotBlank()) emit(AgentEvent.ModelText(resp.text))
                if (resp.calls.isEmpty()) {
                    emit(AgentEvent.Finished)
                    return
                }
                runCalls(resp.calls, emit)
            }
            emit(AgentEvent.Failed("Stopped after $maxSteps steps. Send a message to continue."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun runCalls(calls: List<FunctionCall>, emit: suspend (AgentEvent) -> Unit) {
        val responses = mutableListOf<JsonObject>()
        try {
            for (call in calls) {
                val id = nextId++
                emit(AgentEvent.ToolStarted(id, call.name, call.args))
                val (output, isError) = execute(call)
                emit(AgentEvent.ToolFinished(id, output, isError))
                responses += functionResponse(call.name, output, isError)
            }
        } finally {
            // Keep history valid even if cancelled: every call needs a response.
            for (call in calls.drop(responses.size)) responses += functionResponse(call.name, "Cancelled", true)
            history += buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(responses))
            }
        }
    }

    private suspend fun execute(call: FunctionCall): Pair<String, Boolean> {
        val tool = tools.firstOrNull { it.spec.name == call.name }
            ?: return "Unknown tool: ${call.name}" to true
        if (tool.needsApproval && !approve(call.name, call.args)) return "The user denied this action." to true
        return try {
            tool.run(call.args) to false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            (e.message ?: "Tool failed") to true
        } catch (e: Exception) {
            "Tool failed: ${e.message ?: e.javaClass.simpleName}" to true
        }
    }

    private fun functionResponse(name: String, output: String, isError: Boolean): JsonObject = buildJsonObject {
        putJsonObject("functionResponse") {
            put("name", name)
            putJsonObject("response") { put(if (isError) "error" else "result", output) }
        }
    }

    private fun userContent(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("parts", JsonArray(listOf(buildJsonObject { put("text", text) })))
    }
}

val DEFAULT_SYSTEM_PROMPT = """
You are Antigravity Agent, a coding assistant running on the user's Android phone.
You work inside a private workspace folder. All file paths are relative to it.

How to work:
- For coding tasks, look before you change: list_dir, grep and read_file first.
- Make small, correct edits with edit_file. Use write_file for new files.
- Use run_shell to run and test code. The shell is Android's toybox sh, so many desktop tools (python, node, git) may not exist. Check with `command -v` before relying on them, and tell the user what is missing.
- After changing code, run it or check it when that is possible, then report what you did and what happened.
- Keep replies short and plain. Show code in fenced blocks.
- Never claim you ran something you did not run.
""".trimIndent()
