package app.antigravity.agent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiClientTest {
    private val server = MockWebServer()

    @After fun stop() = server.shutdown()

    private fun client(key: String = "k123") = GeminiClient(
        { key }, { "gemini-test" }, OkHttpClient(), server.url("/").toString().trimEnd('/'), retryDelayMs = 1,
    )

    private val user = listOf(
        buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(listOf(buildJsonObject { put("text", "hi") })))
        },
    )

    @Test fun sendsKeyToolsAndParsesFunctionCall() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"role":"model","parts":[
                    {"text":"thinking","thought":true},
                    {"text":"Let me check."},
                    {"functionCall":{"name":"list_dir","args":{"path":"."}},"thoughtSignature":"abc"}
                ]}}]}""",
            ),
        )
        val tool = ListDirTool(Workspace(java.io.File(System.getProperty("java.io.tmpdir"), "gc-test")))
        val resp = client().generate("sys", user, listOf(tool.spec))

        assertEquals("Let me check.", resp.text)
        assertEquals("list_dir", resp.calls.single().name)
        // Raw content is kept verbatim, including the thought signature.
        assertTrue(resp.content.toString().contains("thoughtSignature"))

        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test:generateContent", req.path)
        assertEquals("k123", req.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("sys", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val decl = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("list_dir", decl[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun retriesOn429ThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}"""))
        assertEquals("ok", client().generate("s", user, emptyList()).text)
        assertEquals(2, server.requestCount)
    }

    @Test fun reportsApiErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"API key not valid"}}"""))
        try {
            client().generate("s", user, emptyList())
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("API key not valid"))
            assertEquals(400, e.code)
        }
    }

    @Test fun missingKeyFailsBeforeNetwork() = runTest {
        try {
            client(key = " ").generate("s", user, emptyList())
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("API key"))
        }
        assertEquals(0, server.requestCount)
    }
}
