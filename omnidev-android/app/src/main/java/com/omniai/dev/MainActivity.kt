package com.omniai.dev

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omniai.dev.data.FileSyncState
import com.omniai.dev.data.GitCommitLogEntity
import com.omniai.dev.data.OmniBackupCrypto
import com.omniai.dev.data.OmniDevDatabase
import com.omniai.dev.data.OmniSuiteBackupPayload
import com.omniai.dev.data.ProjectEntity
import com.omniai.dev.data.ProjectFileIndexEntity
import com.omniai.dev.data.ProjectTaskEntity
import com.omniai.dev.data.TerminalSnippetEntity
import com.omniai.dev.data.VaultConfig
import com.omniai.dev.data.VaultManager
import com.omniai.dev.data.WorkspaceFileItem
import com.omniai.dev.data.WorkspaceManager
import com.omniai.dev.engine.AiBuildDoctor
import com.omniai.dev.engine.AiMultiFileSplitter
import com.omniai.dev.engine.BuildDoctorDiagnosis
import com.omniai.dev.engine.GitAutoPushWorker
import com.omniai.dev.engine.GitHubGitEngine
import com.omniai.dev.engine.PushProgress
import com.omniai.dev.engine.ReleaseApkAsset
import com.omniai.dev.engine.SecretLeakFinding
import com.omniai.dev.engine.SecretLeakScanner
import com.omniai.dev.engine.WorkflowRunSummary
import com.omniai.dev.service.LocalLanWebServer
import com.omniai.dev.ui.DiffReviewDialog
import com.omniai.dev.ui.EditorScreen
import com.omniai.dev.ui.ProjectsScreen
import com.omniai.dev.ui.SecretLeakAlertDialog
import com.omniai.dev.ui.TerminalScreen
import com.omniai.dev.ui.VaultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object OmniColors {
    val Background = Color(0xFF09090C)
    val Card = Color(0xFF13131A)
    val Border = Color(0xFF232330)
    val Cyan = Color(0xFF00E5FF)
    val Purple = Color(0xFFBB86FC)
    val TextPrimary = Color(0xFFEDEDF5)
    val TextSecondary = Color(0xFF9898AE)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
}

private val OmniDarkScheme = darkColorScheme(
    primary = OmniColors.Cyan,
    onPrimary = OmniColors.Background,
    secondary = OmniColors.Purple,
    onSecondary = OmniColors.Background,
    background = OmniColors.Background,
    onBackground = OmniColors.TextPrimary,
    surface = OmniColors.Card,
    onSurface = OmniColors.TextPrimary,
    surfaceVariant = Color(0xFF1A1A24),
    onSurfaceVariant = OmniColors.TextSecondary,
    outline = OmniColors.Border,
    error = OmniColors.Danger,
    onError = Color.White
)

@Composable
fun OmniDevTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmniDarkScheme,
        content = content
    )
}

enum class OmniTab(val label: String) {
    PROJECTS("Projects"),
    EDITOR("Editor"),
    TERMINAL("Terminal"),
    VAULT("Vault")
}

data class TerminalEntry(
    val text: String,
    val isError: Boolean = false,
    val isCommand: Boolean = false
)

class OmniDevViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val db = OmniDevDatabase.getInstance(appContext)
    private val dao = db.dao()
    private val vaultManager = VaultManager(appContext)
    private val gitEngine = GitHubGitEngine()
    private val buildDoctor = AiBuildDoctor()

    val projects: StateFlow<List<ProjectEntity>> = dao.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTasks: StateFlow<List<ProjectTaskEntity>> = dao.observeAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFileIndices: StateFlow<List<ProjectFileIndexEntity>> = dao.observeAllFileIndices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val snippets: StateFlow<List<TerminalSnippetEntity>> = dao.observeSnippets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lanStatus = LocalLanWebServer.status

    private val _selectedTab = MutableStateFlow(OmniTab.PROJECTS)
    val selectedTab: StateFlow<OmniTab> = _selectedTab.asStateFlow()

    private val _activeProjectId = MutableStateFlow<Long?>(null)
    val activeProjectId: StateFlow<Long?> = _activeProjectId.asStateFlow()

    private val _openedProjectDetailId = MutableStateFlow<Long?>(null)
    val openedProjectDetailId: StateFlow<Long?> = _openedProjectDetailId.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFileItem>>(emptyList())
    val workspaceFiles: StateFlow<List<WorkspaceFileItem>> = _workspaceFiles.asStateFlow()

    private val _workflowRuns = MutableStateFlow<List<WorkflowRunSummary>>(emptyList())
    val workflowRuns: StateFlow<List<WorkflowRunSummary>> = _workflowRuns.asStateFlow()

    private val _commitLogs = MutableStateFlow<List<GitCommitLogEntity>>(emptyList())
    val commitLogs: StateFlow<List<GitCommitLogEntity>> = _commitLogs.asStateFlow()

    private val _isVaultUnlocked = MutableStateFlow(false)
    val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

    private val _vaultConfig = MutableStateFlow(VaultConfig())
    val vaultConfig: StateFlow<VaultConfig> = _vaultConfig.asStateFlow()

    private fun readVaultConfig(): VaultConfig {
        return if (_isVaultUnlocked.value) _vaultConfig.value else vaultManager.load()
    }

    fun unlockVault() {
        _vaultConfig.value = vaultManager.load()
        _isVaultUnlocked.value = true
    }

    fun lockVault() {
        _vaultConfig.value = VaultConfig()
        _isVaultUnlocked.value = false
    }

    private val _pushProgress = MutableStateFlow<PushProgress?>(null)
    val pushProgress: StateFlow<PushProgress?> = _pushProgress.asStateFlow()

    private val _secretLeaks = MutableStateFlow<List<SecretLeakFinding>>(emptyList())
    val secretLeaks: StateFlow<List<SecretLeakFinding>> = _secretLeaks.asStateFlow()

    private val _diagnosis = MutableStateFlow<BuildDoctorDiagnosis?>(null)
    val diagnosis: StateFlow<BuildDoctorDiagnosis?> = _diagnosis.asStateFlow()

    private val _latestApk = MutableStateFlow<ReleaseApkAsset?>(null)
    val latestApk: StateFlow<ReleaseApkAsset?> = _latestApk.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _incomingSharedCode = MutableStateFlow<String?>(null)
    val incomingSharedCode: StateFlow<String?> = _incomingSharedCode.asStateFlow()

    private val _terminalLogs = MutableStateFlow(
        listOf(
            TerminalEntry("OmniDev Hybrid Shell ready. Type 'help' for OmniCLI commands."),
            TerminalEntry("Workspace isolated under app filesDir/projects/<slug>/")
        )
    )
    val terminalLogs: StateFlow<List<TerminalEntry>> = _terminalLogs.asStateFlow()

    private val _isProcessRunning = MutableStateFlow(false)
    val isProcessRunning: StateFlow<Boolean> = _isProcessRunning.asStateFlow()

    private var activeProcess: Process? = null
    private var processJob: Job? = null
    private var commitObserverJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            seedDefaultSnippetsIfNeeded()
            val existing = dao.getAllProjects()
            if (existing.isNotEmpty() && _activeProjectId.value == null) {
                selectActiveProject(existing.first().id)
            }
        }
    }

    private suspend fun seedDefaultSnippetsIfNeeded() {
        if (dao.countSnippets() == 0) {
            listOf(
                TerminalSnippetEntity(label = "Git Status", command = "git status", useTermux = false),
                TerminalSnippetEntity(label = "Actions Watch", command = "git actions", useTermux = false),
                TerminalSnippetEntity(label = "Start LAN Studio", command = "lan start", useTermux = false),
                TerminalSnippetEntity(label = "List Files", command = "ls -la", useTermux = false),
                TerminalSnippetEntity(label = "Termux Gradle", command = "gradle assembleDebug", useTermux = true)
            ).forEach { dao.insertSnippet(it) }
        }
    }

    fun selectTab(tab: OmniTab) {
        _selectedTab.value = tab
        if (tab == OmniTab.EDITOR) refreshActiveWorkspace()
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun emitMessage(msg: String) {
        _statusBanner.value = msg
    }

    fun receiveSharedText(text: String) {
        _incomingSharedCode.value = text
        _selectedTab.value = OmniTab.EDITOR
        emitMessage("Received shared text in File Splitter.")
    }

    fun consumeIncomingSharedCode() {
        _incomingSharedCode.value = null
    }

    fun selectActiveProject(projectId: Long) {
        _activeProjectId.value = projectId
        refreshActiveWorkspace()
        commitObserverJob?.cancel()
        commitObserverJob = viewModelScope.launch {
            dao.observeCommitsForProject(projectId).collect { _commitLogs.value = it }
        }
    }

    fun openProjectDetail(projectId: Long?) {
        _openedProjectDetailId.value = projectId
        if (projectId != null) {
            selectActiveProject(projectId)
            refreshActionsForProject(projectId)
        }
    }

    fun activeProject(): ProjectEntity? {
        val id = _activeProjectId.value ?: return projects.value.firstOrNull()
        return projects.value.firstOrNull { it.id == id } ?: projects.value.firstOrNull()
    }

    fun pendingChangesFor(project: ProjectEntity, allIndices: List<ProjectFileIndexEntity>): Int {
        val indexed = allIndices.filter { it.projectId == project.id }
        return WorkspaceManager.countPendingChanges(appContext, project, indexed)
    }

    fun refreshActiveWorkspace() {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = activeProject() ?: run {
                _workspaceFiles.value = emptyList()
                return@launch
            }
            val indexed = dao.getFileIndexForProject(proj.id)
            _workspaceFiles.value = WorkspaceManager.scanProjectFiles(appContext, proj, indexed)
        }
    }

    fun createProject(
        name: String,
        description: String,
        owner: String,
        repo: String,
        branch: String,
        autoPush: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val slug = WorkspaceManager.slugify(name)
            val defaultOwner = owner.ifBlank { readVaultConfig().defaultGithubOwner }
            val entity = ProjectEntity(
                name = name.trim(),
                slug = slug,
                description = description.trim(),
                githubOwner = defaultOwner.trim(),
                githubRepo = repo.trim().ifBlank { slug },
                githubBranch = branch.trim().ifBlank { "main" },
                autoPushOnSave = autoPush
            )
            val id = dao.insertProject(entity)
            val root = WorkspaceManager.projectRoot(appContext, slug)
            val readme = File(root, "README.md")
            if (!readme.exists()) {
                readme.writeText("# ${entity.name}\n\n${entity.description}\n", Charsets.UTF_8)
            }
            selectActiveProject(id)
            emitMessage("Created project '${entity.name}'")
        }
    }

    fun updateProject(project: ProjectEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateProject(project)
            emitMessage("Updated project settings.")
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteProject(project)
            WorkspaceManager.projectRoot(appContext, project.slug).deleteRecursively()
            if (_openedProjectDetailId.value == project.id) _openedProjectDetailId.value = null
            if (_activeProjectId.value == project.id) {
                _activeProjectId.value = dao.getAllProjects().firstOrNull()?.id
            }
            refreshActiveWorkspace()
            emitMessage("Deleted '${project.name}'")
        }
    }

    fun addTask(projectId: Long, title: String, priority: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertTask(
                ProjectTaskEntity(
                    projectId = projectId,
                    title = title.trim(),
                    stage = "TODO",
                    priority = priority
                )
            )
        }
    }

    fun moveTaskStage(task: ProjectTaskEntity, newStage: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateTask(task.copy(stage = newStage))
        }
    }

    fun deleteTask(task: ProjectTaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTask(task)
        }
    }

    fun createOrUpdateFile(relativePath: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = activeProject() ?: return@launch
            val clean = relativePath.trim().replace('\\', '/').removePrefix("/")
            if (clean.isBlank() || clean.contains("..")) {
                emitMessage("Invalid file path.")
                return@launch
            }
            val root = WorkspaceManager.projectRoot(appContext, proj.slug)
            val target = File(root, clean)
            if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                emitMessage("Path escapes project workspace.")
                return@launch
            }
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            refreshActiveWorkspace()

            if (proj.autoPushOnSave && proj.githubOwner.isNotBlank() && proj.githubRepo.isNotBlank()) {
                GitAutoPushWorker.enqueue(appContext, proj.id, "feat: save $clean")
                emitMessage("Saved $clean (Auto-Push queued)")
            } else {
                emitMessage("Saved $clean")
            }
        }
    }

    fun deleteWorkspaceFile(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = activeProject() ?: return@launch
            val root = WorkspaceManager.projectRoot(appContext, proj.slug)
            val target = File(root, relativePath)
            if (target.exists() && target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                target.delete()
                refreshActiveWorkspace()
                emitMessage("Deleted $relativePath")
            }
        }
    }

    fun applyMultiFileSplit(rawInput: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = activeProject() ?: run {
                emitMessage("Create or select a project first.")
                return@launch
            }
            val result = AiMultiFileSplitter.applyToProject(appContext, proj, rawInput)
            refreshActiveWorkspace()
            if (result.writtenFiles.isEmpty()) {
                emitMessage("No ### FILE: blocks found in input.")
            } else {
                val suffix = if (result.triggeredAutoPush) " • Auto-Push queued" else ""
                emitMessage("Wrote ${result.writtenFiles.size} file(s) into ${proj.name}$suffix")
            }
        }
    }

    fun createRemotePrivateRepo(project: ProjectEntity) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val vault = readVaultConfig()
                val info = gitEngine.createPrivateRepo(
                    token = vault.githubToken,
                    repoName = project.githubRepo.ifBlank { project.slug },
                    description = project.description
                )
                withContext(Dispatchers.IO) {
                    dao.updateProject(
                        project.copy(
                            githubOwner = info.owner,
                            githubRepo = info.repo,
                            githubBranch = info.defaultBranch
                        )
                    )
                }
                emitMessage("Created private repo ${info.owner}/${info.repo}")
            } catch (e: Exception) {
                emitMessage(e.message ?: "Failed to create GitHub repo")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun pushProjectAtomic(project: ProjectEntity, customMessage: String? = null, useAiMessage: Boolean = false) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val vault = readVaultConfig()
                val indexed = withContext(Dispatchers.IO) { dao.getFileIndexForProject(project.id) }
                val modifiedFiles = withContext(Dispatchers.IO) {
                    WorkspaceManager.scanProjectFiles(appContext, project, indexed)
                        .filter { it.state != FileSyncState.SYNCED }
                }

                // Mandatory Pre-Push Secret Guardian
                val leaks = withContext(Dispatchers.IO) { SecretLeakScanner.scanFiles(modifiedFiles) }
                if (leaks.isNotEmpty()) {
                    _secretLeaks.value = leaks
                    emitMessage("Push blocked: ${leaks.size} secret leak(s) found.")
                    return@launch
                }

                val commitMsg = when {
                    !customMessage.isNullOrBlank() -> customMessage
                    useAiMessage -> {
                        _pushProgress.value = PushProgress("Generating AI commit message...")
                        buildDoctor.generateCommitMessage(vault, modifiedFiles.map { it.relativePath })
                    }
                    else -> "OmniDev atomic push (${modifiedFiles.size} modified)"
                }

                val res = gitEngine.atomicPushProject(
                    context = appContext,
                    db = db,
                    token = vault.githubToken,
                    project = project,
                    commitMessage = commitMsg,
                    onProgress = { _pushProgress.value = it }
                )
                refreshActiveWorkspace()
                refreshActionsForProject(project.id)
                emitMessage("Pushed ${res.filesChanged} file(s) -> ${res.commitSha.take(7)}")
            } catch (e: Exception) {
                emitMessage(e.message ?: "Atomic push failed")
            } finally {
                _pushProgress.value = null
                _isBusy.value = false
            }
        }
    }

    fun dismissSecretLeaks() {
        _secretLeaks.value = emptyList()
    }

    fun scanActiveProjectForSecrets() {
        viewModelScope.launch(Dispatchers.IO) {
            val proj = activeProject() ?: run {
                emitMessage("No active project to scan.")
                return@launch
            }
            val indexed = dao.getFileIndexForProject(proj.id)
            val allFiles = WorkspaceManager.scanProjectFiles(appContext, proj, indexed)
            val leaks = SecretLeakScanner.scanFiles(allFiles)
            if (leaks.isEmpty()) {
                emitMessage("Secret Guardian: 0 leaks across ${allFiles.size} file(s).")
            } else {
                _secretLeaks.value = leaks
            }
        }
    }

    fun pullProject(project: ProjectEntity) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val count = gitEngine.pullProjectFromGitHub(
                    context = appContext,
                    db = db,
                    token = readVaultConfig().githubToken,
                    project = project,
                    onProgress = { _pushProgress.value = it }
                )
                refreshActiveWorkspace()
                emitMessage("Pulled $count file(s) from ${project.githubOwner}/${project.githubRepo}")
            } catch (e: Exception) {
                emitMessage(e.message ?: "Pull failed")
            } finally {
                _pushProgress.value = null
                _isBusy.value = false
            }
        }
    }

    fun refreshActionsForProject(projectId: Long) {
        viewModelScope.launch {
            val proj = withContext(Dispatchers.IO) { dao.getProjectById(projectId) } ?: return@launch
            if (proj.githubOwner.isBlank() || proj.githubRepo.isBlank()) return@launch
            try {
                val vault = readVaultConfig()
                val runs = gitEngine.fetchRecentWorkflowRuns(
                    token = vault.githubToken,
                    owner = proj.githubOwner,
                    repo = proj.githubRepo
                )
                _workflowRuns.value = runs
                if (runs.firstOrNull()?.conclusion == "success") {
                    _latestApk.value = runCatching {
                        gitEngine.fetchLatestReleaseApk(
                            vault.githubToken,
                            proj.githubOwner,
                            proj.githubRepo
                        )
                    }.getOrNull()
                }
            } catch (e: Exception) {
                emitMessage(e.message ?: "Could not fetch GitHub Actions")
            }
        }
    }

    fun runBuildDoctor(project: ProjectEntity, runId: Long) {
        viewModelScope.launch {
            _isBusy.value = true
            _pushProgress.value = PushProgress("AI Build-Doctor inspecting logs...")
            try {
                val diag = buildDoctor.diagnoseFailedRun(
                    context = appContext,
                    project = project,
                    vault = readVaultConfig(),
                    runId = runId
                )
                _diagnosis.value = diag
            } catch (e: Exception) {
                emitMessage(e.message ?: "AI Build-Doctor failed")
            } finally {
                _pushProgress.value = null
                _isBusy.value = false
            }
        }
    }

    fun dismissDiagnosis() {
        _diagnosis.value = null
    }

    fun applyBuildDoctorFixAndRepush(diag: BuildDoctorDiagnosis) {
        val proj = activeProject() ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val root = WorkspaceManager.projectRoot(appContext, proj.slug)
                val target = File(root, diag.targetRelativePath)
                target.parentFile?.mkdirs()
                target.writeText(diag.proposedContent, Charsets.UTF_8)
            }
            _diagnosis.value = null
            refreshActiveWorkspace()
            pushProjectAtomic(proj, customMessage = "fix(build-doctor): ${diag.explanation.take(56)}")
        }
    }

    fun checkAndInstallLatestReleaseApk(project: ProjectEntity) {
        viewModelScope.launch {
            _isBusy.value = true
            _pushProgress.value = PushProgress("Checking GitHub Releases...")
            try {
                val token = readVaultConfig().githubToken
                val asset = gitEngine.fetchLatestReleaseApk(token, project.githubOwner, project.githubRepo)
                if (asset == null) {
                    emitMessage("No APK asset found in latest release.")
                } else {
                    _latestApk.value = asset
                    _pushProgress.value = PushProgress("Downloading ${asset.assetName}...")
                    gitEngine.downloadAndInstallApk(appContext, token, asset)
                    emitMessage("Installer launched for ${asset.assetName}")
                }
            } catch (e: Exception) {
                emitMessage(e.message ?: "OTA update failed")
            } finally {
                _pushProgress.value = null
                _isBusy.value = false
            }
        }
    }

    fun saveVaultConfig(config: VaultConfig) {
        if (!_isVaultUnlocked.value) {
            emitMessage("Unlock the Vault with biometrics before saving credentials.")
            return
        }
        vaultManager.save(config)
        _vaultConfig.value = vaultManager.load()
        emitMessage("Encrypted Vault saved.")
    }

    fun exportEncryptedBackup(uri: Uri, password: String) {
        if (!_isVaultUnlocked.value) {
            emitMessage("Unlock the Vault before exporting credentials.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = OmniSuiteBackupPayload(
                    vault = _vaultConfig.value,
                    projects = dao.getAllProjects(),
                    tasks = dao.getAllTasks()
                )
                val encrypted = OmniBackupCrypto.encryptPayload(payload, password)
                appContext.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
                    ?: throw IllegalStateException("Could not open output stream.")
                emitMessage("Exported AES-256-GCM backup (.omniai.enc)")
            } catch (e: Exception) {
                emitMessage("Backup failed: ${e.message}")
            }
        }
    }

    fun importEncryptedBackup(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read backup file.")
                val payload = OmniBackupCrypto.decryptPayload(raw, password)
                vaultManager.save(payload.vault)
                if (_isVaultUnlocked.value) {
                    _vaultConfig.value = vaultManager.load()
                }
                val idMap = mutableMapOf<Long, Long>()
                payload.projects.forEach { proj ->
                    val newId = dao.insertProject(proj.copy(id = 0))
                    idMap[proj.id] = newId
                    WorkspaceManager.projectRoot(appContext, proj.slug)
                }
                payload.tasks.forEach { task ->
                    val mappedId = idMap[task.projectId] ?: return@forEach
                    dao.insertTask(task.copy(id = 0, projectId = mappedId))
                }
                emitMessage("Restored Vault + ${payload.projects.size} project(s).")
            } catch (e: Exception) {
                emitMessage("Restore failed: ${e.message}")
            }
        }
    }

    fun saveSnippet(label: String, command: String, useTermux: Boolean) {
        if (label.isBlank() || command.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertSnippet(
                TerminalSnippetEntity(label = label.trim(), command = command.trim(), useTermux = useTermux)
            )
            emitMessage("Saved snippet '$label'")
        }
    }

    fun deleteSnippet(snippet: TerminalSnippetEntity) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteSnippet(snippet) }
    }

    fun startLanStudio() {
        val proj = activeProject() ?: run {
            emitMessage("Select an active project first.")
            return
        }
        LocalLanWebServer.start(appContext, proj.id, proj.slug)
        appendTerm("LAN Web Studio started for '${proj.slug}'.")
    }

    fun stopLanStudio() {
        LocalLanWebServer.stop(appContext)
        appendTerm("LAN Web Studio stopped.")
    }

    fun clearTerminal() {
        _terminalLogs.value = listOf(TerminalEntry("Terminal cleared."))
    }

    fun stopRunningProcess() {
        activeProcess?.destroy()
        activeProcess = null
        processJob?.cancel()
        _isProcessRunning.value = false
        appendTerm("^C Process terminated.", isError = true)
    }

    private fun appendTerm(text: String, isError: Boolean = false, isCommand: Boolean = false) {
        val current = _terminalLogs.value
        val trimmed = if (current.size > 400) current.takeLast(350) else current
        _terminalLogs.value = trimmed + TerminalEntry(text = text, isError = isError, isCommand = isCommand)
    }

    fun executeTerminalCommand(rawCommand: String, useTermux: Boolean) {
        val cmd = rawCommand.trim()
        if (cmd.isBlank()) return
        appendTerm("$ $cmd", isCommand = true)

        if (useTermux) {
            dispatchTermuxCommand(cmd)
            return
        }

        if (handleOmniCli(cmd)) return
        runLocalShellCommand(cmd)
    }

    private fun handleOmniCli(cmd: String): Boolean {
        val lower = cmd.lowercase()
        when {
            lower == "help" -> {
                appendTerm(
                    """
                    OmniCLI built-in commands:
                      help                 - Show available commands
                      proj list            - List Room projects & slugs
                      git status           - Diff active project files
                      git push "<msg>"     - Secret scan + Atomic GitHub push
                      git pull             - Pull files from remote branch
                      git actions          - Show latest GitHub Actions runs
                      git fix              - Launch AI Build-Doctor on failed run
                      lan start | lan stop - Toggle Wi-Fi Web Studio (:8787)
                      backup now           - Open Vault backup tab
                      sysinfo              - Device & OmniDev runtime info
                      clear                - Clear terminal buffer
                    """.trimIndent()
                )
                return true
            }
            lower == "clear" -> {
                clearTerminal()
                return true
            }
            lower == "proj list" -> {
                val list = projects.value
                if (list.isEmpty()) {
                    appendTerm("No projects yet. Create one in the Projects tab.")
                } else {
                    list.forEach { p ->
                        val mark = if (p.id == _activeProjectId.value) "*" else " "
                        appendTerm("[$mark] #${p.id} ${p.name} (slug=${p.slug}, repo=${p.githubOwner}/${p.githubRepo})")
                    }
                }
                return true
            }
            lower == "git status" -> {
                val proj = activeProject() ?: run {
                    appendTerm("No active project selected.", isError = true)
                    return true
                }
                viewModelScope.launch(Dispatchers.IO) {
                    val indexed = dao.getFileIndexForProject(proj.id)
                    val files = WorkspaceManager.scanProjectFiles(appContext, proj, indexed)
                    val deleted = WorkspaceManager.computeDeletedPaths(appContext, proj, indexed)
                    val changed = files.filter { it.state != FileSyncState.SYNCED }
                    appendTerm("Project: ${proj.name} (${proj.githubOwner}/${proj.githubRepo}@${proj.githubBranch})")
                    if (changed.isEmpty() && deleted.isEmpty()) {
                        appendTerm("Working tree clean (${files.size} synced file(s)).")
                    } else {
                        changed.forEach { appendTerm("  [${it.state.name}] ${it.relativePath}") }
                        deleted.forEach { appendTerm("  [DEL] $it", isError = true) }
                    }
                }
                return true
            }
            lower.startsWith("git push") -> {
                val proj = activeProject() ?: run {
                    appendTerm("No active project selected.", isError = true)
                    return true
                }
                val msg = cmd.removePrefix("git push").trim().trim('"', '\'')
                appendTerm("Starting Secret Guardian + Atomic GitHub Push...")
                pushProjectAtomic(proj, customMessage = msg.ifBlank { null })
                return true
            }
            lower == "git pull" -> {
                val proj = activeProject() ?: run {
                    appendTerm("No active project selected.", isError = true)
                    return true
                }
                appendTerm("Pulling ${proj.githubOwner}/${proj.githubRepo}@${proj.githubBranch}...")
                pullProject(proj)
                return true
            }
            lower == "git actions" -> {
                val proj = activeProject() ?: run {
                    appendTerm("No active project selected.", isError = true)
                    return true
                }
                viewModelScope.launch {
                    try {
                        val runs = gitEngine.fetchRecentWorkflowRuns(
                            readVaultConfig().githubToken,
                            proj.githubOwner,
                            proj.githubRepo
                        )
                        _workflowRuns.value = runs
                        if (runs.isEmpty()) {
                            appendTerm("No workflow runs found.")
                        } else {
                            runs.forEach { r ->
                                appendTerm("#${r.id} [${r.status}/${r.conclusion ?: "running"}] ${r.name} (${r.headSha.take(7)})")
                            }
                        }
                    } catch (e: Exception) {
                        appendTerm(e.message ?: "Actions query failed", isError = true)
                    }
                }
                return true
            }
            lower == "git fix" -> {
                val proj = activeProject() ?: run {
                    appendTerm("No active project selected.", isError = true)
                    return true
                }
                val failed = _workflowRuns.value.firstOrNull { it.conclusion == "failure" }
                if (failed == null) {
                    appendTerm("No failed workflow run cached. Run 'git actions' first.", isError = true)
                } else {
                    appendTerm("Invoking AI Build-Doctor on run #${failed.id}...")
                    runBuildDoctor(proj, failed.id)
                }
                return true
            }
            lower == "lan start" -> {
                startLanStudio()
                return true
            }
            lower == "lan stop" -> {
                stopLanStudio()
                return true
            }
            lower == "backup now" -> {
                selectTab(OmniTab.VAULT)
                appendTerm("Switched to Vault tab for AES-256-GCM .omniai.enc backup.")
                return true
            }
            lower == "sysinfo" -> {
                val proj = activeProject()
                appendTerm("OmniDev v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
                appendTerm("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
                appendTerm("Active workspace: ${proj?.slug ?: "none"}")
                return true
            }
            else -> return false
        }
    }

    private fun runLocalShellCommand(cmd: String) {
        val proj = activeProject()
        val workDir = if (proj != null) {
            WorkspaceManager.projectRoot(appContext, proj.slug)
        } else {
            appContext.filesDir
        }

        processJob?.cancel()
        processJob = viewModelScope.launch(Dispatchers.IO) {
            _isProcessRunning.value = true
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                pb.directory(workDir)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                activeProcess = proc
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        appendTerm(line)
                        line = reader.readLine()
                    }
                }
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    appendTerm("[exit $exitCode]", isError = true)
                }
            } catch (e: Exception) {
                appendTerm("Shell error: ${e.message}", isError = true)
            } finally {
                activeProcess = null
                _isProcessRunning.value = false
            }
        }
    }

    private fun dispatchTermuxCommand(cmd: String) {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            ContextCompat.startForegroundService(appContext, intent)
            appendTerm("Dispatched to Termux Bridge: $cmd")
        } catch (e: Exception) {
            appendTerm(
                "Termux Bridge failed (${e.message}). Ensure Termux is installed and ~/.termux/termux.properties has allow-external-apps=true.",
                isError = true
            )
        }
    }
}

