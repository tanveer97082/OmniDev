package com.omniai.dev.engine

enum class PreflightStatus { PASS, WARN, FAIL }

data class PreflightCheck(
    val title: String,
    val status: PreflightStatus,
    val details: String
)

data class ProjectPreflightReport(
    val projectId: Long,
    val checks: List<PreflightCheck>
)

object ProjectPreflight {
    fun evaluate(files: Map<String, String>): List<PreflightCheck> {
        val paths = files.keys
        val manifestPath = paths.firstOrNull {
            it.endsWith("src/main/AndroidManifest.xml", ignoreCase = true) ||
                it.equals("AndroidManifest.xml", ignoreCase = true)
        }
        val manifest = manifestPath?.let { files[it].orEmpty() }.orEmpty()
        val gradlePath = paths.firstOrNull {
            it == "app/build.gradle" || it == "app/build.gradle.kts"
        } ?: paths.firstOrNull {
            it == "build.gradle" || it == "build.gradle.kts" ||
                it.endsWith("/build.gradle") || it.endsWith("/build.gradle.kts")
        }
        val gradle = gradlePath?.let { files[it].orEmpty() }.orEmpty()

        val launcherDeclared = manifest.contains("android.intent.action.MAIN") &&
            manifest.contains("android.intent.category.LAUNCHER")
        val iconName = Regex("android:icon\\s*=\\s*\"@(?:mipmap|drawable)/([^\"]+)\"")
            .find(manifest)?.groupValues?.get(1)
        val hasIcon = iconName != null && paths.any {
            it.contains("/res/") && it.substringAfterLast('/').substringBeforeLast('.') == iconName
        }

        return listOf(
            PreflightCheck(
                title = "Android manifest",
                status = if (manifestPath != null) PreflightStatus.PASS else PreflightStatus.FAIL,
                details = manifestPath ?: "AndroidManifest.xml was not found in the workspace."
            ),
            PreflightCheck(
                title = "Launcher activity",
                status = when {
                    manifestPath == null -> PreflightStatus.WARN
                    launcherDeclared && manifest.contains("android:exported=\"true\"") -> PreflightStatus.PASS
                    else -> PreflightStatus.FAIL
                },
                details = if (launcherDeclared && manifest.contains("android:exported=\"true\"")) {
                    "MAIN and LAUNCHER intent filters and an exported activity are present."
                } else {
                    "Add an exported launcher activity with MAIN and LAUNCHER intent filters."
                }
            ),
            PreflightCheck(
                title = "Android Gradle configuration",
                status = when {
                    gradlePath == null -> PreflightStatus.FAIL
                    !gradle.contains("com.android.application") -> PreflightStatus.WARN
                    !Regex("""compileSdk\s*(?:=\s*)?\d+""").containsMatchIn(gradle) -> PreflightStatus.WARN
                    else -> PreflightStatus.PASS
                },
                details = when {
                    gradlePath == null -> "No build.gradle(.kts) file was found."
                    !gradle.contains("com.android.application") -> "Check that the app module applies the Android application plugin."
                    !Regex("""compileSdk\s*(?:=\s*)?\d+""").containsMatchIn(gradle) -> "Set compileSdk in the Android module configuration."
                    else -> "Android application plugin and compileSdk were found in $gradlePath."
                }
            ),
            PreflightCheck(
                title = "Launcher icon",
                status = when {
                    iconName == null -> PreflightStatus.WARN
                    hasIcon -> PreflightStatus.PASS
                    else -> PreflightStatus.FAIL
                },
                details = when {
                    iconName == null -> "Manifest does not declare an app icon."
                    hasIcon -> "Manifest icon resource @$iconName exists."
                    else -> "Manifest references @$iconName, but that resource was not found."
                }
            ),
            PreflightCheck(
                title = "Network permission",
                status = if (manifest.contains("android.permission.INTERNET")) PreflightStatus.PASS else PreflightStatus.WARN,
                details = if (manifest.contains("android.permission.INTERNET")) {
                    "INTERNET permission is declared."
                } else {
                    "Add INTERNET permission if the app needs network access (for example, GitHub APIs)."
                }
            ),
            PreflightCheck(
                title = "Gradle wrapper",
                status = if (paths.any { it == "gradlew" }) PreflightStatus.PASS else PreflightStatus.WARN,
                details = if (paths.any { it == "gradlew" }) "gradlew is present."
                else "Add gradlew to make builds reproducible without a preinstalled Gradle command."
            ),
            PreflightCheck(
                title = "CI workflow",
                status = if (paths.any { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }) {
                    PreflightStatus.PASS
                } else {
                    PreflightStatus.WARN
                },
                details = if (paths.any { it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml")) }) {
                    "A GitHub Actions workflow was found."
                } else {
                    "No GitHub Actions workflow was found; add one to build and publish APKs automatically."
                }
            )
        )
    }
}
