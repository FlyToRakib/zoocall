package app.zoocall.core.transport

import app.zoocall.core.model.PeerAddress

/** A reliable, ordered byte stream to one remote host (TCP in production, in-memory in tests). */
interface ByteLink {
    val remoteHost: String
    suspend fun readFully(size: Int): ByteArray
    suspend fun write(data: ByteArray)
    suspend fun flush()
    fun close()
}

interface LinkListener {
    val port: Int
    suspend fun accept(): ByteLink
    fun close()
}

interface LinkFactory {
    /** Binds [preferredPort], falling back to an OS-assigned port when it's taken. */
    suspend fun listen(preferredPort: Int): LinkListener
    suspend fun connect(address: PeerAddress, timeoutMs: Long): ByteLink
}

class LinkClosedException(message: String = "Link closed", cause: Throwable? = null) : Exception(message, cause)

/** `u16 BE length ‖ bytes` framing (docs/03-protocol.md §3.3). */
internal suspend fun ByteLink.readFrame(): ByteArray {
    val header = readFully(2)
    val length = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
    if (length == 0) throw LinkClosedException("Empty frame")
    return readFully(length)
}

internal suspend fun ByteLink.writeFrame(data: ByteArray) {
    require(data.size in 1..0xffff) { "Frame size out of range" }
    write(byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()) + data)
}
