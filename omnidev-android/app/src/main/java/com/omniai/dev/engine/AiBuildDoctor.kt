package com.omniai.dev.engine

import android.content.Context
import com.omniai.dev.data.ProjectEntity
import com.omniai.dev.data.VaultConfig
import com.omniai.dev.data.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

data class DiffLine(
    val kind: DiffLineKind,
    val oldLineNo: Int?,
    val newLineNo: Int?,
    val text: String
)

data class BuildDoctorDiagnosis(
    val runId: Long,
    val failedJobName: String,
    val extractedErrors: String,
    val targetRelativePath: String,
    val explanation: String,
    val originalContent: String,
    val proposedContent: String,
    val diffLines: List<DiffLine>
)

class AiBuildDoctor(
    private val client: OkHttpClient = GitHubGitEngine.defaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun diagnoseFailedRun(
        context: Context,
        project: ProjectEntity,
        vault: VaultConfig,
        runId: Long
    ): BuildDoctorDiagnosis = withContext(Dispatchers.IO) {
        require(vault.githubToken.isNotBlank()) { "GitHub token required in Vault." }
        require(vault.aiApiKey.isNotBlank() && vault.aiEndpoint.isNotBlank()) {
            "Configure AI Endpoint & API Key in the Vault tab to run AI Build-Doctor."
        }
        val owner = project.githubOwner.trim()
        val repo = project.githubRepo.trim()

        // 1. Fetch jobs for the failed workflow run
        val jobsReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs")
            .header("Authorization", "Bearer ${vault.githubToken}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniDev-Android")
            .get()
            .build()

        val jobsBody = client.newCall(jobsReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Failed to fetch Actions jobs (HTTP ${resp.code})")
            raw
        }

        val jobs = json.parseToJsonElement(jobsBody).jsonObject["jobs"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
        val failedJob = jobs.firstOrNull {
            it["conclusion"]?.jsonPrimitive?.contentOrNull == "failure"
        } ?: jobs.firstOrNull() ?: throw IOException("No jobs found for workflow run #$runId")

        val jobId = failedJob["id"]?.jsonPrimitive?.longOrNull
            ?: throw IOException("Missing failed job ID.")
        val jobName = failedJob["name"]?.jsonPrimitive?.contentOrNull ?: "build-apk"

        // 2. Fetch raw job logs
        val logsReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/jobs/$jobId/logs")
            .header("Authorization", "Bearer ${vault.githubToken}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniDev-Android")
            .get()
            .build()

        val rawLogs = client.newCall(logsReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Failed to fetch job logs (HTTP ${resp.code})")
            raw
        }

        // 3. Extract Kotlin/Gradle compiler errors & locate matching local file
        val extractedErrors = extractCompilerErrors(rawLogs)
        val root = WorkspaceManager.projectRoot(context, project.slug)
        val matchedRelPath = findMatchingLocalFile(root, extractedErrors)
            ?: throw IOException(
                "Could not map compiler error to a local project file.\n\nExtracted log:\n$extractedErrors"
            )

        val localFile = File(root, matchedRelPath)
        val originalContent = localFile.readText(Charsets.UTF_8)

        // 4. Ask configured AI endpoint for the exact fixed file
        val prompt = buildString {
            appendLine("You are an expert Android & Kotlin compiler engineer.")
            appendLine("A GitHub Actions Gradle build failed with these compiler errors:")
            appendLine("```")
            appendLine(extractedErrors.take(3500))
            appendLine("```")
            appendLine("File path: $matchedRelPath")
            appendLine("Current file content:")
            appendLine("```kotlin")
            appendLine(originalContent)
            appendLine("```")
            appendLine("Respond with ONLY two sections:")
            appendLine("EXPLANATION: <one concise sentence describing the root cause and fix>")
            appendLine("### FIXED_FILE")
            appendLine("<the complete corrected file content without markdown fences>")
        }

        val aiResponse = callAiEndpoint(vault, prompt)
        val (explanation, proposedContent) = parseAiFixResponse(aiResponse, originalContent)
        val diff = computeLineDiff(originalContent, proposedContent)

        BuildDoctorDiagnosis(
            runId = runId,
            failedJobName = jobName,
            extractedErrors = extractedErrors,
            targetRelativePath = matchedRelPath,
            explanation = explanation,
            originalContent = originalContent,
            proposedContent = proposedContent,
            diffLines = diff
        )
    }

    suspend fun generateCommitMessage(
        vault: VaultConfig,
        changedPaths: List<String>
    ): String = withContext(Dispatchers.IO) {
        if (vault.aiApiKey.isBlank() || vault.aiEndpoint.isBlank()) {
            return@withContext "chore: update ${changedPaths.size} file(s)"
        }
        val prompt = "Write one concise conventional commit message (max 68 chars, no quotes) for changes to these files: ${changedPaths.take(15).joinToString(", ")}"
        runCatching {
            callAiEndpoint(vault, prompt).lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '`')
                ?: "chore: update ${changedPaths.size} file(s)"
        }.getOrElse {
            "chore: update ${changedPaths.size} file(s)"
        }
    }

    private fun callAiEndpoint(vault: VaultConfig, prompt: String): String {
        val endpoint = vault.aiEndpoint.trim()
        val isNativeGemini = endpoint.contains("generateContent")

        val request = if (isNativeGemini) {
            val urlWithKey = if (endpoint.contains("key=")) endpoint else {
                val sep = if (endpoint.contains("?")) "&" else "?"
                "$endpoint${sep}key=${vault.aiApiKey}"
            }
            val body = buildJsonObject {
                put("contents", buildJsonArray {
                    addJsonObject {
                        put("parts", buildJsonArray {
                            addJsonObject { put("text", prompt) }
                        })
                    }
                })
            }
            Request.Builder()
                .url(urlWithKey)
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        } else {
            val body = buildJsonObject {
                put("model", vault.aiModel.ifBlank { "gemini-2.0-flash" })
                put("temperature", 0.1)
                put("messages", buildJsonArray {
                    addJsonObject {
                        put("role", "system")
                        put("content", "You are a precise Android/Kotlin build engineer.")
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                })
            }
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${vault.aiApiKey}")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
        }

        val rawResp = client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("AI API failed (HTTP ${resp.code}): ${raw.take(220)}")
            }
            raw
        }

        val obj = json.parseToJsonElement(rawResp).jsonObject
        return if (isNativeGemini) {
            obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    private fun extractCompilerErrors(rawLogs: String): String {
        val lines = rawLogs.lines()
        val errorLines = lines.filter { line ->
            line.contains("e: file://", ignoreCase = true) ||
                line.contains(".kt:", ignoreCase = true) ||
                line.contains(".kts:", ignoreCase = true) ||
                line.contains(".xml:", ignoreCase = true) ||
                line.contains("Unresolved reference", ignoreCase = true) ||
                line.contains("FAILURE:", ignoreCase = true) ||
                line.contains("Execution failed for task", ignoreCase = true) ||
                line.contains("AAPT:", ignoreCase = true)
        }
        return if (errorLines.isNotEmpty()) {
            errorLines.takeLast(45).joinToString("\n")
        } else {
            lines.takeLast(60).joinToString("\n")
        }
    }

    private fun findMatchingLocalFile(root: File, errorLog: String): String? {
        if (!root.exists()) return null
        val localPaths = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toList()

        // Prefer exact relative path match inside log
        localPaths.firstOrNull { path -> errorLog.contains(path) }?.let { return it }

        // Fallback: match by Kotlin/Gradle/XML filename
        val fileRefRegex = Regex("""([A-Za-z0-9_\-]+\.(?:kt|kts|xml|java|yml))""")
        val mentionedNames = fileRefRegex.findAll(errorLog).map { it.groupValues[1] }.toList()
        for (name in mentionedNames) {
            localPaths.firstOrNull { it.endsWith("/$name") || it == name }?.let { return it }
        }
        return localPaths.firstOrNull { it.endsWith(".kt") || it.endsWith("build.gradle.kts") }
    }

    private fun parseAiFixResponse(aiResponse: String, fallback: String): Pair<String, String> {
        val explanationLine = aiResponse.lines()
            .firstOrNull { it.trimStart().startsWith("EXPLANATION:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
            ?: "Applied compiler fix generated by AI Build-Doctor."

        val markerIndex = aiResponse.indexOf("### FIXED_FILE")
        var code = if (markerIndex >= 0) {
            aiResponse.substring(markerIndex + "### FIXED_FILE".length).trim()
        } else {
            aiResponse.trim()
        }

        val codeLines = code.lines().toMutableList()
        if (codeLines.size >= 2 && codeLines.first().trimStart().startsWith("```")) {
            codeLines.removeAt(0)
            if (codeLines.last().trim() == "```") codeLines.removeAt(codeLines.lastIndex)
            code = codeLines.joinToString("\n")
        }
        val finalCode = if (code.isBlank()) fallback else "$code\n"
        return explanationLine to finalCode
    }

    companion object {
        fun computeLineDiff(original: String, updated: String): List<DiffLine> {
            val a = original.replace("\r\n", "\n").lines()
            val b = updated.replace("\r\n", "\n").lines()
            val n = a.size
            val m = b.size

            // Guard LCS memory for very large files by doing simple line comparison
            if (n * m > 400_000) {
                val simple = mutableListOf<DiffLine>()
                val maxLen = maxOf(n, m)
                for (i in 0 until maxLen) {
                    val oldL = a.getOrNull(i)
                    val newL = b.getOrNull(i)
                    if (oldL == newL && oldL != null) {
                        simple += DiffLine(DiffLineKind.CONTEXT, i + 1, i + 1, oldL)
                    } else {
                        if (oldL != null) simple += DiffLine(DiffLineKind.REMOVED, i + 1, null, oldL)
                        if (newL != null) simple += DiffLine(DiffLineKind.ADDED, null, i + 1, newL)
                    }
                }
                return simple
            }

            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (a[i] == b[j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }

            val result = mutableListOf<DiffLine>()
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[i] == b[j] -> {
                        result += DiffLine(DiffLineKind.CONTEXT, i + 1, j + 1, a[i])
                        i++; j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        result += DiffLine(DiffLineKind.REMOVED, i + 1, null, a[i])
                        i++
                    }
                    else -> {
                        result += DiffLine(DiffLineKind.ADDED, null, j + 1, b[j])
                        j++
                    }
                }
            }
            while (i < n) {
                result += DiffLine(DiffLineKind.REMOVED, i + 1, null, a[i])
                i++
            }
            while (j < m) {
                result += DiffLine(DiffLineKind.ADDED, null, j + 1, b[j])
                j++
            }
            return result
        }
    }
}
