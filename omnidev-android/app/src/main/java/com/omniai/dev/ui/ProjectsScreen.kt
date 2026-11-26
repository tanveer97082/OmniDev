package com.omniai.dev.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniai.dev.OmniColors
import com.omniai.dev.OmniDevViewModel
import com.omniai.dev.data.ProjectEntity
import com.omniai.dev.data.ProjectTaskEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val KANBAN_STAGES = listOf("TODO", "IN_PROGRESS", "REVIEW", "DONE")
private val PRIORITIES = listOf("LOW", "MED", "HIGH", "URGENT")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: OmniDevViewModel) {
    val projects by viewModel.projects.collectAsState()
    val allTasks by viewModel.allTasks.collectAsState()
    val allIndices by viewModel.allFileIndices.collectAsState()
    val activeProjectId by viewModel.activeProjectId.collectAsState()
    val openedDetailId by viewModel.openedProjectDetailId.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    val openedProject = projects.firstOrNull { it.id == openedDetailId }
    if (openedProject != null) {
        ProjectDetailView(
            project = openedProject,
            tasks = allTasks.filter { it.projectId == openedProject.id },
            pendingChanges = viewModel.pendingChangesFor(openedProject, allIndices),
            viewModel = viewModel,
            onBack = { viewModel.openProjectDetail(null) }
        )
        return
    }

    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = OmniColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "OmniDev Projects",
                        color = OmniColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniColors.Background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = OmniColors.Cyan,
                contentColor = OmniColors.Background
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Project")
            }
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No projects yet",
                    color = OmniColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Create your first Android or Kotlin workspace to manage Kanban tasks, split AI code blocks, and run atomic GitHub pushes.",
                    color = OmniColors.TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OmniColors.Cyan,
                        contentColor = OmniColors.Background
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Create Project", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    val projectTasks = allTasks.filter { it.projectId == project.id }
                    val doneCount = projectTasks.count { it.stage == "DONE" }
                    val progress = if (projectTasks.isEmpty()) 0f else doneCount.toFloat() / projectTasks.size
                    val pending = viewModel.pendingChangesFor(project, allIndices)
                    val isActive = project.id == activeProjectId

                    Surface(
                        color = OmniColors.Card,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isActive) OmniColors.Cyan else OmniColors.Border
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openProjectDetail(project.id) }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = project.name,
                                        color = OmniColors.TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val repoLabel = if (project.githubOwner.isNotBlank() && project.githubRepo.isNotBlank()) {
                                        "${project.githubOwner}/${project.githubRepo}@${project.githubBranch}"
                                    } else {
                                        "Local workspace • slug: ${project.slug}"
                                    }
                                    Text(
                                        text = repoLabel,
                                        color = OmniColors.TextSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (pending > 0) {
                                    Surface(
                                        color = OmniColors.Warning.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, OmniColors.Warning.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "$pending changes",
                                            color = OmniColors.Warning,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Tasks: $doneCount/${projectTasks.size} done",
                                    color = OmniColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                                if (project.lastCommitSha.isNotBlank()) {
                                    Text(
                                        text = "HEAD ${project.lastCommitSha.take(7)}",
                                        color = OmniColors.Purple,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                color = OmniColors.Cyan,
                                trackColor = OmniColors.Border,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { viewModel.selectActiveProject(project.id) }) {
                                    Text(
                                        text = if (isActive) "Active Workspace" else "Set Active",
                                        color = if (isActive) OmniColors.Cyan else OmniColors.TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Button(
                                    enabled = !isBusy,
                                    onClick = {
                                        viewModel.selectActiveProject(project.id)
                                        viewModel.pushProjectAtomic(project)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = OmniColors.Cyan,
                                        contentColor = OmniColors.Background
                                    )
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Git Auto-Push", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            defaultOwner = viewModel.vaultConfig.collectAsState().value.defaultGithubOwner,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, desc, owner, repo, branch, autoPush ->
                viewModel.createProject(name, desc, owner, repo, branch, autoPush)
                showCreateDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetailView(
    project: ProjectEntity,
    tasks: List<ProjectTaskEntity>,
    pendingChanges: Int,
    viewModel: OmniDevViewModel,
    onBack: () -> Unit
) {
    val commits by viewModel.commitLogs.collectAsState()
    val runs by viewModel.workflowRuns.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskPriority by remember { mutableStateOf("MED") }
    var commitMsgInput by remember { mutableStateOf("") }
    var activeStageFilter by remember { mutableStateOf("ALL") }

    Scaffold(
        containerColor = OmniColors.Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = OmniColors.TextPrimary)
                    }
                },
                title = {
                    Column {
                        Text(project.name, color = OmniColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            text = "${project.githubOwner}/${project.githubRepo}@${project.githubBranch}",
                            color = OmniColors.Cyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.deleteProject(project) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Project", tint = OmniColors.Danger)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniColors.Background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Git Sync & Repo Controls Card
            item {
                Surface(
                    color = OmniColors.Card,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Atomic Git Sync ($pendingChanges pending)", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Auto-Push", color = OmniColors.TextSecondary, fontSize = 11.sp)
                                Spacer(Modifier.width(6.dp))
                                Switch(
                                    checked = project.autoPushOnSave,
                                    onCheckedChange = { viewModel.updateProject(project.copy(autoPushOnSave = it)) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = commitMsgInput,
                            onValueChange = { commitMsgInput = it },
                            label = { Text("Commit message (optional — leave blank for AI/auto)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !isBusy,
                                onClick = {
                                    viewModel.pushProjectAtomic(
                                        project = project,
                                        customMessage = commitMsgInput.ifBlank { null },
                                        useAiMessage = commitMsgInput.isBlank()
                                    )
                                    commitMsgInput = ""
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OmniColors.Cyan,
                                    contentColor = OmniColors.Background
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Atomic Push", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = { viewModel.pullProject(project) },
                                border = BorderStroke(1.dp, OmniColors.Border),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = OmniColors.Purple, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pull GitHub", color = OmniColors.TextPrimary, fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            enabled = !isBusy,
                            onClick = { viewModel.createRemotePrivateRepo(project) },
                            border = BorderStroke(1.dp, OmniColors.Purple.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Create New Private GitHub Repo (auto_init)", color = OmniColors.Purple, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Live GitHub Actions Build Watcher
            item {
                Surface(
                    color = OmniColors.Card,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = OmniColors.Cyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("GitHub Actions Build Watcher", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { viewModel.refreshActionsForProject(project.id) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Actions", tint = OmniColors.Cyan)
                            }
                        }

                        if (runs.isEmpty()) {
                            Text("Tap refresh after pushing to monitor CI builds.", color = OmniColors.TextSecondary, fontSize = 12.sp)
                        } else {
                            runs.take(3).forEach { run ->
                                val color = when (run.conclusion) {
                                    "success" -> OmniColors.Success
                                    "failure" -> OmniColors.Danger
                                    else -> OmniColors.Warning
                                }
                                Surface(
                                    color = OmniColors.Background,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, OmniColors.Border),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (run.conclusion == "failure") Icons.Default.Error else Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = color,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "${run.name} (${run.headSha.take(7)})",
                                                color = OmniColors.TextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = (run.conclusion ?: run.status).uppercase(),
                                                color = color,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (run.conclusion == "failure") {
                                            Spacer(Modifier.height(6.dp))
                                            Button(
                                                enabled = !isBusy,
                                                onClick = { viewModel.runBuildDoctor(project, run.id) },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = OmniColors.Purple,
                                                    contentColor = OmniColors.Background
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Diagnose with AI Build-Doctor", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else if (run.conclusion == "success") {
                                            Spacer(Modifier.height(6.dp))
                                            OutlinedButton(
                                                enabled = !isBusy,
                                                onClick = { viewModel.checkAndInstallLatestReleaseApk(project) },
                                                border = BorderStroke(1.dp, OmniColors.Success.copy(alpha = 0.6f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = OmniColors.Success, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Install Latest Release APK (OTA)", color = OmniColors.Success, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Kanban Board
            item {
                Surface(
                    color = OmniColors.Card,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kanban Board", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = newTaskTitle,
                                onValueChange = { newTaskTitle = it },
                                label = { Text("New task title") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    viewModel.addTask(project.id, newTaskTitle, newTaskPriority)
                                    newTaskTitle = ""
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OmniColors.Cyan,
                                    contentColor = OmniColors.Background
                                ),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text("Add", fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PRIORITIES.forEach { p ->
                                FilterChip(
                                    selected = newTaskPriority == p,
                                    onClick = { newTaskPriority = p },
                                    label = { Text(p, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OmniColors.Purple.copy(alpha = 0.25f),
                                        selectedLabelColor = OmniColors.Purple
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            (listOf("ALL") + KANBAN_STAGES).forEach { stage ->
                                FilterChip(
                                    selected = activeStageFilter == stage,
                                    onClick = { activeStageFilter = stage },
                                    label = { Text(stage.replace("_", " "), fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OmniColors.Cyan.copy(alpha = 0.2f),
                                        selectedLabelColor = OmniColors.Cyan
                                    )
                                )
                            }
                        }

                        val visibleTasks = if (activeStageFilter == "ALL") tasks else tasks.filter { it.stage == activeStageFilter }
                        if (visibleTasks.isEmpty()) {
                            Text("No tasks in this stage.", color = OmniColors.TextSecondary, fontSize = 12.sp)
                        } else {
                            visibleTasks.forEach { task ->
                                val idx = KANBAN_STAGES.indexOf(task.stage).coerceAtLeast(0)
                                val priorityColor = when (task.priority) {
                                    "URGENT" -> OmniColors.Danger
                                    "HIGH" -> OmniColors.Warning
                                    "MED" -> OmniColors.Cyan
                                    else -> OmniColors.TextSecondary
                                }
                                Surface(
                                    color = OmniColors.Background,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, OmniColors.Border),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(task.title, color = OmniColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                text = "${task.stage} • ${task.priority}",
                                                color = priorityColor,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        if (idx > 0) {
                                            IconButton(onClick = { viewModel.moveTaskStage(task, KANBAN_STAGES[idx - 1]) }) {
                                                Icon(Icons.Default.ArrowBack, contentDescription = "Prev Stage", tint = OmniColors.TextSecondary, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        if (idx < KANBAN_STAGES.lastIndex) {
                                            IconButton(onClick = { viewModel.moveTaskStage(task, KANBAN_STAGES[idx + 1]) }) {
                                                Icon(Icons.Default.ArrowForward, contentDescription = "Next Stage", tint = OmniColors.Cyan, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        IconButton(onClick = { viewModel.deleteTask(task) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Task", tint = OmniColors.Danger, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Commit History
            item {
                Surface(
                    color = OmniColors.Card,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Commit History", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        if (commits.isEmpty()) {
                            Text("No atomic commits pushed yet.", color = OmniColors.TextSecondary, fontSize = 12.sp)
                        } else {
                            val fmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }
                            commits.forEachIndexed { idx, log ->
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = log.commitSha.take(7),
                                            color = OmniColors.Purple,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = log.message,
                                            color = OmniColors.TextPrimary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${log.filesChanged} file(s) • ${fmt.format(Date(log.pushedAt))}",
                                        color = OmniColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                    if (idx < commits.lastIndex) {
                                        HorizontalDivider(color = OmniColors.Border, modifier = Modifier.padding(top = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    defaultOwner: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf(defaultOwner) }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var autoPush by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OmniColors.Card,
        title = { Text("New OmniDev Project", color = OmniColors.TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project Name") }, singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("GitHub Owner") }, singleLine = true)
                OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub Repo (optional)") }, singleLine = true)
                OutlinedTextField(value = branch, onValueChange = { branch = it }, label = { Text("Branch") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoPush, onCheckedChange = { autoPush = it })
                    Text("Auto-Push on Save / File Split", color = OmniColors.TextSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name, description, owner, repo, branch, autoPush) },
                colors = ButtonDefaults.buttonColors(containerColor = OmniColors.Cyan, contentColor = OmniColors.Background)
            ) {
                Text("Create", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = OmniColors.TextSecondary)
            }
        }
    )
}
