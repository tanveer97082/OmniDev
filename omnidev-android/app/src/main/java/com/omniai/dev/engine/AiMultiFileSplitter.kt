package com.omniai.dev.engine

import android.content.Context
import com.omniai.dev.data.ProjectEntity
import com.omniai.dev.data.WorkspaceManager
import java.io.File

data class ParsedSplitFile(
    val relativePath: String,
    val content: String
)

data class SplitApplyResult(
    val writtenFiles: List<String>,
    val skippedPaths: List<String>,
    val triggeredAutoPush: Boolean
)

object AiMultiFileSplitter {

    private val fileHeaderRegex = Regex("""^\s*#{2,4}\s*FILE\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    fun parse(rawInput: String): List<ParsedSplitFile> {
        val normalized = rawInput.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.lines()
        val parsed = mutableListOf<ParsedSplitFile>()

        var currentPath: String? = null
        val buffer = mutableListOf<String>()

        fun flushCurrent() {
            val path = currentPath ?: return
            val cleaned = stripMarkdownFences(buffer)
            parsed += ParsedSplitFile(relativePath = path, content = cleaned)
            buffer.clear()
        }

        for (line in lines) {
            val match = fileHeaderRegex.matchEntire(line)
            if (match != null) {
                flushCurrent()
                val rawPath = match.groupValues[1]
                    .trim()
                    .trim('`', '"', '\'')
                    .removePrefix("./")
                    .removePrefix("/")
                currentPath = sanitizeRelativePath(rawPath)
            } else if (currentPath != null) {
                buffer += line
            }
        }
        flushCurrent()
        return parsed
    }

    private fun stripMarkdownFences(lines: List<String>): String {
        val trimmed = lines.toMutableList()
        while (trimmed.isNotEmpty() && trimmed.first().isBlank()) trimmed.removeAt(0)
        while (trimmed.isNotEmpty() && trimmed.last().isBlank()) trimmed.removeAt(trimmed.lastIndex)

        if (trimmed.size >= 2 &&
            trimmed.first().trimStart().startsWith("```") &&
            trimmed.last().trim() == "```"
        ) {
            trimmed.removeAt(0)
            trimmed.removeAt(trimmed.lastIndex)
        }
        return trimmed.joinToString("\n") + "\n"
    }

    private fun sanitizeRelativePath(path: String): String? {
        val unified = path.replace('\\', '/').trim()
        if (unified.isBlank() || unified.contains("..") || unified.startsWith("/")) return null
        val segments = unified.split("/").filter { it.isNotBlank() && it != "." }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    fun applyToProject(
        context: Context,
        project: ProjectEntity,
        rawInput: String
    ): SplitApplyResult {
        val parsed = parse(rawInput)
        val root = WorkspaceManager.projectRoot(context, project.slug)
        val canonicalRoot = root.canonicalPath
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (entry in parsed) {
            val target = File(root, entry.relativePath)
            val canonicalTarget = target.canonicalPath
            if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                skipped += entry.relativePath
                continue
            }
            target.parentFile?.mkdirs()
            target.writeText(entry.content, Charsets.UTF_8)
            written += entry.relativePath
        }

        val triggeredAutoPush = written.isNotEmpty() &&
            project.autoPushOnSave &&
            project.githubOwner.isNotBlank() &&
            project.githubRepo.isNotBlank()

        if (triggeredAutoPush) {
            GitAutoPushWorker.enqueue(
                context = context,
                projectId = project.id,
                commitMessage = "OmniDev splitter: updated ${written.size} file(s)"
            )
        }

        return SplitApplyResult(
            writtenFiles = written,
            skippedPaths = skipped,
            triggeredAutoPush = triggeredAutoPush
        )
    }
}
