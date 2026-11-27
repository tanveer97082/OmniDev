import workflowYml from '../omnidev-android/.github/workflows/build-release-apk.yml?raw';
import gradleProperties from '../omnidev-android/gradle.properties?raw';
import settingsGradle from '../omnidev-android/settings.gradle.kts?raw';
import rootBuildGradle from '../omnidev-android/build.gradle.kts?raw';
import appBuildGradle from '../omnidev-android/app/build.gradle.kts?raw';
import androidManifest from '../omnidev-android/app/src/main/AndroidManifest.xml?raw';
import themesXml from '../omnidev-android/app/src/main/res/values/themes.xml?raw';
import filePathsXml from '../omnidev-android/app/src/main/res/xml/file_paths.xml?raw';
import icBgDevXml from '../omnidev-android/app/src/main/res/drawable/ic_bg_dev.xml?raw';
import icFgDevXml from '../omnidev-android/app/src/main/res/drawable/ic_fg_dev.xml?raw';
import icStatOmniXml from '../omnidev-android/app/src/main/res/drawable/ic_stat_omni.xml?raw';
import icLauncherDevXml from '../omnidev-android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_dev.xml?raw';
import mainActivityKt from '../omnidev-android/app/src/main/java/com/omniai/dev/MainActivity.kt?raw';
import omniDevDatabaseKt from '../omnidev-android/app/src/main/java/com/omniai/dev/data/OmniDevDatabase.kt?raw';
import biometricAuthManagerKt from '../omnidev-android/app/src/main/java/com/omniai/dev/security/BiometricAuthManager.kt?raw';
import gitHubGitEngineKt from '../omnidev-android/app/src/main/java/com/omniai/dev/engine/GitHubGitEngine.kt?raw';
import aiMultiFileSplitterKt from '../omnidev-android/app/src/main/java/com/omniai/dev/engine/AiMultiFileSplitter.kt?raw';
import secretLeakScannerKt from '../omnidev-android/app/src/main/java/com/omniai/dev/engine/SecretLeakScanner.kt?raw';
import aiBuildDoctorKt from '../omnidev-android/app/src/main/java/com/omniai/dev/engine/AiBuildDoctor.kt?raw';
import crashLogAnalyzerKt from '../omnidev-android/app/src/main/java/com/omniai/dev/engine/CrashLogAnalyzer.kt?raw';
import projectPreflightKt from '../omnidev-android/app/src/main/java/com/omniai/dev/engine/ProjectPreflight.kt?raw';
import localLanWebServerKt from '../omnidev-android/app/src/main/java/com/omniai/dev/service/LocalLanWebServer.kt?raw';
import projectsScreenKt from '../omnidev-android/app/src/main/java/com/omniai/dev/ui/ProjectsScreen.kt?raw';
import editorScreenKt from '../omnidev-android/app/src/main/java/com/omniai/dev/ui/EditorScreen.kt?raw';
import terminalScreenKt from '../omnidev-android/app/src/main/java/com/omniai/dev/ui/TerminalScreen.kt?raw';
import vaultScreenKt from '../omnidev-android/app/src/main/java/com/omniai/dev/ui/VaultScreen.kt?raw';
import diffReviewDialogKt from '../omnidev-android/app/src/main/java/com/omniai/dev/ui/DiffReviewDialog.kt?raw';
import workbenchScreenKt from '../omnidev-android/app/src/main/java/com/omniai/dev/ui/WorkbenchScreen.kt?raw';

export interface RepoFile {
  path: string;
  category: 'CI & Gradle' | 'Resources' | 'Data & Security' | 'Core Engines' | 'Compose UI';
  language: 'yaml' | 'properties' | 'kotlin' | 'xml';
  summary: string;
  content: string;
}

