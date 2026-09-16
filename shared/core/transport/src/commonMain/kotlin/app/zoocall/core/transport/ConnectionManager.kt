package app.zoocall.core.transport

import app.zoocall.core.crypto.Identity
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.Logger
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.protocol.FileLimits
import app.zoocall.core.protocol.ProtocolConstants
import app.zoocall.protocol.v1.ConnectionPurpose
import app.zoocall.protocol.v1.Envelope
import app.zoocall.protocol.v1.HandshakePayload
import app.zoocall.protocol.v1.Hello
import app.zoocall.protocol.v1.Ping
import app.zoocall.protocol.v1.Pong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Clock

data class TransportConfig(
    val preferredPort: Int = ProtocolConstants.DEFAULT_PORT,
    val appVersion: String,
    val deviceClass: DeviceClass,
    val maxConnections: Int = 64,
    val handshakesPerIpPerMinute: Int = 10,
)

/** A peer with an authenticated control connection and a received [Hello]. */
data class ConnectedPeer(
    val fingerprint: Fingerprint,
    val publicKey: ByteArray,
    val hello: Hello,
    /** Where the peer accepts connections (remote IP + advertised listen port). */
    val address: PeerAddress?,
    val safetyCode: String,
    val connectedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ConnectedPeer && other.fingerprint == fingerprint && other.hello == hello &&
            other.address == address && other.safetyCode == safetyCode
    override fun hashCode(): Int = fingerprint.hashCode()
}

sealed interface TransportEvent {
    data class Connected(val peer: ConnectedPeer) : TransportEvent
    data class Updated(val peer: ConnectedPeer) : TransportEvent
    data class Disconnected(val fingerprint: Fingerprint) : TransportEvent
    data class Received(val from: Fingerprint, val envelope: Envelope) : TransportEvent
}

/**
 * Owns every control connection: listening, dialing, handshake, Hello exchange, keepalive,
 * duplicate resolution, rate limiting and the connection cap (docs/02-architecture.md §8).
 */
