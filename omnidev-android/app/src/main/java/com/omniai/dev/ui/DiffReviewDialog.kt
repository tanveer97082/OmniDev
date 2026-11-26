package com.omniai.dev.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omniai.dev.OmniColors
import com.omniai.dev.engine.BuildDoctorDiagnosis
import com.omniai.dev.engine.DiffLineKind
import com.omniai.dev.engine.SecretLeakFinding

@Composable
fun DiffReviewDialog(
    diagnosis: BuildDoctorDiagnosis,
    onDismiss: () -> Unit,
    onApplyAndRepush: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = OmniColors.Card,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, OmniColors.Border),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = OmniColors.Purple
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI Build-Doctor Fix Preview",
                            color = OmniColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Run #${diagnosis.runId} • ${diagnosis.targetRelativePath}",
                            color = OmniColors.Cyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Surface(
                    color = OmniColors.Background,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = diagnosis.explanation,
                        color = OmniColors.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                val addedCount = diagnosis.diffLines.count { it.kind == DiffLineKind.ADDED }
                val removedCount = diagnosis.diffLines.count { it.kind == DiffLineKind.REMOVED }
                Text(
                    text = "Unified Diff (+$addedCount / -$removedCount)",
                    color = OmniColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(6.dp))

                Surface(
                    color = OmniColors.Background,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 380.dp)
                ) {
                    val hScroll = rememberScrollState()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll)
                            .padding(vertical = 6.dp)
                    ) {
                        items(diagnosis.diffLines) { line ->
                            val bg = when (line.kind) {
                                DiffLineKind.ADDED -> Color(0xFF0E2A1A)
                                DiffLineKind.REMOVED -> Color(0xFF2D1216)
                                DiffLineKind.CONTEXT -> Color.Transparent
                            }
                            val fg = when (line.kind) {
                                DiffLineKind.ADDED -> Color(0xFF4ADE80)
                                DiffLineKind.REMOVED -> Color(0xFFF87171)
                                DiffLineKind.CONTEXT -> OmniColors.TextSecondary
                            }
                            val prefix = when (line.kind) {
                                DiffLineKind.ADDED -> "+"
                                DiffLineKind.REMOVED -> "-"
                                DiffLineKind.CONTEXT -> " "
                            }
                            val lineNum = (line.newLineNo ?: line.oldLineNo ?: 0).toString().padStart(4, ' ')
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .padding(horizontal = 8.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$lineNum $prefix ${line.text}",
                                    color = fg,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, OmniColors.Border)
                    ) {
                        Text("Cancel", color = OmniColors.TextSecondary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onApplyAndRepush,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OmniColors.Cyan,
                            contentColor = OmniColors.Background
                        )
                    ) {
                        Text("Apply Fix & Re-Push", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SecretLeakAlertDialog(
    findings: List<SecretLeakFinding>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OmniColors.Card,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = OmniColors.Danger
            )
        },
        title = {
            Text(
                text = "Pre-Push Secret Guardian Blocked Push",
                color = OmniColors.Danger,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                item {
                    Text(
                        text = "Sensitive credentials were detected in modified files. Remove them before pushing:",
                        color = OmniColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                items(findings) { finding ->
                    Surface(
                        color = OmniColors.Background,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OmniColors.Danger.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = finding.ruleName,
                                color = OmniColors.Danger,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${finding.relativePath} : line ${finding.lineNumber}",
                                color = OmniColors.Cyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "Match: ${finding.redactedPreview}",
                                color = OmniColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Acknowledge", color = OmniColors.Cyan, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
