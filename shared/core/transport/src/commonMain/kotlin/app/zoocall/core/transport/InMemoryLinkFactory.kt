package app.zoocall.core.transport

import app.zoocall.core.model.PeerAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-process network for tests and the CLI's loopback mode. Each [LinkFactory] from [host]
 * behaves like a device with its own IP.
 */
class InMemoryNetwork {
    private val lock = Mutex()
    private val listeners = HashMap<PeerAddress, Channel<ByteLink>>()

    fun host(ip: String): LinkFactory = HostFactory(ip)

    private inner class HostFactory(private val ip: String) : LinkFactory {
        override suspend fun listen(preferredPort: Int): LinkListener = lock.withLock {
            var port = preferredPort
            while (listeners.containsKey(PeerAddress(ip, port))) port++
            val address = PeerAddress(ip, port)
            val channel = Channel<ByteLink>(Channel.UNLIMITED)
            listeners[address] = channel
            object : LinkListener {
                override val port: Int = port
                override suspend fun accept(): ByteLink = try {
                    channel.receive()
                } catch (e: Exception) {
                    throw LinkClosedException("Listener closed")
                }
                override fun close() {
                    channel.close()
                    listeners.remove(address)
                }
            }
        }

        override suspend fun connect(address: PeerAddress, timeoutMs: Long): ByteLink {
            val listener = lock.withLock { listeners[address] } ?: throw LinkClosedException("Connection refused")
            val aToB = Pipe()
            val bToA = Pipe()
            val serverSide = PipeLink(remoteHost = ip, input = aToB, output = bToA)
            val clientSide = PipeLink(remoteHost = address.host, input = bToA, output = aToB)
            listener.send(serverSide)
            return clientSide
        }
    }

    private class Pipe {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    }

    private class PipeLink(override val remoteHost: String, private val input: Pipe, private val output: Pipe) : ByteLink {
        private var buffer = ByteArray(0)
        private var offset = 0

        override suspend fun readFully(size: Int): ByteArray {
            val out = ByteArray(size)
            var filled = 0
            while (filled < size) {
                if (offset == buffer.size) {
                    buffer = input.chunks.receiveCatching().getOrNull() ?: throw LinkClosedException()
                    offset = 0
                }
                val n = minOf(size - filled, buffer.size - offset)
                buffer.copyInto(out, filled, offset, offset + n)
                filled += n
                offset += n
            }
            return out
        }

        override suspend fun write(data: ByteArray) {
            if (output.chunks.trySend(data.copyOf()).isFailure) throw LinkClosedException()
        }

        override suspend fun flush() = Unit

        override fun close() {
            input.chunks.close()
            output.chunks.close()
        }
    }
}
