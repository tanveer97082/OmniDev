package com.omniai.dev.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omniai.dev.data.FileSyncState
import com.omniai.dev.data.GitCommitLogEntity
import com.omniai.dev.data.OmniDevDatabase
import com.omniai.dev.data.ProjectEntity
import com.omniai.dev.data.ProjectFileIndexEntity
import com.omniai.dev.data.VaultManager
import com.omniai.dev.data.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class PushProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0
)

data class AtomicPushResult(
    val commitSha: String,
    val filesChanged: Int,
    val branch: String
)

data class CreatedRepoInfo(
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val htmlUrl: String
)

data class WorkflowRunSummary(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val headSha: String,
    val htmlUrl: String,
    val createdAt: String
)

data class ReleaseApkAsset(
    val tagName: String,
    val releaseName: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class WorkspaceDiffFile(
    val relativePath: String,
    val changeType: String,
    val isBinary: Boolean,
    val lines: List<DiffLine>
)

class GitHubGitEngine(
    private val client: OkHttpClient = defaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun Request.Builder.withGitHubHeaders(token: String): Request.Builder {
        return this
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniDev-Android")
    }

    private fun executeJson(request: Request): Pair<Int, String> {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return resp.code to body
        }
    }

    private fun parseObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    suspend fun createPrivateRepo(
        token: String,
        repoName: String,
        description: String
    ): CreatedRepoInfo = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token is missing. Configure it in the Vault tab." }
        val payload = buildJsonObject {
            put("name", repoName.trim())
            put("description", description.trim())
            put("private", true)
            put("auto_init", true)
        }
        val req = Request.Builder()
            .url("https://api.github.com/user/repos")
            .withGitHubHeaders(token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val (code, body) = executeJson(req)
        if (code !in 200..299) {
            throw IOException("GitHub repo creation failed (HTTP $code): ${extractErrorMessage(body)}")
        }
        val obj = parseObject(body)
        CreatedRepoInfo(
            owner = obj["owner"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty(),
            repo = obj["name"]?.jsonPrimitive?.contentOrNull ?: repoName,
            defaultBranch = obj["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
            htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    suspend fun atomicPushProject(
        context: Context,
        db: OmniDevDatabase,
        token: String,
        project: ProjectEntity,
        commitMessage: String,
        onProgress: (PushProgress) -> Unit = {}
    ): AtomicPushResult = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token is missing. Add it in the Vault tab." }
        require(project.githubOwner.isNotBlank() && project.githubRepo.isNotBlank()) {
            "Configure GitHub Owner and Repo on this project before pushing."
        }

        val owner = project.githubOwner.trim()
        val repo = project.githubRepo.trim()
        val branch = project.githubBranch.trim().ifBlank { "main" }

        val indexed = db.dao().getFileIndexForProject(project.id)
        val allLocalFiles = WorkspaceManager.scanProjectFiles(context, project, indexed)
        val changedFiles = allLocalFiles.filter { it.state != FileSyncState.SYNCED }
        val deletedPaths = WorkspaceManager.computeDeletedPaths(context, project, indexed)

        if (changedFiles.isEmpty() && deletedPaths.isEmpty()) {
            throw IllegalStateException("No local changes detected. Working tree is already synced.")
        }

        // Step 1: GET /repos/{owner}/{repo}/git/ref/heads/{branch} (bootstrap if 404/409)
        onProgress(PushProgress("Resolving branch ref ($branch)..."))
        val baseCommitSha = resolveOrBootstrapBranchRef(token, owner, repo, branch, project.name)

        // Step 2: GET /repos/{owner}/{repo}/git/commits/{baseCommitSha} -> baseTreeSha
        onProgress(PushProgress("Fetching base tree..."))
        val commitReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/commits/$baseCommitSha")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (commitCode, commitBody) = executeJson(commitReq)
        if (commitCode !in 200..299) {
            throw IOException("Failed to fetch base commit (HTTP $commitCode): ${extractErrorMessage(commitBody)}")
        }
        val baseTreeSha = parseObject(commitBody)["tree"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Base commit response missing tree SHA.")

        // Step 3: POST /repos/{owner}/{repo}/git/blobs for every added/modified file
        val uploadedBlobs = mutableListOf<Pair<String, String>>()
        changedFiles.forEachIndexed { index, item ->
            onProgress(PushProgress("Uploading ${item.relativePath}", index + 1, changedFiles.size))
            val encoded = Base64.encodeToString(item.file.readBytes(), Base64.NO_WRAP)
            val blobPayload = buildJsonObject {
                put("content", encoded)
                put("encoding", "base64")
            }
            val blobReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/blobs")
                .withGitHubHeaders(token)
                .post(blobPayload.toString().toRequestBody(jsonMediaType))
                .build()
            val (blobCode, blobBody) = executeJson(blobReq)
            if (blobCode !in 200..299) {
                throw IOException("Blob upload failed for ${item.relativePath} (HTTP $blobCode): ${extractErrorMessage(blobBody)}")
            }
            val blobSha = parseObject(blobBody)["sha"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Blob SHA missing for ${item.relativePath}")
            uploadedBlobs += item.relativePath to blobSha
        }

        // Step 4: POST /repos/{owner}/{repo}/git/trees (base_tree, mode=100644, sha=null for deleted)
        onProgress(PushProgress("Building atomic Git tree..."))
        val treePayload = buildJsonObject {
            put("base_tree", baseTreeSha)
            put("tree", buildJsonArray {
                uploadedBlobs.forEach { (path, sha) ->
                    add(buildJsonObject {
                        put("path", path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", sha)
                    })
                }
                deletedPaths.forEach { path ->
                    add(buildJsonObject {
                        put("path", path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", JsonNull)
                    })
                }
            })
        }
        val treeReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/trees")
            .withGitHubHeaders(token)
            .post(treePayload.toString().toRequestBody(jsonMediaType))
            .build()
        val (treeCode, treeBody) = executeJson(treeReq)
        if (treeCode !in 200..299) {
            throw IOException("Tree creation failed (HTTP $treeCode): ${extractErrorMessage(treeBody)}")
        }
        val newTreeSha = parseObject(treeBody)["sha"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Tree response missing SHA.")

        // Step 5: POST /repos/{owner}/{repo}/git/commits -> newCommitSha
        onProgress(PushProgress("Creating atomic commit..."))
        val newCommitPayload = buildJsonObject {
            put("message", commitMessage.trim().ifBlank { "OmniDev atomic sync (${changedFiles.size + deletedPaths.size} files)" })
            put("tree", newTreeSha)
            put("parents", buildJsonArray { add(baseCommitSha) })
        }
        val newCommitReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/commits")
            .withGitHubHeaders(token)
            .post(newCommitPayload.toString().toRequestBody(jsonMediaType))
            .build()
        val (newCommitCode, newCommitBody) = executeJson(newCommitReq)
        if (newCommitCode !in 200..299) {
            throw IOException("Commit creation failed (HTTP $newCommitCode): ${extractErrorMessage(newCommitBody)}")
        }
        val newCommitSha = parseObject(newCommitBody)["sha"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Commit response missing SHA.")

        // Step 6: PATCH /repos/{owner}/{repo}/git/refs/heads/{branch}
        onProgress(PushProgress("Updating heads/$branch..."))
        val patchPayload = buildJsonObject {
            put("sha", newCommitSha)
            put("force", false)
        }
        val patchReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch")
            .withGitHubHeaders(token)
            .patch(patchPayload.toString().toRequestBody(jsonMediaType))
            .build()
        val (patchCode, patchBody) = executeJson(patchReq)
        if (patchCode !in 200..299) {
            throw IOException("Ref update failed (HTTP $patchCode): ${extractErrorMessage(patchBody)}")
        }

        // Refresh ProjectFileIndexEntity & ProjectEntity
        val now = System.currentTimeMillis()
        db.dao().clearFileIndexForProject(project.id)
        db.dao().upsertFileIndices(
            allLocalFiles.map { item ->
                ProjectFileIndexEntity(
                    projectId = project.id,
                    relativePath = item.relativePath,
                    sha256 = item.sha256,
                    updatedAt = now
                )
            }
        )
        db.dao().updateProject(
            project.copy(
                lastCommitSha = newCommitSha,
                lastPushedAt = now
            )
        )
        val totalChanged = changedFiles.size + deletedPaths.size
        db.dao().insertCommitLog(
            GitCommitLogEntity(
                projectId = project.id,
                commitSha = newCommitSha,
                message = commitMessage.trim().ifBlank { "OmniDev atomic sync ($totalChanged files)" },
                filesChanged = totalChanged,
                pushedAt = now
            )
        )

        AtomicPushResult(
            commitSha = newCommitSha,
            filesChanged = totalChanged,
            branch = branch
        )
    }

    private fun resolveOrBootstrapBranchRef(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        projectName: String
    ): String {
        val refUrl = "https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch"
        val getReq = Request.Builder()
            .url(refUrl)
            .withGitHubHeaders(token)
            .get()
            .build()
        val (code, body) = executeJson(getReq)
        if (code in 200..299) {
            return parseObject(body)["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Ref response missing commit SHA.")
        }

        if (code == 404 || code == 409) {
            val readmeB64 = Base64.encodeToString(
                "# $projectName\n\nInitialized by OmniDev.\n".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val bootstrapPayload = buildJsonObject {
                put("message", "chore: bootstrap repository with README.md")
                put("content", readmeB64)
                put("branch", branch)
            }
            val putReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/README.md")
                .withGitHubHeaders(token)
                .put(bootstrapPayload.toString().toRequestBody(jsonMediaType))
                .build()
            val (putCode, putBody) = executeJson(putReq)
            if (putCode !in 200..299) {
                throw IOException("Empty-repo bootstrap failed (HTTP $putCode): ${extractErrorMessage(putBody)}")
            }
            val retryReq = Request.Builder()
                .url(refUrl)
                .withGitHubHeaders(token)
                .get()
                .build()
            val (retryCode, retryBody) = executeJson(retryReq)
            if (retryCode !in 200..299) {
                throw IOException("Ref resolution after bootstrap failed (HTTP $retryCode): ${extractErrorMessage(retryBody)}")
            }
            return parseObject(retryBody)["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Bootstrapped ref missing commit SHA.")
        }

        throw IOException("Unable to read branch ref (HTTP $code): ${extractErrorMessage(body)}")
    }

    suspend fun pullProjectFromGitHub(
        context: Context,
        db: OmniDevDatabase,
        token: String,
        project: ProjectEntity,
        onProgress: (PushProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token is missing. Configure it in the Vault tab." }
        val owner = project.githubOwner.trim()
        val repo = project.githubRepo.trim()
        val branch = project.githubBranch.trim().ifBlank { "main" }
        require(owner.isNotBlank() && repo.isNotBlank()) { "Set GitHub Owner & Repo first." }

        onProgress(PushProgress("Fetching remote ref..."))
        val refReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (refCode, refBody) = executeJson(refReq)
        if (refCode !in 200..299) {
            throw IOException("Pull failed reading branch $branch (HTTP $refCode): ${extractErrorMessage(refBody)}")
        }
        val commitSha = parseObject(refBody)["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Missing commit SHA on remote ref.")

        onProgress(PushProgress("Reading remote file tree..."))
        val treeReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/trees/$commitSha?recursive=1")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (treeCode, treeBody) = executeJson(treeReq)
        if (treeCode !in 200..299) {
            throw IOException("Failed to fetch remote tree (HTTP $treeCode): ${extractErrorMessage(treeBody)}")
        }

        val entries = parseObject(treeBody)["tree"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "blob" }

        val root = WorkspaceManager.projectRoot(context, project.slug)
        val canonicalRoot = root.canonicalPath
        val newIndices = mutableListOf<ProjectFileIndexEntity>()
        val now = System.currentTimeMillis()
        var downloadedCount = 0

        entries.forEachIndexed { idx, entry ->
            val path = entry["path"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            val sha = entry["sha"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            val target = File(root, path)
            if (!target.canonicalPath.startsWith(canonicalRoot + File.separator)) return@forEachIndexed

            onProgress(PushProgress("Pulling $path", idx + 1, entries.size))
            val blobReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/git/blobs/$sha")
                .withGitHubHeaders(token)
                .get()
                .build()
            val (bCode, bBody) = executeJson(blobReq)
            if (bCode in 200..299) {
                val bObj = parseObject(bBody)
                val b64 = bObj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", "")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                newIndices += ProjectFileIndexEntity(
                    projectId = project.id,
                    relativePath = path,
                    sha256 = WorkspaceManager.sha256(bytes),
                    updatedAt = now
                )
                downloadedCount++
            }
        }

        db.dao().clearFileIndexForProject(project.id)
        db.dao().upsertFileIndices(newIndices)
        db.dao().updateProject(project.copy(lastCommitSha = commitSha, lastPushedAt = now))
        downloadedCount
    }

    suspend fun fetchWorkspaceDiff(
        context: Context,
        db: OmniDevDatabase,
        token: String,
        project: ProjectEntity
    ): List<WorkspaceDiffFile> = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Unlock the Vault and configure a GitHub token to review remote diffs." }
        require(project.githubOwner.isNotBlank() && project.githubRepo.isNotBlank()) {
            "Configure the GitHub owner and repository before reviewing diffs."
        }
        val repositoryRequest = Request.Builder()
            .url("https://api.github.com/repos/${project.githubOwner.trim()}/${project.githubRepo.trim()}")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (repositoryCode, repositoryBody) = executeJson(repositoryRequest)
        if (repositoryCode !in 200..299) {
            throw IOException("GitHub repository is not accessible (HTTP $repositoryCode): ${extractErrorMessage(repositoryBody)}")
        }
        val branch = project.githubBranch.trim().ifBlank { "main" }
        val branchRequest = Request.Builder()
            .url("https://api.github.com/repos/${project.githubOwner.trim()}/${project.githubRepo.trim()}/branches/${Uri.encode(branch)}")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (branchCode, branchBody) = executeJson(branchRequest)
        if (branchCode !in 200..299) {
            throw IOException("GitHub branch '$branch' is not accessible (HTTP $branchCode): ${extractErrorMessage(branchBody)}")
        }

        val indexed = db.dao().getFileIndexForProject(project.id)
        val localFiles = WorkspaceManager.scanProjectFiles(context, project, indexed)
        val changedFiles = localFiles.filter { it.state != FileSyncState.SYNCED }
        val deletedPaths = WorkspaceManager.computeDeletedPaths(context, project, indexed)
        val paths = (changedFiles.map { it.relativePath } + deletedPaths).distinct().sorted()
        require(paths.size <= 30) {
            "Diff review is limited to 30 changed files per request. Review or push a smaller set first."
        }

        paths.mapNotNull { relativePath ->
            val root = WorkspaceManager.projectRoot(context, project.slug)
            val localFile = File(root, relativePath)
            require(localFile.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                "Indexed path escapes the project workspace."
            }
            val localBytes = if (localFile.isFile) localFile.readBytes() else null
            val remoteBytes = fetchRemoteFileBytes(
                token = token,
                owner = project.githubOwner.trim(),
                repo = project.githubRepo.trim(),
                branch = branch,
                relativePath = relativePath
            )
            if (localBytes == null && remoteBytes == null) return@mapNotNull null
            if (localBytes != null && remoteBytes != null && localBytes.contentEquals(remoteBytes)) {
                return@mapNotNull null
            }

            val changeType = when {
                localBytes == null -> "DELETED"
                remoteBytes == null -> "ADDED"
                else -> "MODIFIED"
            }
            val oldText = remoteBytes?.let(::decodeUtf8)
            val newText = localBytes?.let(::decodeUtf8)
            val isBinary = (remoteBytes != null && oldText == null) ||
                (localBytes != null && newText == null)
            WorkspaceDiffFile(
                relativePath = relativePath,
                changeType = changeType,
                isBinary = isBinary,
                lines = if (isBinary) {
                    emptyList()
                } else {
                    AiBuildDoctor.computeLineDiff(oldText.orEmpty(), newText.orEmpty())
                }
            )
        }
    }

    private fun fetchRemoteFileBytes(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        relativePath: String
    ): ByteArray? {
        val encodedPath = Uri.encode(relativePath, "/")
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$encodedPath?ref=${Uri.encode(branch)}")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (code, body) = executeJson(request)
        if (code == 404) return null
        if (code !in 200..299) {
            throw IOException("Could not fetch remote file '$relativePath' (HTTP $code): ${extractErrorMessage(body)}")
        }
        val obj = parseObject(body)
        val encoding = obj["encoding"]?.jsonPrimitive?.contentOrNull
        require(encoding == "base64") {
            "GitHub did not return file content for '$relativePath'; large files cannot be diffed."
        }
        val encodedContent = obj["content"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Remote file '$relativePath' has no content.")
        return Base64.decode(encodedContent.filterNot { it.isWhitespace() }, Base64.DEFAULT)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    suspend fun fetchRecentWorkflowRuns(
        token: String,
        owner: String,
        repo: String
    ): List<WorkflowRunSummary> = withContext(Dispatchers.IO) {
        if (token.isBlank() || owner.isBlank() || repo.isBlank()) return@withContext emptyList()
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=5")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (code, body) = executeJson(req)
        if (code !in 200..299) {
            throw IOException("Actions fetch failed (HTTP $code): ${extractErrorMessage(body)}")
        }

        val runs = parseObject(body)["workflow_runs"]?.jsonArray.orEmpty()
        runs.map { el ->
            val obj = el.jsonObject
            WorkflowRunSummary(
                id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Workflow",
                status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull,
                headSha = obj["head_sha"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
    }

    suspend fun dispatchBuildWorkflow(
        token: String,
        owner: String,
        repo: String,
        branch: String
    ) = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token is missing. Add one with Actions workflow permissions in the Vault." }
        require(owner.isNotBlank() && repo.isNotBlank()) {
            "Configure the GitHub owner and repository before starting a build."
        }
        val payload = buildJsonObject {
            put("ref", branch.trim().ifBlank { "main" })
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/${owner.trim()}/${repo.trim()}/actions/workflows/build-release-apk.yml/dispatches")
            .withGitHubHeaders(token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        val (code, body) = executeJson(request)
        if (code != 204) {
            throw IOException("Could not start the GitHub APK build (HTTP $code): ${extractErrorMessage(body)}")
        }
    }

    suspend fun fetchLatestReleaseApk(
        token: String,
        owner: String,
        repo: String
    ): ReleaseApkAsset? = withContext(Dispatchers.IO) {
        require(token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()) {
            "Configure GitHub token, owner, and repo first."
        }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .withGitHubHeaders(token)
            .get()
            .build()
        val (code, body) = executeJson(req)
        if (code == 404) return@withContext null
        if (code !in 200..299) {
            throw IOException("Release lookup failed (HTTP $code): ${extractErrorMessage(body)}")
        }
        val obj = parseObject(body)
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: "latest"
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: tag
        val assets = obj["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        val apkAsset = assets.firstOrNull {
            it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
        } ?: return@withContext null

        val apiAssetUrl = apkAsset["url"]?.jsonPrimitive?.contentOrNull
            ?: apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext null

        ReleaseApkAsset(
            tagName = tag,
            releaseName = name,
            assetName = apkAsset["name"]?.jsonPrimitive?.contentOrNull ?: "update.apk",
            downloadUrl = apiAssetUrl,
            sizeBytes = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L
        )
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        token: String,
        asset: ReleaseApkAsset
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, asset.assetName)

        val req = Request.Builder()
            .url(asset.downloadUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OmniDev-Android")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("APK download failed (HTTP ${resp.code})")
            }
            val bodyBytes = resp.body?.bytes() ?: throw IOException("Empty APK stream")
            apkFile.writeBytes(bodyBytes)
        }

        withContext(Dispatchers.Main) {
            launchPackageInstaller(context, apkFile)
        }
        apkFile
    }

    private fun launchPackageInstaller(context: Context, apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    private fun extractErrorMessage(body: String): String {
        return runCatching {
            parseObject(body)["message"]?.jsonPrimitive?.contentOrNull ?: body.take(180)
        }.getOrElse { body.take(180) }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

class GitAutoPushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
        if (projectId <= 0L) return Result.failure()
        val message = inputData.getString(KEY_COMMIT_MSG) ?: "OmniDev auto-push"

        val db = OmniDevDatabase.getInstance(applicationContext)
        val project = db.dao().getProjectById(projectId) ?: return Result.failure()
        val vault = VaultManager(applicationContext).load()
        if (vault.githubToken.isBlank()) return Result.failure()

        val indexed = db.dao().getFileIndexForProject(project.id)
        val modified = WorkspaceManager.scanProjectFiles(applicationContext, project, indexed)
            .filter { it.state != FileSyncState.SYNCED }
        val leaks = SecretLeakScanner.scanFiles(modified)
        if (leaks.isNotEmpty()) {
            return Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR, "Blocked auto-push: ${leaks.size} potential secret(s) detected.")
                    .build()
            )
        }

        return try {
            GitHubGitEngine().atomicPushProject(
                context = applicationContext,
                db = db,
                token = vault.githubToken,
                project = project,
                commitMessage = message
            )
            Result.success()
        } catch (e: Exception) {
            Result.failure(
                Data.Builder().putString(KEY_ERROR, e.message ?: "Auto-push failed").build()
            )
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_COMMIT_MSG = "commit_msg"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, projectId: Long, commitMessage: String) {
            val data = Data.Builder()
                .putLong(KEY_PROJECT_ID, projectId)
                .putString(KEY_COMMIT_MSG, commitMessage)
                .build()
            val req = OneTimeWorkRequestBuilder<GitAutoPushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "omnidev_autopush_$projectId",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
