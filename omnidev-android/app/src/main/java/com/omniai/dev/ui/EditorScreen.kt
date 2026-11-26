package com.omniai.dev.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniai.dev.OmniColors
import com.omniai.dev.OmniDevViewModel
import com.omniai.dev.data.FileSyncState
import com.omniai.dev.engine.AiMultiFileSplitter

private enum class EditorMode { FILES, CODE, SPLITTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: OmniDevViewModel) {
    val projects by viewModel.projects.collectAsState()
    val activeId by viewModel.activeProjectId.collectAsState()
    val files by viewModel.workspaceFiles.collectAsState()
    val incomingShared by viewModel.incomingSharedCode.collectAsState()
    val clipboard = LocalClipboardManager.current

    val activeProject = projects.firstOrNull { it.id == activeId } ?: projects.firstOrNull()

    var mode by remember { mutableStateOf(EditorMode.FILES) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var originalText by remember { mutableStateOf("") }
    var splitterText by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(incomingShared) {
        if (!incomingShared.isNullOrBlank()) {
            splitterText = incomingShared.orEmpty()
            mode = EditorMode.SPLITTER
            viewModel.consumeIncomingSharedCode()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header & Mode Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeProject?.name ?: "No Active Project",
                    color = OmniColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (activeProject != null) "filesDir/projects/${activeProject.slug}/" else "Create a project in Tab 1 first",
                    color = OmniColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            if (activeProject != null && mode == EditorMode.FILES) {
                IconButton(onClick = { showNewFileDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New File", tint = OmniColors.Cyan)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorMode.values().forEach { item ->
                val label = when (item) {
                    EditorMode.FILES -> "Files (${files.size})"
                    EditorMode.CODE -> "Code Editor"
                    EditorMode.SPLITTER -> "### FILE: Splitter"
                }
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OmniColors.Cyan.copy(alpha = 0.2f),
                        selectedLabelColor = OmniColors.Cyan
                    )
                )
            }
        }

        if (activeProject == null) {
            Surface(
                color = OmniColors.Card,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, OmniColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Create or select a project in the Projects tab to explore files, edit code, or run the ### FILE: Multi-File Splitter.",
                    color = OmniColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return
        }

        when (mode) {
            EditorMode.FILES -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files, key = { it.relativePath }) { item ->
                        val badgeColor = when (item.state) {
                            FileSyncState.NEW -> OmniColors.Cyan
                            FileSyncState.MOD -> OmniColors.Warning
                            FileSyncState.SYNCED -> OmniColors.Success
                        }
                        Surface(
                            color = OmniColors.Card,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, OmniColors.Border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPath = item.relativePath
                                    val content = runCatching { item.file.readText(Charsets.UTF_8) }.getOrDefault("")
                                    editorText = content
                                    originalText = content
                                    mode = EditorMode.CODE
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "[${item.state.name}]",
                                    color = badgeColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = item.relativePath,
                                    color = OmniColors.TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { viewModel.deleteWorkspaceFile(item.relativePath) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete File",
                                        tint = OmniColors.Danger,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            EditorMode.CODE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedPath ?: "No file selected",
                        color = OmniColors.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            enabled = selectedPath != null && editorText != originalText,
                            onClick = { editorText = originalText },
                            border = BorderStroke(1.dp, OmniColors.Border)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Undo", fontSize = 12.sp)
                        }
                        Button(
                            enabled = selectedPath != null,
                            onClick = {
                                val path = selectedPath ?: return@Button
                                viewModel.createOrUpdateFile(path, editorText)
                                originalText = editorText
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OmniColors.Cyan,
                                contentColor = OmniColors.Background
                            )
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = OmniColors.TextPrimary,
                        lineHeight = 18.sp
                    ),
                    placeholder = { Text("Select a file from Files tab or create a new file...") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = OmniColors.Card,
                        unfocusedContainerColor = OmniColors.Card,
                        focusedBorderColor = OmniColors.Cyan,
                        unfocusedBorderColor = OmniColors.Border
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            EditorMode.SPLITTER -> {
                val parsedPreview = remember(splitterText) { AiMultiFileSplitter.parse(splitterText) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Detected ${parsedPreview.size} ### FILE: block(s)",
                        color = if (parsedPreview.isNotEmpty()) OmniColors.Cyan else OmniColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = {
                            clipboard.getText()?.text?.let { splitterText = it }
                        },
                        border = BorderStroke(1.dp, OmniColors.Border)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Paste Clipboard", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = splitterText,
                    onValueChange = { splitterText = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = OmniColors.TextPrimary
                    ),
                    placeholder = {
                        Text(
                            "Paste AI output containing:\n### FILE: app/src/main/java/com/example/App.kt\n```kotlin\n...\n```",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = OmniColors.Card,
                        unfocusedContainerColor = OmniColors.Card,
                        focusedBorderColor = OmniColors.Purple,
                        unfocusedBorderColor = OmniColors.Border
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Button(
                    enabled = parsedPreview.isNotEmpty(),
                    onClick = {
                        viewModel.applyMultiFileSplit(splitterText)
                        splitterText = ""
                        mode = EditorMode.FILES
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OmniColors.Purple,
                        contentColor = OmniColors.Background
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Write ${parsedPreview.size} File(s) Into ${activeProject.name}",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showNewFileDialog) {
        var newPath by remember { mutableStateOf("app/src/main/java/com/omniai/dev/NewFile.kt") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            containerColor = OmniColors.Card,
            title = { Text("Create Workspace File", color = OmniColors.TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Relative path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = newPath.isNotBlank(),
                    onClick = {
                        viewModel.createOrUpdateFile(newPath, "")
                        selectedPath = newPath.trim()
                        editorText = ""
                        originalText = ""
                        showNewFileDialog = false
                        mode = EditorMode.CODE
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OmniColors.Cyan, contentColor = OmniColors.Background)
                ) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel", color = OmniColors.TextSecondary)
                }
            }
        )
    }
}
