package app.zoocall.core.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.zoocall.core.model.Hex
import app.zoocall.core.model.Logger
import app.zoocall.core.store.db.ZoocallDatabase
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

object DesktopPaths {
    /** `%LOCALAPPDATA%\Zoocall`, `~/Library/Application Support/Zoocall`, or `$XDG_DATA_HOME/zoocall`. */
    fun dataDir(profile: String? = null): File {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "Zoocall")
            os.contains("mac") -> File(home, "Library/Application Support/Zoocall")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "zoocall")
        }
        return (if (profile.isNullOrBlank()) base else File(base, "profiles/$profile")).apply { mkdirs() }
    }
}

/** SQLCipher-compatible database through sqlite-jdbc-crypt. */
class DesktopDatabaseDriverFactory(private val dir: File) : DatabaseDriverFactory {
    override fun create(key: ByteArray): SqlDriver {
        val file = File(dir, "zoocall.db")
        val properties = SQLiteMCSqlCipherConfig.getV4Defaults()
            .withHexKey(Hex.encode(key))
            .build()
            .toProperties()
        return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties, ZoocallDatabase.Schema)
    }
}

/**
 * Desktop secret storage. Windows: DPAPI (current user). macOS: the login Keychain. Linux (and
 * macOS if the Keychain can't be used): an owner-only file (0600).
 */
class DesktopKeyStore(private val dir: File) : SecureKeyStore {
    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = os.contains("win")
    private val keychain: MacKeychain? = if (os.contains("mac")) {
        runCatching { MacKeychain(KEYCHAIN_SERVICE) }.onFailure { Logger.current.warn(TAG, "Keychain not available", it) }.getOrNull()
    } else {
        null
    }

    /** One Keychain item per data directory, so separate profiles keep separate identities. */
    private fun account(name: String) = "${dir.absolutePath}#$name"

    override suspend fun load(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val kc = keychain
        if (kc != null) {
            try {
                kc.load(account(name))?.let { return@withContext it }
                // A key saved as a file before the Keychain was used: move it in.
                val file = file(name)
                if (!file.exists()) return@withContext null
                val legacy = file.readBytes()
                if (runCatching { kc.save(account(name), legacy) }.isSuccess) file.delete()
                return@withContext legacy
            } catch (e: Exception) {
                Logger.current.warn(TAG, "Keychain read failed, using the key file", e)
            }
        }
        val file = file(name)
        if (!file.exists()) return@withContext null
        val blob = file.readBytes()
        if (isWindows) Crypt32Util.cryptUnprotectData(blob) else blob
    }

    override suspend fun save(name: String, secret: ByteArray) = withContext(Dispatchers.IO) {
        val kc = keychain
        if (kc != null) {
            val saved = runCatching { kc.save(account(name), secret) }
                .onFailure { Logger.current.warn(TAG, "Keychain write failed, using a key file", it) }
                .isSuccess
            if (saved) {
                file(name).delete()
                return@withContext
            }
        }
        val blob = if (isWindows) Crypt32Util.cryptProtectData(secret) else secret
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(blob)
        if (!isWindows) runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file(name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) {
            keychain?.let { kc -> runCatching { kc.delete(account(name)) } }
            file(name).delete()
        }
    }

    private fun file(name: String) = File(dir, "$name.key")

    private companion object {
        const val TAG = "KeyStore"
        const val KEYCHAIN_SERVICE = "io.github.flytorakib.zoocall"
    }
}
