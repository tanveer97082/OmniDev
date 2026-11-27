package com.omniai.dev.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omniai.dev.OmniColors
import com.omniai.dev.OmniDevViewModel
import com.omniai.dev.data.VaultConfig
import com.omniai.dev.security.BiometricAuthManager
import com.omniai.dev.security.BiometricAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(viewModel: OmniDevViewModel) {
    val context = LocalContext.current
    val isVaultUnlocked by viewModel.isVaultUnlocked.collectAsState()
    val savedVault by viewModel.vaultConfig.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val activeProjectId by viewModel.activeProjectId.collectAsState()
    val runs by viewModel.workflowRuns.collectAsState()
    val latestApk by viewModel.latestApk.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val clipboard = LocalClipboardManager.current
    val activeProject = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()

    var githubToken by remember(savedVault, isVaultUnlocked) { mutableStateOf(savedVault.githubToken) }
    var defaultOwner by remember(savedVault, isVaultUnlocked) { mutableStateOf(savedVault.defaultGithubOwner) }
    var aiEndpoint by remember(savedVault, isVaultUnlocked) { mutableStateOf(savedVault.aiEndpoint) }
    var aiModel by remember(savedVault, isVaultUnlocked) { mutableStateOf(savedVault.aiModel) }
    var aiApiKey by remember(savedVault, isVaultUnlocked) { mutableStateOf(savedVault.aiApiKey) }
    var showSecrets by remember { mutableStateOf(false) }

    var backupPassword by remember { mutableStateOf("") }
    var showSetupGuide by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var hasAutoPrompted by remember { mutableStateOf(false) }

    val triggerBiometricUnlock: () -> Unit = {
        authError = null
        BiometricAuthManager.authenticate(
            context = context,
            title = "Unlock OmniDev Vault",
            subtitle = "Authenticate with fingerprint or device lock to decrypt stored tokens",
            onSuccess = {
                authError = null
                viewModel.unlockVault()
            },
            onError = { message ->
                authError = message
            }
        )
    }

    LaunchedEffect(isVaultUnlocked) {
        if (!isVaultUnlocked && !hasAutoPrompted) {
            hasAutoPrompted = true
            if (BiometricAuthManager.checkAvailability(context) is BiometricAvailability.Ready) {
                triggerBiometricUnlock()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.exportEncryptedBackup(uri, backupPassword)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importEncryptedBackup(uri, backupPassword)
        }
    }

    Scaffold(
        containerColor = OmniColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Vault, Backup & Build-Doctor",
                        color = OmniColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                },
                actions = {
                    if (isVaultUnlocked) {
                        IconButton(
                            onClick = {
                                showSecrets = false
                                viewModel.lockVault()
                            }
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock Vault", tint = OmniColors.Purple)
                        }
                    }
                    IconButton(onClick = { showSetupGuide = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Setup Guide", tint = OmniColors.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniColors.Background)
            )
        }
    ) { padding ->
        if (!isVaultUnlocked) {
            val availability = remember(context, authError) {
                BiometricAuthManager.checkAvailability(context)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = OmniColors.Card,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Biometric Lock",
                            tint = OmniColors.Cyan,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Vault Locked",
                            color = OmniColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = "Authenticate with your fingerprint or device screen lock before decrypting GitHub tokens, AI API keys, and backup controls from EncryptedSharedPreferences.",
                            color = OmniColors.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        if (!authError.isNullOrBlank()) {
                            Surface(
                                color = OmniColors.Danger.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, OmniColors.Danger.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = authError.orEmpty(),
                                    color = OmniColors.Danger,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Button(
                            onClick = triggerBiometricUnlock,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OmniColors.Cyan,
                                contentColor = OmniColors.Background
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Unlock with Fingerprint / Device Lock", fontWeight = FontWeight.Bold)
                        }

                        if (availability is BiometricAvailability.NotEnrolled) {
                            OutlinedButton(
                                onClick = { BiometricAuthManager.openSecuritySettings(context) },
                                border = BorderStroke(1.dp, OmniColors.Purple.copy(alpha = 0.7f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = OmniColors.Purple, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open Android Security Settings", color = OmniColors.Purple, fontSize = 12.sp)
                            }
                        }
                    }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Key, contentDescription = null, tint = OmniColors.Cyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Encrypted Key Vault (AES256-GCM)", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(onClick = { showSecrets = !showSecrets }) {
                                    Icon(
                                        imageVector = if (showSecrets) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Secret Visibility",
                                        tint = OmniColors.TextSecondary
                                    )
                                }
                            }

                            val secretTransform = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()

                            OutlinedTextField(
                                value = githubToken,
                                onValueChange = { githubToken = it },
                                label = { Text("GITHUB_TOKEN (repo + workflow)") },
                                visualTransformation = secretTransform,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = defaultOwner,
                                onValueChange = { defaultOwner = it },
                                label = { Text("Default GitHub Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = aiEndpoint,
                                onValueChange = { aiEndpoint = it },
                                label = { Text("AI Endpoint (OpenAI-compatible or Gemini)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = aiModel,
                                onValueChange = { aiModel = it },
                                label = { Text("AI Model ID") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = aiApiKey,
                                onValueChange = { aiApiKey = it },
                                label = { Text("AI API Key (Commit Msgs & Build-Doctor)") },
                                visualTransformation = secretTransform,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    viewModel.saveVaultConfig(
                                        VaultConfig(
                                            githubToken = githubToken,
                                            defaultGithubOwner = defaultOwner,
                                            aiEndpoint = aiEndpoint,
                                            aiApiKey = aiApiKey,
                                            aiModel = aiModel
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OmniColors.Cyan,
                                    contentColor = OmniColors.Background
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save Encrypted Credentials", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                item {
                    Surface(
                        color = OmniColors.Card,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OmniColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = OmniColors.Purple, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Guardian, Build-Doctor & OTA", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.scanActiveProjectForSecrets() },
                                border = BorderStroke(1.dp, OmniColors.Cyan.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Run Pre-Push Secret Guardian Scan Now", color = OmniColors.Cyan, fontSize = 12.sp)
                            }

                            val latestFailedRun = runs.firstOrNull { it.conclusion == "failure" }
                            Button(
                                enabled = !isBusy && activeProject != null && latestFailedRun != null,
                                onClick = {
                                    if (activeProject != null && latestFailedRun != null) {
                                        viewModel.runBuildDoctor(activeProject, latestFailedRun.id)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OmniColors.Purple,
                                    contentColor = OmniColors.Background
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (latestFailedRun != null) "AI Build-Doctor: Fix Run #${latestFailedRun.id}" else "AI Build-Doctor (No Failed Run)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                enabled = !isBusy && activeProject != null && activeProject.githubOwner.isNotBlank() && activeProject.githubRepo.isNotBlank(),
                                onClick = {
                                    if (activeProject != null) {
                                        viewModel.checkAndInstallLatestReleaseApk(activeProject)
                                    }
                                },
                                border = BorderStroke(1.dp, OmniColors.Success.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = OmniColors.Success, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = latestApk?.let { "Install OTA: ${it.assetName}" } ?: "Check & Install GitHub Release APK",
                                    color = OmniColors.Success,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                item {
                    Surface(
                        color = OmniColors.Card,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OmniColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Omni Suite AES-256-GCM Backup (.omniai.enc)", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Exports encrypted Vault keys, project metadata, workspace files, Git sync state, tasks, and terminal snippets using PBKDF2-SHA256 + AES-256-GCM.",
                                color = OmniColors.TextSecondary,
                                fontSize = 12.sp
                            )

                            OutlinedTextField(
                                value = backupPassword,
                                onValueChange = { backupPassword = it },
                                label = { Text("Backup Passphrase (min 4 chars)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = backupPassword.length >= 4,
                                    onClick = { exportLauncher.launch("omnidev-backup.omniai.enc") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = OmniColors.Cyan,
                                        contentColor = OmniColors.Background
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Export .enc", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    enabled = backupPassword.length >= 4,
                                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                                    border = BorderStroke(1.dp, OmniColors.Border),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = OmniColors.Purple, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Restore .enc", color = OmniColors.TextPrimary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showSetupGuide) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val termuxCmd = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings"
        ModalBottomSheet(
            onDismissRequest = { showSetupGuide = false },
            sheetState = sheetState,
            containerColor = OmniColors.Card
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("OmniDev Setup Guide", color = OmniColors.Cyan, fontWeight = FontWeight.Bold, fontSize = 17.sp)

                Text("1. GitHub Classic Personal Access Token (PAT)", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = "• Open GitHub -> Settings -> Developer settings -> Personal access tokens -> Tokens (classic).\n• Click 'Generate new token (classic)'.\n• Select scopes: [x] repo and [x] workflow.\n• Paste the ghp_... token into GITHUB_TOKEN in the Vault tab.",
                    color = OmniColors.TextSecondary,
                    fontSize = 12.sp
                )

                Text("2. Enable Termux Bridge (allow-external-apps)", color = OmniColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Surface(
                    color = OmniColors.Background,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OmniColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = termuxCmd,
                        color = OmniColors.Purple,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(termuxCmd))
                        viewModel.emitMessage("Copied Termux setup command.")
                    },
                    border = BorderStroke(1.dp, OmniColors.Cyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = OmniColors.Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Termux Command", color = OmniColors.Cyan, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
