package com.omniai.dev.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val slug: String,
    val description: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubBranch: String = "main",
    val autoPushOnSave: Boolean = false,
    val lastCommitSha: String = "",
    val lastPushedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "project_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class ProjectTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val title: String,
    val stage: String = "TODO",       // TODO, IN_PROGRESS, REVIEW, DONE
    val priority: String = "MED",     // LOW, MED, HIGH, URGENT
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "project_file_index",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index(value = ["projectId", "relativePath"], unique = true)]
)
data class ProjectFileIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val relativePath: String,
    val sha256: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "git_commit_logs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class GitCommitLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val commitSha: String,
    val message: String,
    val filesChanged: Int,
    val pushedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(tableName = "terminal_snippets")
data class TerminalSnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val command: String,
    val useTermux: Boolean = false
)

@Dao
interface OmniDevDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun getAllProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("SELECT * FROM project_tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<ProjectTaskEntity>>

    @Query("SELECT * FROM project_tasks WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeTasksForProject(projectId: Long): Flow<List<ProjectTaskEntity>>

    @Query("SELECT * FROM project_tasks")
    suspend fun getAllTasks(): List<ProjectTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ProjectTaskEntity): Long

    @Update
    suspend fun updateTask(task: ProjectTaskEntity)

    @Delete
    suspend fun deleteTask(task: ProjectTaskEntity)

    @Query("SELECT * FROM project_file_index WHERE projectId = :projectId")
    suspend fun getFileIndexForProject(projectId: Long): List<ProjectFileIndexEntity>

    @Query("SELECT * FROM project_file_index")
    fun observeAllFileIndices(): Flow<List<ProjectFileIndexEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileIndices(items: List<ProjectFileIndexEntity>)

    @Query("DELETE FROM project_file_index WHERE projectId = :projectId")
    suspend fun clearFileIndexForProject(projectId: Long)

    @Query("SELECT * FROM git_commit_logs WHERE projectId = :projectId ORDER BY pushedAt DESC LIMIT 30")
    fun observeCommitsForProject(projectId: Long): Flow<List<GitCommitLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommitLog(log: GitCommitLogEntity): Long

    @Query("SELECT * FROM terminal_snippets ORDER BY id ASC")
    fun observeSnippets(): Flow<List<TerminalSnippetEntity>>

    @Query("SELECT COUNT(*) FROM terminal_snippets")
    suspend fun countSnippets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnippet(snippet: TerminalSnippetEntity): Long

    @Delete
    suspend fun deleteSnippet(snippet: TerminalSnippetEntity)
}