export const OMNIDEV_REPO_FILES: RepoFile[] = [
  {
    path: '.github/workflows/build-release-apk.yml',
    category: 'CI & Gradle',
    language: 'yaml',
    summary: 'Temurin JDK 17 + Gradle 8.5 APK build, artifact upload, and GitHub Release publisher.',
    content: workflowYml,
  },
  {
    path: 'gradle.properties',
    category: 'CI & Gradle',
    language: 'properties',
    summary: 'Locked AndroidX, nonTransitiveRClass, and 3GB JVM heap configuration.',
    content: gradleProperties,
  },
  {
    path: 'settings.gradle.kts',
    category: 'CI & Gradle',
    language: 'kotlin',
    summary: 'Root project declaration and Google/MavenCentral repository rules.',
    content: settingsGradle,
  },
  {
    path: 'build.gradle.kts',
    category: 'CI & Gradle',
    language: 'kotlin',
    summary: 'Locked AGP 8.2.2, Kotlin 1.9.22, Serialization, and KSP 1.9.22-1.0.17 plugin matrix.',
    content: rootBuildGradle,
  },
  {
    path: 'app/build.gradle.kts',
    category: 'CI & Gradle',
    language: 'kotlin',
    summary: 'App module config (com.omniai.dev, SDK 34, Compose 1.5.8, exact dependencies only).',
    content: appBuildGradle,
  },
  {
    path: 'app/src/main/AndroidManifest.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'Permissions, Termux queries, FileProvider, dataSync LAN service, and ACTION_SEND filter.',
    content: androidManifest,
  },
  {
    path: 'app/src/main/res/values/themes.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'AMOLED dark NoActionBar Material theme.',
    content: themesXml,
  },
  {
    path: 'app/src/main/res/xml/file_paths.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'FileProvider paths for project files, OTA APKs, and encrypted backups.',
    content: filePathsXml,
  },
  {
    path: 'app/src/main/res/drawable/ic_bg_dev.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'Pure XML adaptive icon AMOLED grid background.',
    content: icBgDevXml,
  },
  {
    path: 'app/src/main/res/drawable/ic_fg_dev.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'Pure XML adaptive icon terminal chevron foreground.',
    content: icFgDevXml,
  },
  {
    path: 'app/src/main/res/drawable/ic_stat_omni.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'Monochrome 24dp vector notification icon for ForegroundService.',
    content: icStatOmniXml,
  },
  {
    path: 'app/src/main/res/mipmap-anydpi-v26/ic_launcher_dev.xml',
    category: 'Resources',
    language: 'xml',
    summary: 'Adaptive launcher icon mipmap referencing XML drawables.',
    content: icLauncherDevXml,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/MainActivity.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Entry activity, AMOLED theme, OmniDevViewModel, OmniCLI, Termux bridge, and 5-tab scaffold.',
    content: mainActivityKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/data/OmniDevDatabase.kt',
    category: 'Data & Security',
    language: 'kotlin',
    summary: 'Room 2.6.1 DB, SHA-256 WorkspaceManager, encrypted Vault, and AES-256-GCM backups for project files, tasks, sync state, and snippets.',
    content: omniDevDatabaseKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/security/BiometricAuthManager.kt',
    category: 'Data & Security',
    language: 'kotlin',
    summary: 'Android BiometricPrompt wrapper supporting fingerprint and device lock authentication across API 26–34.',
    content: biometricAuthManagerKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/engine/GitHubGitEngine.kt',
    category: 'Core Engines',
    language: 'kotlin',
    summary: 'Atomic Git Data API push/pull, remote line-diff review, manual Actions build dispatch, workflow watcher, OTA installer, and GitAutoPushWorker.',
    content: gitHubGitEngineKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/engine/AiMultiFileSplitter.kt',
    category: 'Core Engines',
    language: 'kotlin',
    summary: '### FILE: parser with path-traversal protection and auto-push queue trigger.',
    content: aiMultiFileSplitterKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/engine/SecretLeakScanner.kt',
    category: 'Data & Security',
    language: 'kotlin',
    summary: 'Pre-Push Secret Guardian scanning for GitHub, Gemini, OpenAI, HF, Groq, Telegram, and Private Key leaks.',
    content: secretLeakScannerKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/engine/AiBuildDoctor.kt',
    category: 'Core Engines',
    language: 'kotlin',
    summary: 'Failed Actions log extractor, AI fix generator (OpenAI/Gemini), and LCS unified line diff engine.',
    content: aiBuildDoctorKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/engine/CrashLogAnalyzer.kt',
    category: 'Core Engines',
    language: 'kotlin',
    summary: 'Offline Logcat analyzer that extracts app exceptions, root causes, source frames, and next-step guidance.',
    content: crashLogAnalyzerKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/engine/ProjectPreflight.kt',
    category: 'Core Engines',
    language: 'kotlin',
    summary: 'Static Android workspace checks for manifest, launcher, Gradle config, icon, network permission, wrapper, and CI.',
    content: projectPreflightKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/service/LocalLanWebServer.kt',
    category: 'Core Engines',
    language: 'kotlin',
    summary: 'ForegroundService (dataSync) running java.net.ServerSocket (:8787) with 6-digit PIN and embedded AMOLED SPA editor.',
    content: localLanWebServerKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/ui/ProjectsScreen.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Tab 1: Project cards, Kanban stage board, private repo creator, atomic push/pull, commit history, and Actions watcher.',
    content: projectsScreenKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/ui/EditorScreen.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Tab 2: Searchable file explorer, pending Git change filter, monospace editor with Undo/Save, and ### FILE: splitter.',
    content: editorScreenKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/ui/TerminalScreen.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Tab 3: Hybrid /system/bin/sh + OmniCLI + Termux Bridge, extra-keys bar, snippets drawer, and LAN Studio toggle.',
    content: terminalScreenKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/ui/VaultScreen.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Tab 5: Encrypted Vault, Secret Guardian, AI Build-Doctor, OTA updater, full-workspace .omniai.enc backup/restore, and Setup Guide.',
    content: vaultScreenKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/ui/WorkbenchScreen.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Tab 4: Crash Log Analyzer, Android project preflight, GitHub Actions build dispatch/status, APK installer, and Git diff review.',
    content: workbenchScreenKt,
  },
  {
    path: 'app/src/main/java/com/omniai/dev/ui/DiffReviewDialog.kt',
    category: 'Compose UI',
    language: 'kotlin',
    summary: 'Green (+)/Red (-) line diff review dialog for AI Build-Doctor and SecretLeakAlertDialog for blocked pushes.',
    content: diffReviewDialogKt,
  },
];

export function buildAllFilesSplitBundle(): string {
  return OMNIDEV_REPO_FILES.map((file) => `### FILE: ${file.path}\n${file.content.trimEnd()}\n`).join('\n');
}
