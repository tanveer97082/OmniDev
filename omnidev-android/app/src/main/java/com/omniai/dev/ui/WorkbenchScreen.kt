package com.omniai.dev.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniai.dev.OmniColors
import com.omniai.dev.OmniDevViewModel
import com.omniai.dev.engine.CrashAnalysis
import com.omniai.dev.engine.CrashLogAnalyzer
import com.omniai.dev.engine.DiffLineKind
import com.omniai.dev.engine.PreflightStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(viewModel: OmniDevViewModel) {
    val projects by viewModel.projects.collectAsState()
    val activeProjectId by viewModel.activeProjectId.collectAsState()
    val runs by viewModel.workflowRuns.collectAsState()
    val latestApk by viewModel.latestApk.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val preflightReport by viewModel.preflightReport.collectAsState()
    val workspaceDiffs by viewModel.workspaceDiffs.collectAsState()
    val incomingCrashLog by viewModel.incomingCrashLog.collectAsState()
    val activeProject = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
    val currentReport = preflightReport?.takeIf { it.projectId == activeProject?.id }
    val clipboard = LocalClipboardManager.current

    var crashLog by remember { mutableStateOf("") }
    var crashAnalysis by remember { mutableStateOf<CrashAnalysis?>(null) }
    var analysisMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(incomingCrashLog) {
        if (!incomingCrashLog.isNullOrBlank()) {
            crashLog = incomingCrashLog.orEmpty()
            crashAnalysis = null
            analysisMessage = null
            viewModel.consumeIncomingCrashLog()
        }
    }

    Scaffold(
        containerColor = OmniColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text("Build & Diagnostics", color = OmniColors.TextPrimary, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniColors.Background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = "Project Preflight") {
                Text(
                    text = activeProject?.let { "Checks ${it.name} for common Android packaging and CI requirements." }
                        ?: "Create a project first to run workspace checks.",
                    color = OmniColors.TextSecondary,
                    fontSize = 12.sp
                )
                Button(
                    enabled = activeProject != null && !isBusy,
                    onClick = { activeProject?.let(viewModel::runProjectPreflight) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OmniColors.Cyan,
                        contentColor = OmniColors.Background
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Spacer(Modifier.height(2.dp))
                    Text("Run Preflight", fontWeight = FontWeight.Bold)
                }
                currentReport?.checks?.forEach { check ->
                    val color = when (check.status) {
                        PreflightStatus.PASS -> OmniColors.Success
                        PreflightStatus.WARN -> OmniColors.Warning
                        PreflightStatus.FAIL -> OmniColors.Danger
                    }
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(check.status.name, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Column {
                            Text(check.title, color = OmniColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(check.details, color = OmniColors.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }

            SectionCard(title = "GitHub Actions & APK") {
                Text(
                    text = "Monitor recent CI runs and diagnose failures. Release APK install is available after a successful build.",
                    color = OmniColors.TextSecondary,
                    fontSize = 12.sp
                )
                Button(
                    enabled = activeProject != null && !isBusy,
                    onClick = { activeProject?.let(viewModel::dispatchBuildWorkflow) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OmniColors.Cyan,
                        contentColor = OmniColors.Background
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Build, contentDescription = null)
                    Text("Build APK on GitHub Actions", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    enabled = activeProject != null && !isBusy,
                    onClick = { activeProject?.let { viewModel.refreshActionsForProject(it.id) } },
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = OmniColors.Cyan)
                    Text("Refresh workflow runs", color = OmniColors.TextPrimary)
                }
                if (runs.isEmpty()) {
                    Text("No workflow runs loaded yet.", color = OmniColors.TextSecondary, fontSize = 12.sp)
                } else {
                    runs.take(3).forEach { run ->
                        val color = when (run.conclusion) {
                            "success" -> OmniColors.Success
                            "failure" -> OmniColors.Danger
                            else -> OmniColors.Warning
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (run.conclusion == "failure") Icons.Default.Error else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = color
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(run.name, color = OmniColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("${run.status} · ${run.conclusion ?: "in progress"} · ${run.headSha.take(7)}", color = color, fontSize = 11.sp)
                            }
                            if (run.conclusion == "failure" && activeProject != null) {
                                IconButton(
                                    enabled = !isBusy,
                                    onClick = { viewModel.runBuildDoctor(activeProject, run.id) }
                                ) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Diagnose failed workflow", tint = OmniColors.Purple)
                                }
                            }
                        }
                    }
                }
                if (activeProject != null) {
                    OutlinedButton(
                        enabled = !isBusy,
                        onClick = { viewModel.checkAndInstallLatestReleaseApk(activeProject) },
                        border = BorderStroke(1.dp, OmniColors.Success.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = OmniColors.Success)
                        Text(
                            latestApk?.let { "Install latest APK: ${it.assetName}" } ?: "Check for release APK",
                            color = OmniColors.Success
                        )
                    }
                    OutlinedButton(
                        enabled = !isBusy,
                        onClick = { viewModel.reviewWorkspaceDiff(activeProject) },
                        border = BorderStroke(1.dp, OmniColors.Purple.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = OmniColors.Purple)
                        Text("Review local changes against GitHub", color = OmniColors.Purple)
                    }
                }
                workspaceDiffs.forEach { diff ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${diff.changeType} · ${diff.relativePath}",
                            color = when (diff.changeType) {
                                "ADDED" -> OmniColors.Cyan
                                "DELETED" -> OmniColors.Danger
                                else -> OmniColors.Warning
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (diff.isBinary) {
                            Text("Binary file changed; line diff is unavailable.", color = OmniColors.TextSecondary, fontSize = 11.sp)
                        } else {
                            val firstChangedLine = diff.lines.indexOfFirst { it.kind != DiffLineKind.CONTEXT }
                                .coerceAtLeast(0)
                            val firstVisibleLine = (firstChangedLine - 3).coerceAtLeast(0)
                            val visibleDiffLines = diff.lines.drop(firstVisibleLine).take(30)
                            visibleDiffLines.forEach { line ->
                                val color = when (line.kind) {
                                    DiffLineKind.ADDED -> OmniColors.Success
                                    DiffLineKind.REMOVED -> OmniColors.Danger
                                    DiffLineKind.CONTEXT -> OmniColors.TextSecondary
                                }
                                val marker = when (line.kind) {
                                    DiffLineKind.ADDED -> "+"
                                    DiffLineKind.REMOVED -> "-"
                                    DiffLineKind.CONTEXT -> " "
                                }
                                val lineNumber = line.newLineNo ?: line.oldLineNo ?: ""
                                Text(
                                    text = "$marker $lineNumber ${line.text}",
                                    color = color,
                                    fontSize = 10.sp,
                                    maxLines = 2
                                )
                            }
                            if (firstVisibleLine + visibleDiffLines.size < diff.lines.size) {
                                Text("Showing the first change and nearby context.", color = OmniColors.TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            SectionCard(title = "Crash Log Analyzer") {
                Text(
                    text = "Analysis stays on this device. Paste the Logcat exception, including the 'Caused by' and stack trace lines.",
                    color = OmniColors.TextSecondary,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { crashLog = it } },
                    border = BorderStroke(1.dp, OmniColors.Border)
                ) {
                    Text("Paste Logcat", color = OmniColors.TextPrimary)
                }
                OutlinedTextField(
                    value = crashLog,
                    onValueChange = {
                        crashLog = it
                        crashAnalysis = null
                        analysisMessage = null
                    },
                    label = { Text("Crash log") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = crashLog.isNotBlank(),
                    onClick = {
                        if (crashLog.length > 1_000_000) {
                            crashAnalysis = null
                            analysisMessage = "Log is too large. Paste the FATAL EXCEPTION section only."
                        } else {
                            crashAnalysis = CrashLogAnalyzer.analyze(crashLog)
                            analysisMessage = if (crashAnalysis == null) {
                                "No Java/Kotlin exception found. Include the FATAL EXCEPTION and Caused by lines."
                            } else {
                                null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OmniColors.Purple,
                        contentColor = OmniColors.Background
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                    Text("Analyze log")
                }
                analysisMessage?.let { Text(it, color = OmniColors.Warning, fontSize = 12.sp) }
                crashAnalysis?.let { result ->
                    Surface(
                        color = OmniColors.Background,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OmniColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ResultLine("Exception", result.exception)
                            ResultLine("Root cause", result.rootCause)
                            result.appFrame?.let { ResultLine("App frame", it) }
                            ResultLine("Next step", result.recommendation)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = OmniColors.Card,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, OmniColors.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(title, color = OmniColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            content()
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Column {
        Text(label, color = OmniColors.Cyan, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Text(value, color = OmniColors.TextPrimary, fontSize = 12.sp)
    }
}
