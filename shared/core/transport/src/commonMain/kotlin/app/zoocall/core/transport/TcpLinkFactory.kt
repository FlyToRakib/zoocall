package app.zoocall.core.transport

import app.zoocall.core.model.PeerAddress
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withTimeout

class TcpLinkFactory(
    private val selector: SelectorManager = SelectorManager(Dispatchers.IO),
) : LinkFactory {

    override suspend fun listen(preferredPort: Int): LinkListener {
        val server = runCatching { aSocket(selector).tcp().bind(port = preferredPort) }
            .getOrElse { aSocket(selector).tcp().bind(port = 0) }
        return TcpListener(server)
    }

    override suspend fun connect(address: PeerAddress, timeoutMs: Long): ByteLink {
        val socket = withTimeout(timeoutMs) {
            aSocket(selector).tcp().connect(address.host, address.port) { noDelay = true }
        }
        return TcpLink(socket)
    }

    private class TcpListener(private val server: ServerSocket) : LinkListener {
        override val port: Int = (server.localAddress as InetSocketAddress).port
        override suspend fun accept(): ByteLink = TcpLink(server.accept())
        override fun close() = server.close()
    }

    private class TcpLink(private val socket: Socket) : ByteLink {
        private val input: ByteReadChannel = socket.openReadChannel()
        private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = false)

        override val remoteHost: String = (socket.remoteAddress as? InetSocketAddress)?.hostname.orEmpty()

        override suspend fun readFully(size: Int): ByteArray {
            val buffer = ByteArray(size)
            try {
                input.readFully(buffer, 0, size)
            } catch (e: Exception) {
                throw LinkClosedException("Read failed: ${e::class.simpleName}: ${e.message}", e)
            }
            return buffer
        }

        override suspend fun write(data: ByteArray) {
            try {
                output.writeFully(data, 0, data.size)
            } catch (e: Exception) {
                throw LinkClosedException("Write failed: ${e::class.simpleName}: ${e.message}", e)
            }
        }

        override suspend fun flush() {
            try {
                output.flush()
            } catch (e: Exception) {
                throw LinkClosedException("Flush failed: ${e::class.simpleName}: ${e.message}", e)
            }
        }

        override fun close() = socket.close()
    }
}
