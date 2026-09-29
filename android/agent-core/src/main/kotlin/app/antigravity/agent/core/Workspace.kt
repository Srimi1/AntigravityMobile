package app.antigravity.agent.core

import java.io.File

class ToolException(message: String) : Exception(message)

/** All file tools go through here so nothing can touch files outside [root]. */
class Workspace(root: File) {
    val root: File = root.canonicalFile

    init {
        this.root.mkdirs()
    }

    fun resolve(path: String): File {
        val raw = path.trim().ifEmpty { "." }
        val asGiven = File(raw)
        val candidate = if (asGiven.isAbsolute && asGiven.canonicalPath.startsWith(root.path)) {
            asGiven
        } else {
            File(root, raw.trimStart('/', '\\'))
        }
        val canon = candidate.canonicalFile
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) {
            throw ToolException("Path is outside the workspace: $path")
        }
        return canon
    }

    fun relative(file: File): String =
        if (file == root) "." else file.path.removePrefix(root.path + File.separator)
}
