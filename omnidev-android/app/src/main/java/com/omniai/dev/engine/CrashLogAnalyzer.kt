package com.omniai.dev.engine

data class CrashAnalysis(
    val exception: String,
    val rootCause: String,
    val appFrame: String?,
    val recommendation: String
)

object CrashLogAnalyzer {
    private val exceptionPattern = Regex(
        """^\s*(?:Caused by:\s*)?([\w.$]+(?:Exception|Error))(?:\s*:\s*(.*))?$"""
    )
    private val framePattern = Regex("""^\s*at\s+([\w.$]+)\(([^:()]+)(?::(\d+))?\)""")

    fun analyze(rawLog: String): CrashAnalysis? {
        val lines = rawLog.lines()
        if (lines.isEmpty()) return null

        val fatalIndices = lines.indices.filter { lines[it].contains("FATAL EXCEPTION", ignoreCase = true) }
        val selectedFatal = fatalIndices.firstOrNull { index ->
            val nextFatal = fatalIndices.firstOrNull { it > index } ?: lines.size
            lines.subList(index, nextFatal).any { it.contains("Process: com.omniai.dev") }
        } ?: fatalIndices.lastOrNull()
        val start = selectedFatal ?: 0
        val end = fatalIndices.firstOrNull { it > start } ?: lines.size
        val section = lines.subList(start, end)

        val exceptions = section.mapNotNull { line ->
            exceptionPattern.matchEntire(stripLogcatPrefix(line))?.let { match ->
                val type = match.groupValues[1]
                val message = match.groupValues[2].trim()
                if (message.isBlank()) type else "$type: $message"
            }
        }
        val rootCause = exceptions.lastOrNull()
            ?: section.firstOrNull { it.contains("Exception", ignoreCase = true) || it.contains("Error", ignoreCase = true) }
            ?: return null

        val appFrame = section.firstNotNullOfOrNull { line ->
            framePattern.find(stripLogcatPrefix(line))?.let { match ->
                val className = match.groupValues[1]
                if (className.startsWith("com.omniai.dev.")) {
                    val location = match.groupValues[2]
                    val lineNumber = match.groupValues[3]
                    "$className ($location${if (lineNumber.isNotBlank()) ":$lineNumber" else ""})"
                } else {
                    null
                }
            }
        }

        return CrashAnalysis(
            exception = exceptions.firstOrNull() ?: rootCause,
            rootCause = rootCause,
            appFrame = appFrame,
            recommendation = recommendationFor(rootCause)
        )
    }

    private fun stripLogcatPrefix(line: String): String =
        line.substringAfter("AndroidRuntime:", line).trim()

    private fun recommendationFor(rootCause: String): String = when {
        rootCause.contains("Can only use lower 16 bits for requestCode", ignoreCase = true) ->
            "Use a permission request code within 0..65535, or launch the request through an API compatible with the host Activity."
        rootCause.contains("SecurityException", ignoreCase = true) ->
            "Check the AndroidManifest permission and request runtime permissions before using the protected feature."
        rootCause.contains("InflateException", ignoreCase = true) ->
            "Inspect the named layout/theme resource and verify every referenced drawable, color, and style exists."
        rootCause.contains("SQLiteException", ignoreCase = true) ->
            "Check the Room schema and database migration path; reproduce with a clean install only after backing up app data."
        rootCause.contains("Unable to start activity", ignoreCase = true) ->
            "Inspect the nested 'Caused by' exception and verify the Activity declaration and its startup initialization."
        else ->
            "Start with the deepest 'Caused by' entry, then inspect the first app-owned stack frame and its line number."
    }
}
