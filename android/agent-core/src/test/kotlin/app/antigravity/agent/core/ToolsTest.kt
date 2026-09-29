package app.antigravity.agent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ws() = Workspace(tmp.newFolder("ws"))

    @Test fun rejectsPathTraversal() {
        val w = ws()
        for (bad in listOf("../x", "a/../../x", "/etc/passwd")) {
            try {
                val f = w.resolve(bad)
                // Absolute paths outside the root are re-rooted inside it, never outside.
                assertTrue(f.path.startsWith(w.root.path))
            } catch (e: ToolException) {
                assertTrue(e.message!!.contains("outside"))
            }
        }
        try {
            w.resolve("../escape")
            fail("expected ToolException")
        } catch (e: ToolException) { /* ok */ }
    }

    @Test fun rejectsSymlinkEscape() {
        val w = ws()
        val outside = tmp.newFolder("outside")
        java.nio.file.Files.createSymbolicLink(w.root.resolve("link").toPath(), outside.toPath())
        try {
            w.resolve("link/file.txt")
            fail("expected ToolException")
        } catch (e: ToolException) { /* ok */ }
    }

    @Test fun writeReadEdit() = runTest {
        val w = ws()
        WriteFileTool(w).run(buildJsonObject { put("path", "src/a.txt"); put("content", "one\ntwo\nthree\n") })
        val read = ReadFileTool(w).run(buildJsonObject { put("path", "src/a.txt") })
        assertTrue(read.contains("2\ttwo"))
        EditFileTool(w).run(buildJsonObject { put("path", "src/a.txt"); put("old_string", "two"); put("new_string", "2") })
        assertEquals("one\n2\nthree\n", w.root.resolve("src/a.txt").readText())
    }

    @Test fun editRequiresUniqueMatch() = runTest {
        val w = ws()
        w.root.resolve("f.txt").writeText("x x")
        try {
            EditFileTool(w).run(buildJsonObject { put("path", "f.txt"); put("old_string", "x"); put("new_string", "y") })
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("2 places"))
        }
        EditFileTool(w).run(buildJsonObject {
            put("path", "f.txt"); put("old_string", "x"); put("new_string", "y"); put("replace_all", true)
        })
        assertEquals("y y", w.root.resolve("f.txt").readText())
    }

    @Test fun grepAndList() = runTest {
        val w = ws()
        w.root.resolve("a.kt").writeText("fun main() {}\nval x = 1\n")
        w.root.resolve("b.txt").writeText("val y = 2\n")
        val hits = GrepTool(w).run(buildJsonObject { put("pattern", "val \\w"); put("include", "*.kt") })
        assertEquals("a.kt:2: val x = 1", hits)
        val list = ListDirTool(w).run(buildJsonObject {})
        assertTrue(list.contains("a.kt") && list.contains("b.txt"))
    }

    @Test fun shellRunsAndTimesOut() = runTest {
        val w = ws()
        val sh = RunShellTool(w, "sh")
        val out = sh.run(buildJsonObject { put("command", "echo hi; pwd") })
        assertTrue(out.startsWith("exit code 0"))
        assertTrue(out.contains("hi"))
        assertTrue(out.contains(w.root.path))
        val slow = sh.run(buildJsonObject { put("command", "sleep 5"); put("timeout_seconds", 1) })
        assertTrue(slow.startsWith("timed out"))
    }
}
