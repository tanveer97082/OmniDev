package com.omniai.dev.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniai.dev.OmniColors
import com.omniai.dev.OmniDevViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(viewModel: OmniDevViewModel) {
    val logs by viewModel.terminalLogs.collectAsState()
    val isRunning by viewModel.isProcessRunning.collectAsState()
    val lanStatus by viewModel.lanStatus.collectAsState()
    val snippets by viewModel.snippets.collectAsState()
    val clipboard = LocalClipboardManager.current

    var useTermux by remember { mutableStateOf(false) }
    var commandInput by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyIdx by remember { mutableStateOf(-1) }
    var showSnippetsSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top Shell Mode + Snippets Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !useTermux,
                    onClick = { useTermux = false },
                    label = { Text("Local Shell", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OmniColors.Cyan.copy(alpha = 0.2f),
                        selectedLabelColor = OmniColors.Cyan
                    )
                )
                FilterChip(
                    selected = useTermux,
                    onClick = { useTermux = true },
                    label = { Text("Termux Bridge", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OmniColors.Purple.copy(alpha = 0.25f),
                        selectedLabelColor = OmniColors.Purple
                    )
                )
            }
            OutlinedButton(
                onClick = { showSnippetsSheet = true },
                border = BorderStroke(1.dp, OmniColors.Border)
            ) {
                Icon(Icons.Default.Bookmark, contentDescription = null, tint = OmniColors.Cyan, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Snippets", color = OmniColors.TextPrimary, fontSize = 11.sp)
            }
        }

        // 1-Tap Local Wi-Fi Web Studio Card
        Surface(
            color = OmniColors.Card,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (lanStatus.running) OmniColors.Cyan else OmniColors.Border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (lanStatus.running) OmniColors.Cyan else OmniColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (lanStatus.running) lanStatus.url else "Local Wi-Fi Web Studio (:8787)",
                        color = if (lanStatus.running) OmniColors.Cyan else OmniColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (lanStatus.running) "Session PIN: ${lanStatus.pin} • ${lanStatus.projectSlug}" else "Edit files from laptop browser & trigger Git Push",
                        color = OmniColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = {
                        if (lanStatus.running) viewModel.stopLanStudio() else viewModel.startLanStudio()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (lanStatus.running) OmniColors.Danger else OmniColors.Cyan,
                        contentColor = OmniColors.Background
                    )
                ) {
                    Text(
                        text = if (lanStatus.running) "Stop" else "Start",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (useTermux) {
            Surface(
                color = OmniColors.Card,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, OmniColors.Purple.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Termux Bridge dispatches via com.termux.RUN_COMMAND. Enable allow-external-apps=true in ~/.termux/termux.properties (see Vault ? Guide).",
                    color = OmniColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // Terminal Output Buffer
        Surface(
            color = OmniColors.Card,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, OmniColors.Border),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(logs) { entry ->
                    val color = when {
                        entry.isError -> OmniColors.Danger
                        entry.isCommand -> OmniColors.Cyan
                        else -> OmniColors.TextPrimary
                    }
                    Text(
                        text = entry.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Sticky Extra-Keys Row
        val extraKeys = listOf("ESC", "TAB", "↑", "↓", "/", "-", "|", "CLEAR", "COPY")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            extraKeys.forEach { key ->
                Surface(
                    color = OmniColors.Card,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.clickable {
                        when (key) {
                            "ESC" -> commandInput = ""
                            "TAB" -> commandInput += "  "
                            "↑" -> {
                                if (history.isNotEmpty()) {
                                    historyIdx = (historyIdx + 1).coerceAtMost(history.lastIndex)
                                    commandInput = history[history.lastIndex - historyIdx]
                                }
                            }
                            "↓" -> {
                                if (historyIdx > 0) {
                                    historyIdx--
                                    commandInput = history[history.lastIndex - historyIdx]
                                } else {
                                    historyIdx = -1
                                    commandInput = ""
                                }
                            }
                            "CLEAR" -> viewModel.clearTerminal()
                            "COPY" -> {
                                val full = logs.joinToString("\n") { it.text }
                                clipboard.setText(AnnotatedString(full))
                                viewModel.emitMessage("Copied terminal output.")
                            }
                            else -> commandInput += key
                        }
                    }
                ) {
                    Text(
                        text = key,
                        color = OmniColors.Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Command Input + Run / CTRL+C Stop
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = {
                    Text(
                        if (useTermux) "Termux bash command..." else "OmniCLI or /system/bin/sh...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = OmniColors.TextPrimary
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = OmniColors.Card,
                    unfocusedContainerColor = OmniColors.Card,
                    focusedBorderColor = if (useTermux) OmniColors.Purple else OmniColors.Cyan,
                    unfocusedBorderColor = OmniColors.Border
                ),
                modifier = Modifier.weight(1f)
            )

            if (isRunning) {
                IconButton(
                    onClick = { viewModel.stopRunningProcess() },
                    modifier = Modifier.background(OmniColors.Danger, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "CTRL+C Stop", tint = OmniColors.Background)
                }
            } else {
                IconButton(
                    onClick = {
                        val toRun = commandInput.trim()
                        if (toRun.isNotBlank()) {
                            history.add(toRun)
                            historyIdx = -1
                            commandInput = ""
                            viewModel.executeTerminalCommand(toRun, useTermux)
                        }
                    },
                    modifier = Modifier.background(
                        if (useTermux) OmniColors.Purple else OmniColors.Cyan,
                        RoundedCornerShape(8.dp)
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = OmniColors.Background)
                }
            }
        }
    }

    if (showSnippetsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var newLabel by remember { mutableStateOf("") }
        var newCmd by remember { mutableStateOf("") }

        ModalBottomSheet(
            onDismissRequest = { showSnippetsSheet = false },
            sheetState = sheetState,
            containerColor = OmniColors.Card
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Saved Command Snippets", color = OmniColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f)
                    )
                    OutlinedTextField(
                        value = newCmd,
                        onValueChange = { newCmd = it },
                        label = { Text("Command") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                }
                Button(
                    enabled = newLabel.isNotBlank() && newCmd.isNotBlank(),
                    onClick = {
                        viewModel.saveSnippet(newLabel, newCmd, useTermux)
                        newLabel = ""
                        newCmd = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OmniColors.Cyan, contentColor = OmniColors.Background),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Snippet (${if (useTermux) "Termux" else "Local"})", fontWeight = FontWeight.Bold)
                }

                LazyColumn(
                    modifier = Modifier.height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(snippets, key = { it.id }) { snippet ->
                        Surface(
                            color = OmniColors.Background,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, OmniColors.Border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSnippetsSheet = false
                                    viewModel.executeTerminalCommand(snippet.command, snippet.useTermux)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${snippet.label} (${if (snippet.useTermux) "Termux" else "Shell"})",
                                        color = if (snippet.useTermux) OmniColors.Purple else OmniColors.Cyan,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = snippet.command,
                                        color = OmniColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteSnippet(snippet) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = OmniColors.Danger, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
