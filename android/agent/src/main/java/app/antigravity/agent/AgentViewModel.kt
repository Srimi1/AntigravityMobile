package app.antigravity.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.antigravity.agent.core.AgentEvent
import app.antigravity.agent.core.AgentLoop
import app.antigravity.agent.core.ChatItem
import app.antigravity.agent.core.DEFAULT_SYSTEM_PROMPT
import app.antigravity.agent.core.GeminiClient
import app.antigravity.agent.core.Workspace
import app.antigravity.agent.core.defaultTools
import app.antigravity.agent.core.historyToItems
import app.antigravity.agent.core.summarizeArgs
import app.antigravity.agent.data.SecretStore
import app.antigravity.agent.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

data class PendingApproval(val tool: String, val summary: String)

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val secrets = SecretStore(app)
    val settings = SettingsStore(app)
    val workspaceDir = File(app.filesDir, "workspace").also { it.mkdirs() }
    private val historyFile = File(app.filesDir, "history.json")

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private var approval: CompletableDeferred<Boolean>? = null
    private var job: Job? = null
    private var nextItemId = 1_000_000

    private val loop = AgentLoop(
        llm = GeminiClient({ secrets.getApiKey() }, { settings.model }, http),
        tools = defaultTools(Workspace(workspaceDir), http),
        systemPrompt = DEFAULT_SYSTEM_PROMPT + "\nThe workspace folder is your current directory.",
        approve = ::askApproval,
    )

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()
    private val _hasKey = MutableStateFlow(secrets.getApiKey().isNotEmpty())
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    init {
        loadHistory()
    }

    fun saveApiKey(key: String) {
        secrets.putApiKey(key)
        _hasKey.value = secrets.getApiKey().isNotEmpty()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _running.value) return
        _items.update { it + ChatItem.User(nextItemId++, trimmed) }
        _running.value = true
        job = viewModelScope.launch {
            try {
                loop.send(trimmed, ::onEvent)
            } finally {
                _running.value = false
                approval?.complete(false)
                _pending.value = null
                saveHistory()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clearChat() {
        stop()
        loop.restore(emptyList())
        _items.value = emptyList()
        historyFile.delete()
    }

    fun answerApproval(ok: Boolean) {
        approval?.complete(ok)
    }

    private suspend fun askApproval(tool: String, args: JsonObject): Boolean {
        if (settings.autoApproveShell) return true
        val d = CompletableDeferred<Boolean>()
        approval = d
        _pending.value = PendingApproval(tool, summarizeArgs(tool, args))
        return try {
            d.await()
        } finally {
            _pending.value = null
        }
    }

    private suspend fun onEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.ModelText -> _items.update { it + ChatItem.Model(nextItemId++, e.text) }
            is AgentEvent.ToolStarted ->
                _items.update { it + ChatItem.ToolRun(e.id + TOOL_ID_BASE, e.name, summarizeArgs(e.name, e.args)) }
            is AgentEvent.ToolFinished -> _items.update { list ->
                list.map {
                    if (it is ChatItem.ToolRun && it.id == e.id + TOOL_ID_BASE) it.copy(output = e.output, isError = e.isError) else it
                }
            }
            is AgentEvent.Failed -> _items.update { it + ChatItem.Notice(nextItemId++, e.message) }
            AgentEvent.Finished -> Unit
        }
    }

    private fun saveHistory() {
        try {
            historyFile.writeText(JsonArray(loop.history).toString())
        } catch (_: Exception) {
        }
    }

    private fun loadHistory() {
        try {
            if (!historyFile.exists()) return
            val saved = Json.parseToJsonElement(historyFile.readText()).jsonArray.map { it.jsonObject }
            loop.restore(saved)
            _items.value = historyToItems(saved)
        } catch (_: Exception) {
            historyFile.delete()
        }
    }

    private companion object {
        // Live tool ids come from the loop (1, 2, ...); offset them so they never collide with other item ids.
        const val TOOL_ID_BASE = 2_000_000
    }
}