@Database(
    entities = [
        ProjectEntity::class,
        ProjectTaskEntity::class,
        ProjectFileIndexEntity::class,
        GitCommitLogEntity::class,
        TerminalSnippetEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class OmniDevDatabase : RoomDatabase() {
    abstract fun dao(): OmniDevDao

    companion object {
        @Volatile
        private var instance: OmniDevDatabase? = null

        fun getInstance(context: Context): OmniDevDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmniDevDatabase::class.java,
                    "omnidev_v1.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}

enum class FileSyncState { NEW, MOD, SYNCED }

data class WorkspaceFileItem(
    val relativePath: String,
    val file: File,
    val sha256: String,
    val state: FileSyncState,
    val sizeBytes: Long
)

object WorkspaceManager {
    fun projectRoot(context: Context, slug: String): File {
        val dir = File(File(context.filesDir, "projects"), slug)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun slugify(name: String): String {
        val cleaned = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (cleaned.isBlank()) "project-${System.currentTimeMillis() % 10000}" else cleaned
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun scanProjectFiles(
        context: Context,
        project: ProjectEntity,
        indexed: List<ProjectFileIndexEntity>
    ): List<WorkspaceFileItem> {
        val root = projectRoot(context, project.slug)
        val indexMap = indexed.associateBy { it.relativePath }
        val result = mutableListOf<WorkspaceFileItem>()
        if (!root.exists()) return result

        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel.startsWith(".git/")) return@forEach
                val bytes = runCatching { file.readBytes() }.getOrElse { ByteArray(0) }
                val hash = sha256(bytes)
                val prev = indexMap[rel]
                val state = when {
                    prev == null -> FileSyncState.NEW
                    prev.sha256 != hash -> FileSyncState.MOD
                    else -> FileSyncState.SYNCED
                }
                result += WorkspaceFileItem(
                    relativePath = rel,
                    file = file,
                    sha256 = hash,
                    state = state,
                    sizeBytes = file.length()
                )
            }
        return result.sortedBy { it.relativePath }
    }

    fun computeDeletedPaths(
        context: Context,
        project: ProjectEntity,
        indexed: List<ProjectFileIndexEntity>
    ): List<String> {
        val root = projectRoot(context, project.slug)
        return indexed.map { it.relativePath }.filter { rel -> !File(root, rel).exists() }
    }

    fun countPendingChanges(
        context: Context,
        project: ProjectEntity,
        indexed: List<ProjectFileIndexEntity>
    ): Int {
        val files = scanProjectFiles(context, project, indexed)
        val changed = files.count { it.state != FileSyncState.SYNCED }
        val deleted = computeDeletedPaths(context, project, indexed).size
        return changed + deleted
    }
}

@Serializable
data class VaultConfig(
    val githubToken: String = "",
    val defaultGithubOwner: String = "",
    val aiEndpoint: String = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
    val aiApiKey: String = "",
    val aiModel: String = "gemini-2.0-flash"
)

class VaultManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { createEncryptedPrefs(appContext) }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.deleteSharedPreferences(PREFS_NAME)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun load(): VaultConfig = VaultConfig(
        githubToken = prefs.getString(KEY_GH_TOKEN, "") ?: "",
        defaultGithubOwner = prefs.getString(KEY_GH_OWNER, "") ?: "",
        aiEndpoint = prefs.getString(
            KEY_AI_ENDPOINT,
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        ) ?: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        aiApiKey = prefs.getString(KEY_AI_KEY, "") ?: "",
        aiModel = prefs.getString(KEY_AI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"
    )

    fun save(config: VaultConfig) {
        prefs.edit()
            .putString(KEY_GH_TOKEN, config.githubToken.trim())
            .putString(KEY_GH_OWNER, config.defaultGithubOwner.trim())
            .putString(KEY_AI_ENDPOINT, config.aiEndpoint.trim())
            .putString(KEY_AI_KEY, config.aiApiKey.trim())
            .putString(KEY_AI_MODEL, config.aiModel.trim().ifBlank { "gemini-2.0-flash" })
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "omnidev_encrypted_vault"
        private const val KEY_GH_TOKEN = "github_token"
        private const val KEY_GH_OWNER = "default_github_owner"
        private const val KEY_AI_ENDPOINT = "ai_endpoint"
        private const val KEY_AI_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
    }
}

@Serializable
data class OmniSuiteBackupPayload(
    val suiteFormatVersion: Int = 1,
    val sourceApp: String = "com.omniai.dev",
    val exportedAt: Long = System.currentTimeMillis(),
    val vault: VaultConfig,
    val projects: List<ProjectEntity>,
    val tasks: List<ProjectTaskEntity>
)

object OmniBackupCrypto {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte())
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 100_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encryptPayload(payload: OmniSuiteBackupPayload, password: String): ByteArray {
        require(password.length >= 4) { "Backup password must be at least 4 characters." }
        val plainBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val secretKey = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainBytes)

        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(byteArrayOf(FORMAT_VERSION))
        out.write(salt)
        out.write(iv)
        out.write(encrypted)
        return out.toByteArray()
    }

    fun decryptPayload(rawBytes: ByteArray, password: String): OmniSuiteBackupPayload {
        require(rawBytes.size > MAGIC.size + 1 + SALT_LEN + IV_LEN + 16) {
            "Invalid .omniai.enc backup file."
        }
        for (i in MAGIC.indices) {
            require(rawBytes[i] == MAGIC[i]) { "Unrecognized backup file header." }
        }
        val saltStart = MAGIC.size + 1
        val ivStart = saltStart + SALT_LEN
        val cipherStart = ivStart + IV_LEN

        val salt = rawBytes.copyOfRange(saltStart, ivStart)
        val iv = rawBytes.copyOfRange(ivStart, cipherStart)
        val cipherBytes = rawBytes.copyOfRange(cipherStart, rawBytes.size)
        val secretKey = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return json.decodeFromString(OmniSuiteBackupPayload.serializer(), plainBytes.toString(Charsets.UTF_8))
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
