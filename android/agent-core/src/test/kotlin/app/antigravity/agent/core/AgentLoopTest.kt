package app.antigravity.agent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun modelTurn(text: String?, vararg calls: FunctionCall): LlmResponse {
    val parts = buildList {
        if (text != null) add(buildJsonObject { put("text", text) })
        for (c in calls) add(buildJsonObject { putJsonObject("functionCall") { put("name", c.name); put("args", c.args) } })
    }
    return LlmResponse(
        buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) },
        text.orEmpty(),
        calls.toList(),
    )
}

private class FakeLlm(private val script: List<LlmResponse>) : LlmClient {
    var calls = 0
    val seen = mutableListOf<List<JsonObject>>()
    override suspend fun generate(system: String, contents: List<JsonObject>, tools: List<ToolSpec>): LlmResponse {
        seen += contents.toList()
        return script[calls++]
    }
}

class AgentLoopTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun loop(llm: LlmClient, approve: Boolean = true): Pair<AgentLoop, Workspace> {
        val ws = Workspace(tmp.newFolder("ws"))
        val tools = listOf(WriteFileTool(ws), ReadFileTool(ws), RunShellTool(ws, "sh"))
        return AgentLoop(llm, tools, "sys", { _, _ -> approve }) to ws
    }

    @Test fun toolCallThenAnswer() = runTest {
        val llm = FakeLlm(
            listOf(
                modelTurn("Creating file", FunctionCall("write_file", buildJsonObject { put("path", "hello.txt"); put("content", "hi") })),
                modelTurn("Done"),
            ),
        )
        val (agent, ws) = loop(llm)
        val events = mutableListOf<AgentEvent>()
        agent.send("make a file") { events += it }

        assertEquals("hi", ws.root.resolve("hello.txt").readText())
        assertTrue(events.first() is AgentEvent.ModelText)
        assertTrue(events.any { it is AgentEvent.ToolFinished && !it.isError })
        assertEquals(AgentEvent.Finished, events.last())
        // user, model(call), user(response), model(answer)
        assertEquals(4, agent.history.size)
        // The second request carried the function response back to the model.
        assertTrue(llm.seen[1].last().toString().contains("functionResponse"))
    }

    @Test fun deniedShellIsReportedAndNotRun() = runTest {
        val llm = FakeLlm(
            listOf(
                modelTurn(null, FunctionCall("run_shell", buildJsonObject { put("command", "touch ran.txt") })),
                modelTurn("ok"),
            ),
        )
        val (agent, ws) = loop(llm, approve = false)
        val events = mutableListOf<AgentEvent>()
        agent.send("go") { events += it }
        assertTrue(!ws.root.resolve("ran.txt").exists())
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError && it.output.contains("denied") })
    }

    @Test fun toolErrorsGoBackToTheModel() = runTest {
        val llm = FakeLlm(
            listOf(
                modelTurn(null, FunctionCall("read_file", buildJsonObject { put("path", "missing.txt") })),
                modelTurn("not there"),
            ),
        )
        val (agent, _) = loop(llm)
        val events = mutableListOf<AgentEvent>()
        agent.send("read") { events += it }
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.isError })
        assertEquals(AgentEvent.Finished, events.last())
    }

    @Test fun stopsAfterMaxSteps() = runTest {
        val call = FunctionCall("list_dir", buildJsonObject {})
        val ws = Workspace(tmp.newFolder("ws2"))
        val llm = object : LlmClient {
            override suspend fun generate(system: String, contents: List<JsonObject>, tools: List<ToolSpec>) = modelTurn(null, call)
        }
        val agent = AgentLoop(llm, listOf(ListDirTool(ws)), "sys", { _, _ -> true }, maxSteps = 3)
        val events = mutableListOf<AgentEvent>()
        agent.send("loop") { events += it }
        assertTrue(events.last() is AgentEvent.Failed)
    }

    @Test fun transcriptRebuildsFromHistory() = runTest {
        val llm = FakeLlm(
            listOf(
                modelTurn("hm", FunctionCall("write_file", buildJsonObject { put("path", "a.txt"); put("content", "x") })),
                modelTurn("Done"),
            ),
        )
        val (agent, _) = loop(llm)
        agent.send("make a.txt") {}
        val items = historyToItems(agent.history)
        assertEquals(4, items.size)
        assertTrue(items[0] is ChatItem.User)
        val tool = items[2] as ChatItem.ToolRun
        assertEquals("a.txt", tool.summary)
        assertTrue(tool.output!!.contains("Wrote"))
        assertTrue(items[3] is ChatItem.Model)
    }
}
