package app.zoocall.core.store

/**
 * Platform port for secrets (docs/04-security-privacy.md §4): Android Keystore, Windows DPAPI,
 * macOS Keychain. Secrets are raw bytes; implementations wrap them with an OS-protected key.
 */
interface SecureKeyStore {
    /** Returns the secret stored under [name], or null if it doesn't exist. */
    suspend fun load(name: String): ByteArray?

    suspend fun save(name: String, secret: ByteArray)

    suspend fun delete(name: String)

    companion object {
        const val IDENTITY = "identity"
        const val DATABASE = "database"
    }
}

/** Returns the stored secret, creating and saving one with [create] on first use. */
suspend fun SecureKeyStore.getOrCreate(name: String, create: () -> ByteArray): ByteArray =
    load(name) ?: create().also { save(name, it) }