class ConnectionManager(
    private val identity: Identity,
    private val config: TransportConfig,
    private val linkFactory: LinkFactory,
    private val helloProvider: () -> Hello,
    /** Return false to refuse a peer right after the handshake (e.g. blocked). */
    private val admit: (Fingerprint) -> Boolean = { true },
    private val logger: Logger = Logger.current,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // A failure in one connection's coroutine must never take down the process.
    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e -> logger.error(TAG, "Uncaught transport error", e) },
    )
    private val lock = Mutex()
    private val sessions = HashMap<Fingerprint, Session>()
    private val pendingDials = HashMap<PeerAddress, CompletableDeferred<Result<ConnectedPeer>>>()
    private val handshakeLog = HashMap<String, ArrayDeque<Long>>()
    private var listener: LinkListener? = null

    private val _peers = MutableStateFlow<Map<Fingerprint, ConnectedPeer>>(emptyMap())
    val peers: StateFlow<Map<Fingerprint, ConnectedPeer>> = _peers.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _listenPort = MutableStateFlow<Int?>(null)
    val listenPort: StateFlow<Int?> = _listenPort.asStateFlow()

    val localFingerprint: Fingerprint get() = identity.fingerprint

    /**
     * Receives inbound FILE connections (docs/03 §4.4) from connected peers: `(peer, fileId, connection)`.
     * The connection is closed when the handler returns. Unset: FILE connections are refused.
     */
    var fileConnectionHandler: (suspend (Fingerprint, String, SecureConnection) -> Unit)? = null

    private val fileConnections = HashMap<Fingerprint, Int>()

    suspend fun start() {
        if (listener != null) return
        val l = linkFactory.listen(config.preferredPort)
        listener = l
        _listenPort.value = l.port
        logger.info(TAG, "Listening on port ${l.port}")
        scope.launch {
            while (isActive) {
                val link = try {
                    l.accept()
                } catch (e: Exception) {
                    if (isActive) logger.warn(TAG, "Accept loop ended", e)
                    break
                }
                if (!allowHandshakeFrom(link.remoteHost)) {
                    logger.warn(TAG, "Handshake rate limit hit")
                    link.close()
                    continue
                }
                launch { handleNewLink(link, initiator = false) }
            }
        }
    }

    fun stop() {
        listener?.close()
        listener = null
        _listenPort.value = null
        scope.cancel()
        _peers.value = emptyMap()
        // [sessions] only changes under [lock]; a reader that was mid-update when the scope was
        // cancelled may still hold it, so close everything once it's free.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            lock.withLock {
                sessions.values.forEach { it.connection.close() }
                sessions.clear()
            }
        }
    }

    /** Dials [address] or returns the existing connection when that peer is already connected there. */
    suspend fun connect(address: PeerAddress): Result<ConnectedPeer> {
        _peers.value.values.firstOrNull { it.address == address }?.let { return Result.success(it) }
        val (deferred, owner) = lock.withLock {
            pendingDials[address]?.let { it to false }
                ?: CompletableDeferred<Result<ConnectedPeer>>().also { pendingDials[address] = it }.let { it to true }
        }
        if (owner) {
            scope.launch {
                val result = runCatching {
                    val link = linkFactory.connect(address, ProtocolConstants.HANDSHAKE_TIMEOUT_MS)
                    handleNewLink(link, initiator = true) ?: error("Connection was not kept")
                }
                result.exceptionOrNull()?.let { logger.info(TAG, "Dial failed: ${it.message}") }
                lock.withLock { pendingDials.remove(address) }
                deferred.complete(result)
            }
        }
        return deferred.await()
    }

    /** Sends to a connected peer. Returns false when the peer isn't connected or the write failed. */
    suspend fun send(to: Fingerprint, envelope: Envelope): Boolean {
        val session = lock.withLock { sessions[to] } ?: return false
        return try {
            session.connection.send(envelope)
            session.lastSentMs = now()
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Send failed, closing connection: ${e::class.simpleName}: ${e.message}", e)
            closeSession(session)
            false
        }
    }

    fun isConnected(fingerprint: Fingerprint): Boolean = _peers.value.containsKey(fingerprint)

    suspend fun disconnect(fingerprint: Fingerprint) {
        lock.withLock { sessions[fingerprint] }?.let { closeSession(it) }
    }

    /** Sends a fresh Hello (e.g. after a profile or presence change) to every connected peer. */
    suspend fun broadcastHello() {
        val targets = lock.withLock { sessions.keys.toList() }
        val hello = helloProvider()
        targets.forEach { send(it, Envelope(hello = hello)) }
    }

    /**
     * Opens a FILE connection to a connected [peer] for [fileId], runs [block] on it and closes it.
     * Returns null when the peer isn't reachable, has the wrong identity, or the per-peer limit is reached.
     */
    suspend fun <T> withFileConnection(peer: Fingerprint, fileId: String, block: suspend (SecureConnection) -> T): T? {
        val address = _peers.value[peer]?.address ?: return null
        if (!reserveFileSlot(peer)) return null
        var connection: SecureConnection? = null
        try {
            val link = linkFactory.connect(address, ProtocolConstants.HANDSHAKE_TIMEOUT_MS)
            val opened = SecureConnection.handshake(
                link = link,
                identity = identity,
                initiator = true,
                localPayload = HandshakePayload(
                    protocol_versions = listOf(ProtocolConstants.MAJOR_VERSION),
                    app_version = config.appVersion,
                    device_class = config.deviceClass.toProto(),
                    purpose = ConnectionPurpose.CONNECTION_PURPOSE_FILE,
                    file_id = fileId,
                    listen_port = _listenPort.value ?: 0,
                ),
            )
            connection = opened
            // Same key check as control connections: a different device at that address gets nothing.
            if (opened.remoteFingerprint != peer) {
                logger.warn(TAG, "FILE connection reached a different identity")
                return null
            }
            return block(opened)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.info(TAG, "FILE connection failed: ${e::class.simpleName}: ${e.message}")
            return null
        } finally {
            connection?.close()
            releaseFileSlot(peer)
        }
    }

    private suspend fun reserveFileSlot(peer: Fingerprint): Boolean = lock.withLock {
        val count = fileConnections[peer] ?: 0
        if (count >= FileLimits.MAX_CONNECTIONS_PER_PEER) return@withLock false
        fileConnections[peer] = count + 1
        true
    }

    private suspend fun releaseFileSlot(peer: Fingerprint) = lock.withLock {
        val count = (fileConnections[peer] ?: 1) - 1
        if (count <= 0) fileConnections.remove(peer) else fileConnections[peer] = count
    }

    private suspend fun acceptFileConnection(fp: Fingerprint, connection: SecureConnection) {
        val handler = fileConnectionHandler
        val fileId = connection.remotePayload.file_id
        // Only peers with a live, authenticated control connection may open FILE connections.
        if (handler == null || !isConnected(fp) || fileId.length !in 1..64 || !reserveFileSlot(fp)) {
            connection.close()
            return
        }
        try {
            handler(fp, fileId, connection)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.info(TAG, "FILE connection ended: ${e::class.simpleName}: ${e.message}")
        } finally {
            connection.close()
            releaseFileSlot(fp)
        }
    }

    private suspend fun handleNewLink(link: ByteLink, initiator: Boolean): ConnectedPeer? {
        val connection = try {
            SecureConnection.handshake(
                link = link,
                identity = identity,
                initiator = initiator,
                localPayload = HandshakePayload(
                    protocol_versions = listOf(ProtocolConstants.MAJOR_VERSION),
                    app_version = config.appVersion,
                    device_class = config.deviceClass.toProto(),
                    purpose = ConnectionPurpose.CONNECTION_PURPOSE_CONTROL,
                    listen_port = _listenPort.value ?: 0,
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Handshake failed: ${e::class.simpleName}: ${e.message}", e)
            if (initiator) throw e
            return null
        }
        val fp = connection.remoteFingerprint
        if (!admit(fp)) {
            logger.info(TAG, "Peer refused by policy")
            connection.close()
            if (initiator) error("Peer not allowed")
            return null
        }
        if (!initiator && connection.remotePayload.purpose == ConnectionPurpose.CONNECTION_PURPOSE_FILE) {
            acceptFileConnection(fp, connection)
            return null
        }

        // Exchange Hello before registering, so every registered session has a profile.
        val remoteHello = try {
            connection.send(Envelope(hello = helloProvider()))
            withTimeout(ProtocolConstants.HANDSHAKE_TIMEOUT_MS) {
                var hello: Hello? = null
                while (hello == null) hello = connection.receive().hello
                hello
            }
        } catch (e: Exception) {
            if (e is CancellationException && initiator) throw e
            logger.warn(TAG, "Hello exchange failed: ${e::class.simpleName}: ${e.message}", e)
            connection.close()
            if (initiator) throw e
            return null
        }

        val port = connection.remotePayload.listen_port
        val peer = ConnectedPeer(
            fingerprint = fp,
            publicKey = connection.remoteStaticKey,
            hello = remoteHello,
            address = if (port in 1..65_535 && connection.remoteHost.isNotEmpty()) {
                PeerAddress(connection.remoteHost, port)
            } else {
                null
            },
            safetyCode = connection.safetyCode,
            connectedAtMs = now(),
        )
        val session = Session(connection, peer, initiatedByLocal = initiator, lastReceivedMs = now(), lastSentMs = now())

        val kept = register(session)
        if (!kept) {
            connection.close()
            return lock.withLock { sessions[fp]?.peer }
        }
        _events.emit(TransportEvent.Connected(peer))
        session.readerJob = scope.launch { readLoop(session) }
        session.keepaliveJob = scope.launch { keepalive(session) }
        return peer
    }

    /** Applies the duplicate-connection rule and the connection cap. Returns whether [session] is kept. */
    private suspend fun register(session: Session): Boolean {
        val fp = session.peer.fingerprint
        var evicted: Session? = null
        val kept = lock.withLock {
            val existing = sessions[fp]
            if (existing != null) {
                // Keep the connection initiated by the lower fingerprint; if both have the same initiator, the newer wins.
                val preferredInitiator = minOf(identity.fingerprint, fp)
                val newInitiator = if (session.initiatedByLocal) identity.fingerprint else fp
                val oldInitiator = if (existing.initiatedByLocal) identity.fingerprint else fp
                val keepNew = newInitiator == oldInitiator || newInitiator == preferredInitiator
                if (!keepNew) return@withLock false
                evicted = existing
            } else if (sessions.size >= config.maxConnections) {
                evicted = sessions.values.minByOrNull { maxOf(it.lastReceivedMs, it.lastSentMs) }
            }
            evicted?.let { sessions.remove(it.peer.fingerprint) }
            sessions[fp] = session
            _peers.update { current -> (current - (evicted?.peer?.fingerprint ?: fp)) + (fp to session.peer) }
            true
        }
        evicted?.let { old ->
            old.closedByManager = true
            old.readerJob?.cancel()
            old.keepaliveJob?.cancel()
            old.connection.close()
            if (old.peer.fingerprint != fp) _events.emit(TransportEvent.Disconnected(old.peer.fingerprint))
        }
        return kept
    }

    private suspend fun readLoop(session: Session) {
        val fp = session.peer.fingerprint
        try {
            while (scope.isActive) {
                val envelope = session.connection.receive()
                session.lastReceivedMs = now()
                val ping = envelope.ping
                val hello = envelope.hello
                when {
                    ping != null -> send(fp, Envelope(pong = Pong(nonce = ping.nonce)))
                    envelope.pong != null -> Unit
                    hello != null -> {
                        val updated = session.peer.copy(hello = hello)
                        session.peer = updated
                        lock.withLock { if (sessions[fp] === session) _peers.update { it + (fp to updated) } }
                        _events.emit(TransportEvent.Updated(updated))
                    }
                    else -> _events.emit(TransportEvent.Received(fp, envelope))
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                logger.warn(TAG, "Connection closed by ${if (session.closedByManager) "us" else "read error"}: ${e::class.simpleName}: ${e.message}", e)
            }
        } finally {
            closeSession(session)
        }
    }

    private suspend fun keepalive(session: Session) {
        while (scope.isActive) {
            delay(ProtocolConstants.KEEPALIVE_INTERVAL_MS / 5)
            val t = now()
            if (t - session.lastReceivedMs > ProtocolConstants.DEAD_PEER_TIMEOUT_MS) {
                logger.info(TAG, "Peer timed out")
                closeSession(session)
                return
            }
            if (t - session.lastSentMs >= ProtocolConstants.KEEPALIVE_INTERVAL_MS) {
                send(session.peer.fingerprint, Envelope(ping = Ping(nonce = Random.nextLong())))
            }
        }
    }

    private suspend fun closeSession(session: Session) {
        val fp = session.peer.fingerprint
        val removed = lock.withLock {
            if (sessions[fp] === session) {
                sessions.remove(fp)
                _peers.update { it - fp }
                true
            } else {
                false
            }
        }
        session.connection.close()
        session.keepaliveJob?.cancel()
        if (removed && !session.closedByManager) {
            session.closedByManager = true
            _events.emit(TransportEvent.Disconnected(fp))
        }
    }

    private suspend fun allowHandshakeFrom(host: String): Boolean = lock.withLock {
        val t = now()
        val log = handshakeLog.getOrPut(host) { ArrayDeque() }
        while (log.isNotEmpty() && t - log.first() > 60_000) log.removeFirst()
        if (log.size >= config.handshakesPerIpPerMinute) return@withLock false
        log.addLast(t)
        if (handshakeLog.size > 1024) handshakeLog.entries.removeAll { it.value.isEmpty() }
        true
    }

    private class Session(
        val connection: SecureConnection,
        var peer: ConnectedPeer,
        val initiatedByLocal: Boolean,
        var lastReceivedMs: Long,
        var lastSentMs: Long,
    ) {
        var readerJob: Job? = null
        var keepaliveJob: Job? = null
        var closedByManager = false
    }

    private companion object {
        const val TAG = "Transport"
    }
}
