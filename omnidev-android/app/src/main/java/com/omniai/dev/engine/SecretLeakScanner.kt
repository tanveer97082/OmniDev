package com.omniai.dev.engine

import com.omniai.dev.data.WorkspaceFileItem

data class SecretLeakFinding(
    val relativePath: String,
    val lineNumber: Int,
    val ruleName: String,
    val redactedPreview: String
)

object SecretLeakScanner {

    private data class SecretRule(val name: String, val regex: Regex)

    private val rules = listOf(
        SecretRule("GitHub Classic PAT", Regex("""ghp_[A-Za-z0-9]{20,}""")),
        SecretRule("GitHub Fine-Grained PAT", Regex("""github_pat_[A-Za-z0-9_]{20,}""")),
        SecretRule("Google / Gemini API Key", Regex("""AIza[0-9A-Za-z\-_]{30,}""")),
        SecretRule("HuggingFace Token", Regex("""hf_[A-Za-z0-9]{20,}""")),
        SecretRule("OpenAI / Anthropic Secret Key", Regex("""sk-[A-Za-z0-9\-_]{20,}""")),
        SecretRule("Groq API Key", Regex("""gsk_[A-Za-z0-9]{20,}""")),
        SecretRule("Telegram Bot Token", Regex("""\b\d{8,10}:[A-Za-z0-9_-]{35}\b""")),
        SecretRule(
            "Private Key Header",
            Regex("""-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----""")
        )
    )

    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "apk", "jar", "zip", "pdf", "so", "jks", "keystore", "enc"
    )

    fun scanFiles(files: List<WorkspaceFileItem>): List<SecretLeakFinding> {
        val findings = mutableListOf<SecretLeakFinding>()
        for (item in files) {
            val ext = item.file.extension.lowercase()
            if (ext in binaryExtensions || item.sizeBytes > 1_500_000L) continue
            val text = runCatching { item.file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val lines = text.lines()
            lines.forEachIndexed { idx, line ->
                for (rule in rules) {
                    val match = rule.regex.find(line)
                    if (match != null) {
                        val raw = match.value
                        val preview = if (raw.length > 10) {
                            "${raw.take(6)}...${raw.takeLast(4)}"
                        } else {
                            "***REDACTED***"
                        }
                        findings += SecretLeakFinding(
                            relativePath = item.relativePath,
                            lineNumber = idx + 1,
                            ruleName = rule.name,
                            redactedPreview = preview
                        )
                    }
                }
            }
        }
        return findings
    }
}
