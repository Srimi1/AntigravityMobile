package app.antigravity.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

interface Tool {
    val spec: ToolSpec

    /** True if the user should confirm before this runs (unless auto-approve is on). */
    val needsApproval: Boolean get() = false

    suspend fun run(args: JsonObject): String
}

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.reqStr(key: String): String = str(key) ?: throw ToolException("Missing argument: $key")
internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

internal fun schema(required: List<String>, props: Map<String, Pair<String, String>>): JsonObject = buildJsonObject {
    put("type", "OBJECT")
    putJsonObject("properties") {
        for ((name, typeDesc) in props) putJsonObject(name) {
            put("type", typeDesc.first)
            put("description", typeDesc.second)
        }
    }
    if (required.isNotEmpty()) {
        put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}

private const val MAX_READ_BYTES = 300_000L
private val SKIP_DIRS = setOf(".git", "node_modules", ".gradle", "build", "__pycache__")

private fun looksBinary(f: File): Boolean = f.inputStream().use { s ->
    val buf = ByteArray(2048)
    val n = s.read(buf)
    (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
}

class ReadFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        "read_file",
        "Read a text file from the workspace. Returns numbered lines. Use start_line/end_line for big files.",
        schema(
            listOf("path"),
            mapOf(
                "path" to ("STRING" to "File path relative to the workspace"),
                "start_line" to ("INTEGER" to "First line to read, 1-based (optional)"),
                "end_line" to ("INTEGER" to "Last line to read (optional)"),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val f = ws.resolve(args.reqStr("path"))
        if (!f.isFile) throw ToolException("Not a file: ${ws.relative(f)}")
        if (f.length() > MAX_READ_BYTES) throw ToolException("File too large (${f.length()} bytes). Use grep or shell tools.")
        if (looksBinary(f)) throw ToolException("Binary file, cannot show as text.")
        val lines = f.readLines()
        val start = (args.int("start_line") ?: 1).coerceAtLeast(1)
        val end = (args.int("end_line") ?: lines.size).coerceAtMost(lines.size)
        if (lines.isEmpty()) return@withContext "(empty file)"
        if (start > end) throw ToolException("start_line is past the end of the file (${lines.size} lines)")
        val slice = lines.subList(start - 1, end)
        val out = slice.withIndex().joinToString("\n") { (i, l) -> "${start + i}\t$l" }
        if (out.length > 60_000) out.take(60_000) + "\n... (truncated, use start_line/end_line)" else out
    }
}

class WriteFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        "write_file",
        "Create or overwrite a file in the workspace. Parent folders are created automatically.",
        schema(
            listOf("path", "content"),
            mapOf(
                "path" to ("STRING" to "File path relative to the workspace"),
                "content" to ("STRING" to "Full file content"),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val f = ws.resolve(args.reqStr("path"))
        if (f == ws.root || f.isDirectory) throw ToolException("Path is a directory")
        val content = args.reqStr("content")
        f.parentFile?.mkdirs()
        f.writeText(content)
        "Wrote ${content.length} characters to ${ws.relative(f)}"
    }
}

class EditFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        "edit_file",
        "Replace text in an existing file. old_string must match exactly and be unique unless replace_all is true.",
        schema(
            listOf("path", "old_string", "new_string"),
            mapOf(
                "path" to ("STRING" to "File path relative to the workspace"),
                "old_string" to ("STRING" to "Exact text to find"),
                "new_string" to ("STRING" to "Replacement text"),
                "replace_all" to ("BOOLEAN" to "Replace every match instead of requiring a unique one"),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val f = ws.resolve(args.reqStr("path"))
        if (!f.isFile) throw ToolException("Not a file: ${ws.relative(f)}")
        val old = args.reqStr("old_string")
        val new = args.reqStr("new_string")
        if (old.isEmpty()) throw ToolException("old_string must not be empty")
        val text = f.readText()
        val count = Regex.escape(old).toRegex().findAll(text).count()
        if (count == 0) throw ToolException("old_string not found. Read the file again and copy the text exactly.")
        val all = args.bool("replace_all") == true
        if (count > 1 && !all) throw ToolException("old_string matches $count places. Add more context or set replace_all.")
        f.writeText(text.replace(old, new))
        "Edited ${ws.relative(f)} ($count replacement${if (count == 1) "" else "s"})"
    }
}

class ListDirTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        "list_dir",
        "List files and folders. Set recursive to walk subfolders (max depth 4).",
        schema(
            emptyList(),
            mapOf(
                "path" to ("STRING" to "Folder relative to the workspace (default: workspace root)"),
                "recursive" to ("BOOLEAN" to "Include subfolders"),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val dir = ws.resolve(args.str("path") ?: ".")
        if (!dir.isDirectory) throw ToolException("Not a folder: ${ws.relative(dir)}")
        val recursive = args.bool("recursive") == true
        val out = mutableListOf<String>()
        fun walk(d: File, depth: Int) {
            val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (k in kids) {
                if (out.size >= 500) return
                out += ws.relative(k) + if (k.isDirectory) "/" else ""
                if (recursive && k.isDirectory && depth < 4 && k.name !in SKIP_DIRS) walk(k, depth + 1)
            }
        }
        walk(dir, 1)
        if (out.isEmpty()) "(empty)" else out.joinToString("\n") + if (out.size >= 500) "\n... (truncated)" else ""
    }
}

class GrepTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        "grep",
        "Search file contents with a regular expression. Returns path:line: text (max 200 matches).",
        schema(
            listOf("pattern"),
            mapOf(
                "pattern" to ("STRING" to "Regular expression"),
                "path" to ("STRING" to "Folder or file to search (default: workspace root)"),
                "include" to ("STRING" to "Only files whose name matches this glob, e.g. *.kt"),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val regex = try {
            Regex(args.reqStr("pattern"))
        } catch (e: Exception) {
            throw ToolException("Invalid regex: ${e.message}")
        }
        val base = ws.resolve(args.str("path") ?: ".")
        val include = args.str("include")?.let { globToRegex(it) }
        val out = mutableListOf<String>()
        val files = if (base.isFile) sequenceOf(base) else base.walkTopDown().onEnter { it.name !in SKIP_DIRS }.filter { it.isFile }
        for (f in files) {
            if (out.size >= 200) break
            if (include != null && !include.matches(f.name)) continue
            if (f.length() > 1_000_000 || looksBinary(f)) continue
            f.useLines { lines ->
                for ((i, line) in lines.withIndex()) {
                    if (regex.containsMatchIn(line)) {
                        out += "${ws.relative(f)}:${i + 1}: ${line.take(300)}"
                        if (out.size >= 200) break
                    }
                }
            }
        }
        if (out.isEmpty()) "No matches" else out.joinToString("\n")
    }

    private fun globToRegex(glob: String): Regex =
        Regex(glob.map { c ->
            when (c) {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(c.toString())
            }
        }.joinToString(""))
}

class RunShellTool(
    private val ws: Workspace,
    private val shellPath: String = "/system/bin/sh",
    private val extraEnv: Map<String, String> = emptyMap(),
) : Tool {
    override val needsApproval = true
    override val spec = ToolSpec(
        "run_shell",
        "Run a shell command in the workspace folder and return its output. Commands run in an Android shell (toybox). " +
            "Default timeout 60s, max 600s.",
        schema(
            listOf("command"),
            mapOf(
                "command" to ("STRING" to "Shell command"),
                "timeout_seconds" to ("INTEGER" to "Time limit in seconds"),
            ),
        ),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val cmd = args.reqStr("command")
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 600)
        val pb = ProcessBuilder(shellPath, "-c", cmd).directory(ws.root).redirectErrorStream(true)
        pb.environment().apply {
            put("HOME", ws.root.path)
            put("TMPDIR", ws.root.resolve(".tmp").apply { mkdirs() }.path)
            putAll(extraEnv)
        }
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            throw ToolException("Could not start shell: ${e.message}")
        }
        val buf = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().use { r ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = r.read(chunk)
                    if (n < 0) break
                    synchronized(buf) { buf.append(chunk, 0, n) }
                }
            }
        }.also { it.isDaemon = true; it.start() }
        val finished = try {
            proc.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            proc.destroyForcibly()
            throw e
        }
        if (!finished) proc.destroyForcibly()
        reader.join(1000)
        val text = synchronized(buf) { buf.toString() }
        val shown = if (text.length > 20_000) text.take(8_000) + "\n... (output cut) ...\n" + text.takeLast(10_000) else text
        val status = if (finished) "exit code ${proc.exitValue()}" else "timed out after ${timeout}s (killed)"
        "$status\n$shown".trimEnd()
    }
}

class WebFetchTool(private val http: OkHttpClient) : Tool {
    override val spec = ToolSpec(
        "web_fetch",
        "Download a web page or text file over HTTPS and return its text (max 20000 characters).",
        schema(listOf("url"), mapOf("url" to ("STRING" to "https:// URL"))),
    )

    override suspend fun run(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.reqStr("url")
        if (!url.startsWith("https://")) throw ToolException("Only https:// URLs are allowed")
        val req = Request.Builder().url(url).header("User-Agent", "AntigravityAgent/1.0").build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.source()?.let { src ->
                src.request(200_000)
                src.buffer.readUtf8(minOf(src.buffer.size, 200_000L))
            }.orEmpty()
            if (!r.isSuccessful) throw ToolException("HTTP ${r.code}")
            if (body.length > 20_000) body.take(20_000) + "\n... (truncated)" else body
        }
    }
}

fun defaultTools(ws: Workspace, http: OkHttpClient, shellPath: String = "/system/bin/sh"): List<Tool> = listOf(
    ListDirTool(ws), ReadFileTool(ws), WriteFileTool(ws), EditFileTool(ws), GrepTool(ws),
    RunShellTool(ws, shellPath), WebFetchTool(http),
)