class MainActivity : FragmentActivity() {

    private lateinit var viewModel: OmniDevViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[OmniDevViewModel::class.java]

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    POST_NOTIFICATIONS_REQUEST_CODE
                )
            }
        }

        handleIncomingShareIntent(intent)

        setContent {
            OmniDevTheme {
                OmniDevRootApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                viewModel.receiveSharedText(shared)
            }
        }
    }

    private companion object {
        const val POST_NOTIFICATIONS_REQUEST_CODE = 1001
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OmniDevRootApp(viewModel: OmniDevViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val pushProgress by viewModel.pushProgress.collectAsState()
    val statusBanner by viewModel.statusBanner.collectAsState()
    val secretLeaks by viewModel.secretLeaks.collectAsState()
    val diagnosis by viewModel.diagnosis.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        containerColor = OmniColors.Background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column {
                HorizontalDivider(color = OmniColors.Border, thickness = 1.dp)
                NavigationBar(
                    containerColor = OmniColors.Card,
                    tonalElevation = 0.dp
                ) {
                    OmniTab.values().forEach { tab ->
                        val icon = when (tab) {
                            OmniTab.PROJECTS -> Icons.Default.Folder
                            OmniTab.EDITOR -> Icons.Default.Code
                            OmniTab.TERMINAL -> Icons.Default.Terminal
                            OmniTab.VAULT -> Icons.Default.Security
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = OmniColors.Cyan,
                                selectedTextColor = OmniColors.Cyan,
                                unselectedIconColor = OmniColors.TextSecondary,
                                unselectedTextColor = OmniColors.TextSecondary,
                                indicatorColor = Color(0xFF1A222D)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OmniColors.Background)
                .padding(innerPadding)
        ) {
            pushProgress?.let { progress ->
                Surface(
                    color = OmniColors.Card,
                    border = BorderStroke(1.dp, OmniColors.Cyan.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = progress.stage,
                                color = OmniColors.Cyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (progress.total > 0) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${progress.current}/${progress.total}",
                                    color = OmniColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.current.toFloat() / progress.total.toFloat() },
                                color = OmniColors.Cyan,
                                trackColor = OmniColors.Border,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                color = OmniColors.Cyan,
                                trackColor = OmniColors.Border,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    OmniTab.PROJECTS -> ProjectsScreen(viewModel)
                    OmniTab.EDITOR -> EditorScreen(viewModel)
                    OmniTab.TERMINAL -> TerminalScreen(viewModel)
                    OmniTab.VAULT -> VaultScreen(viewModel)
                }
            }
        }
    }

    if (secretLeaks.isNotEmpty()) {
        SecretLeakAlertDialog(
            findings = secretLeaks,
            onDismiss = { viewModel.dismissSecretLeaks() }
        )
    }

    diagnosis?.let { diag ->
        DiffReviewDialog(
            diagnosis = diag,
            onDismiss = { viewModel.dismissDiagnosis() },
            onApplyAndRepush = { viewModel.applyBuildDoctorFixAndRepush(diag) }
        )
    }
}
