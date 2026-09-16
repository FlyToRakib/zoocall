package app.zoocall.core.model

/**
 * A device identity: `BLAKE2b-256("zoocall-fp-v1" ‖ static_public_key)`.
 *
 * Stored as lowercase hex so it can be used directly as a map key and database primary key.
 */
class Fingerprint private constructor(val hex: String) : Comparable<Fingerprint> {

    val bytes: ByteArray get() = Hex.decode(hex)

    /** First 20 base32 characters, used in the mDNS TXT `fp` key. */
    val shortId: String get() = Base32.encode(bytes).take(SHORT_ID_LENGTH)

    /** A readable suffix for disambiguating people with the same name, e.g. "k3m9". */
    val tag: String get() = Base32.encode(bytes).take(4)

    override fun compareTo(other: Fingerprint): Int = hex.compareTo(other.hex)
    override fun equals(other: Any?): Boolean = other is Fingerprint && other.hex == hex
    override fun hashCode(): Int = hex.hashCode()
    override fun toString(): String = "Fingerprint(${hex.take(8)}…)"

    companion object {
        const val SIZE_BYTES = 32
        const val SHORT_ID_LENGTH = 20

        fun of(bytes: ByteArray): Fingerprint {
            require(bytes.size == SIZE_BYTES) { "Fingerprint must be $SIZE_BYTES bytes" }
            return Fingerprint(Hex.encode(bytes))
        }

        fun fromHex(hex: String): Fingerprint {
            val normalized = hex.lowercase()
            require(normalized.length == SIZE_BYTES * 2 && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid fingerprint hex"
            }
            return Fingerprint(normalized)
        }
    }
}
