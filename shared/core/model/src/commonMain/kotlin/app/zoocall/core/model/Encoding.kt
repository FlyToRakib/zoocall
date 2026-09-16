package app.zoocall.core.model

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(DIGITS[v ushr 4])
            append(DIGITS[v and 0x0f])
        }
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            ((digit(hex[i * 2]) shl 4) or digit(hex[i * 2 + 1])).toByte()
        }
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex character")
    }
}

/** RFC 4648 base32, lowercase, no padding. */
object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(bytes: ByteArray): String = buildString((bytes.size * 8 + 4) / 5) {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer ushr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    }
}

/** RFC 4648 §5 base64url without padding (used in QR / deep links). */
object Base64Url {
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private val codec = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT_OPTIONAL)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun encode(bytes: ByteArray): String = codec.encode(bytes)

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun decode(text: String): ByteArray = codec.decode(text)
}
