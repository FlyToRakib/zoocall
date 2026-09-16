package app.zoocall.core.model

import kotlin.jvm.JvmInline
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Call identifier: a UUIDv7 in lowercase canonical form. Ordering matters for collision resolution. */
@JvmInline
value class CallId(val value: String) : Comparable<CallId> {
    override fun compareTo(other: CallId): Int = value.compareTo(other.value)
    override fun toString(): String = value

    companion object {
        fun generate(nowMs: Long = Clock.System.now().toEpochMilliseconds()): CallId {
            val bytes = Uuid.random().toByteArray()
            for (i in 0 until 6) bytes[i] = (nowMs ushr (8 * (5 - i))).toByte()
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte() // version 7
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // RFC 4122 variant
            return CallId(Uuid.fromByteArray(bytes).toString())
        }
    }
}

/** Message identifier: a ULID (26 chars, Crockford base32, lexicographically sortable by time). */
@JvmInline
value class MessageId(val value: String) : Comparable<MessageId> {
    override fun compareTo(other: MessageId): Int = value.compareTo(other.value)
    override fun toString(): String = value

    companion object {
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun generate(nowMs: Long = Clock.System.now().toEpochMilliseconds()): MessageId {
            val random = Uuid.random().toByteArray()
            val chars = CharArray(26)
            var time = nowMs
            for (i in 9 downTo 0) {
                chars[i] = CROCKFORD[(time and 0x1f).toInt()]
                time = time ushr 5
            }
            // 80 bits of randomness → 16 chars
            var buffer = 0L
            var bits = 0
            var out = 10
            var index = 0
            while (out < 26) {
                if (bits < 5) {
                    buffer = (buffer shl 8) or (random[index++].toLong() and 0xff)
                    bits += 8
                }
                chars[out++] = CROCKFORD[((buffer ushr (bits - 5)) and 0x1f).toInt()]
                bits -= 5
            }
            return MessageId(chars.concatToString())
        }
    }
}
