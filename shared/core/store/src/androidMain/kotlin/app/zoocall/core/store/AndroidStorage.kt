package app.zoocall.core.store

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.zoocall.core.store.db.ZoocallDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SQLCipher database in app-private storage (excluded from backup by the manifest rules). */
class AndroidDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun create(key: ByteArray): SqlDriver {
        System.loadLibrary("sqlcipher")
        return AndroidSqliteDriver(
            schema = ZoocallDatabase.Schema,
            context = context,
            name = "zoocall.db",
            factory = SupportOpenHelperFactory(key),
        )
    }
}

/**
 * Wraps secrets with an AES-256-GCM key that never leaves the Android Keystore (StrongBox when
 * available). Wrapped blobs live in `noBackupFilesDir`.
 */
class AndroidKeystoreKeyStore(context: Context) : SecureKeyStore {
    private val dir = File(context.noBackupFilesDir, "keys").apply { mkdirs() }

    override suspend fun load(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = file(name)
        if (!file.exists()) return@withContext null
        val blob = file.readBytes()
        if (blob.size <= IV_SIZE) return@withContext null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
        cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    override suspend fun save(name: String, secret: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        check(iv.size == IV_SIZE)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(iv + cipher.doFinal(secret))
        check(tmp.renameTo(file(name)) || (file(name).delete() && tmp.renameTo(file(name))))
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { file(name).delete() }
    }

    private fun file(name: String) = File(dir, "$name.bin")

    @Synchronized
    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        } catch (e: Exception) {
            generate(strongBox = false)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "zoocall.wrap.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
