package app.zoocall.core.app

import app.zoocall.core.call.ActiveCall
import app.zoocall.core.call.CallManager
import app.zoocall.core.call.CallState
import app.zoocall.core.call.IncomingDecision
import app.zoocall.core.chat.ChatManager
import app.zoocall.core.chat.IncomingMessage
import app.zoocall.core.call.CallPhase
import app.zoocall.core.chat.KnockEvent
import app.zoocall.core.chat.KnockManager
import app.zoocall.core.chat.KnockReply
import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.SafetyCodes
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.discovery.DiscoveredService
import app.zoocall.core.discovery.DiscoveryEvent
import app.zoocall.core.discovery.ServiceRecord
import app.zoocall.core.files.FileTransferManager
import app.zoocall.core.model.AllowCallsFrom
import app.zoocall.core.model.Base32
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.Contact
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.PeerAddress
import app.zoocall.core.model.Presence
import app.zoocall.core.model.Visibility
import app.zoocall.core.protocol.Capabilities
import app.zoocall.core.protocol.FileLimits
import app.zoocall.core.store.CallLogEntry
import app.zoocall.core.store.ChatMessageRecord
import app.zoocall.core.store.ConversationSummary
import app.zoocall.core.store.ReactionRecord
import app.zoocall.core.store.SecureKeyStore
import app.zoocall.core.store.ZoocallStore
import app.zoocall.core.store.getOrCreate
import app.zoocall.core.transport.ConnectedPeer
import app.zoocall.core.transport.ConnectionManager
import app.zoocall.core.transport.TransportConfig
import app.zoocall.core.transport.TransportEvent
import app.zoocall.core.transport.toModel
import app.zoocall.core.transport.toProto
import app.zoocall.protocol.v1.Hello
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.Clock

/**
 * The application facade (docs/02-architecture.md §3 `:shared:core:app`). View models talk only to this.
 * Lifecycle: [initialize] once per process → [completeOnboarding] if needed → running.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ZoocallCore(private val platform: Platform) {
    private val logger = platform.logger
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> platform.logger.error("Core", "Uncaught core error", e) },
    )
    private val initLock = Mutex()
    private fun now() = Clock.System.now().toEpochMilliseconds()

    private val _status = MutableStateFlow<CoreStatus>(CoreStatus.Starting)
    val status: StateFlow<CoreStatus> = _status.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _services = MutableStateFlow<Map<String, DiscoveredService>>(emptyMap())
    private val started = MutableStateFlow(false)

    private lateinit var identity: Identity
    private lateinit var store: ZoocallStore
    private lateinit var transport: ConnectionManager
    private lateinit var callManager: CallManager
    private lateinit var chatManager: ChatManager
    private lateinit var files: FileTransferManager
    private lateinit var knockManager: KnockManager
    private lateinit var instanceName: String
    private var discoveryJob: Job? = null

    val network: StateFlow<NetworkState> get() = platform.network.state
    val mediaEngine get() = platform.mediaEngine

    val localFingerprint: Fingerprint get() = identity.fingerprint

    private val runningFlow = started.map { it }.distinctUntilChanged()

    val people: StateFlow<List<Person>> = runningFlow.flatMapLatest { running ->
        if (!running) flowOf(emptyList()) else combine(store.contacts.all, _services, transport.peers, tagIndex) { contacts, services, peers, _ ->
            buildPeople(contacts, services, peers)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Blocked people, for the block list in Settings. */
    val blockedPeople: Flow<List<Contact>> = runningFlow.flatMapLatest { running ->
        if (running) store.contacts.all.map { list -> list.filter { it.blocked } } else flowOf(emptyList())
    }

    val activeCall: StateFlow<ActiveCall?> = runningFlow.flatMapLatest { running ->
        if (running) callManager.activeCall else flowOf(null)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val finishedCalls: Flow<CallState> = runningFlow.flatMapLatest { if (it) callManager.finishedCalls else emptyFlow() }

    val incomingMessages: Flow<IncomingMessage> = runningFlow.flatMapLatest { if (it) chatManager.incoming else emptyFlow() }

    /** Knocks from others and replies to ours. */
    val knocks: Flow<KnockEvent> = runningFlow.flatMapLatest { if (it) knockManager.events else emptyFlow() }

    /** 1:1 conversations and group chats, most recent first. */
    val conversations: Flow<List<ConversationSummary>> = runningFlow.flatMapLatest { running ->
        if (running) {
            combine(store.messages.conversations, store.messages.groupConversations) { direct, groups ->
                (direct + groups).sortedByDescending { it.lastAtMs }
            }
        } else {
            flowOf(emptyList())
        }
    }

    val unreadCount: Flow<Int> = runningFlow.flatMapLatest { if (it) store.messages.totalUnread else flowOf(0) }

    val recents: Flow<List<CallLogEntry>> = runningFlow.flatMapLatest { if (it) store.calls.recent else flowOf(emptyList()) }

    val unseenMissedCalls: Flow<Int> = runningFlow.flatMapLatest { if (it) store.calls.unseenMissed else flowOf(0) }

    val typing: Flow<Set<Fingerprint>> = runningFlow.flatMapLatest { if (it) chatManager.typing else flowOf(emptySet()) }

    val listenPort: Flow<Int?> = runningFlow.flatMapLatest { if (it) transport.listenPort else flowOf(null) }

    /** Live byte counts of file transfers in progress, by message id. */
    val transferProgress: Flow<Map<MessageId, Long>> = runningFlow.flatMapLatest { if (it) files.progress else flowOf(emptyMap()) }

    /** Outgoing files the user paused. */
    val pausedUploads: Flow<Set<MessageId>> = runningFlow.flatMapLatest { if (it) files.pausedOutgoing else flowOf(emptySet()) }

    // -- Network Doctor ---------------------------------------------------------------------------

    /**
     * Diagnoses why peers can't see each other (docs/05 §5.7, edge cases N1–N6, N14, N20, N21).
     * Waits a few seconds for discovery answers and dials a few known contacts directly.
     */
    suspend fun runNetworkDoctor(): DoctorReport = kotlinx.coroutines.coroutineScope {
        val network = platform.network.state.value
        val running = started.value
        val platformChecks = async(Dispatchers.IO) {
            try {
                platform.diagnostics.run(network)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.warn(TAG, "Platform diagnostics failed", e)
                emptyList()
            }
        }
        val selfId = if (running) identity.fingerprint.shortId else null
        fun othersSeen() = _services.value.values.count { it.txt.fingerprintShortId != selfId }
        val contacts = contactsCache.value.values.filter { !it.blocked }
        val probe = async {
            if (!running || !network.onLocalNetwork || transport.peers.value.isNotEmpty()) return@async ProbeOutcome.NotTried
            val targets = contacts.mapNotNull { it.lastAddress }.distinct().take(NetworkDoctor.MAX_PROBES)
            if (targets.isEmpty()) return@async ProbeOutcome.NotTried
            val reached = targets.map { address ->
                async { kotlinx.coroutines.withTimeoutOrNull(NetworkDoctor.PROBE_TIMEOUT_MS) { transport.connect(address).isSuccess } == true }
            }.awaitAll()
            if (reached.any { it }) ProbeOutcome.Reached else ProbeOutcome.Failed
        }
        if (running && network.onLocalNetwork && othersSeen() == 0) {
            kotlinx.coroutines.withTimeoutOrNull(NetworkDoctor.DISCOVERY_WAIT_MS) {
                _services.first { services -> services.values.any { it.txt.fingerprintShortId != selfId } }
            }
        }
        val outcome = probe.await()
        val inputs = NetworkDoctor.Inputs(
            network = network,
            port = if (running) transport.listenPort.value else null,
            discovered = othersSeen(),
            connected = if (running) transport.peers.value.size else 0,
            probe = outcome,
            contactHosts = contacts.mapNotNull { it.lastAddress?.host },
            platform = platformChecks.await(),
        )
        DoctorReport(NetworkDoctor.evaluate(inputs), network, inputs.port)
    }

    /** Fixes a platform issue found by [runNetworkDoctor], or opens the settings page that does. */
    suspend fun fixNetworkIssue(check: DoctorCheck): Boolean = withContext(Dispatchers.IO) {
        try {
            platform.diagnostics.fix(check)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Couldn't fix $check", e)
            false
        }
    }

    // -- Lifecycle --------------------------------------------------------------------------------

    suspend fun initialize() = initLock.withLock {
        if (_status.value != CoreStatus.Starting) return@withLock
        try {
            Sodium.ensureInitialized()
            val keys = platform.keyStore
            identity = Identity.fromSecret(keys.getOrCreate(SecureKeyStore.IDENTITY) { Sodium.randomBytes(32) })
            val dbKey = keys.getOrCreate(SecureKeyStore.DATABASE) { Sodium.randomBytes(32) }
            store = ZoocallStore(platform.databaseFactory.create(dbKey))
            Sodium.wipe(dbKey)
            val raw = store.settings.snapshot()
            instanceName = raw[KEY_INSTANCE] ?: Base32.encode(Sodium.randomBytes(8)).take(12).also { store.settings.put(KEY_INSTANCE, it) }
            _settings.value = raw.toSettings()
            platform.mediaEngine.setStrongNoiseSuppression(_settings.value.strongNoiseSuppression)
            buildServices()
            if (_settings.value.isOnboarded) startNetworking() else _status.value = CoreStatus.NeedsOnboarding
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error(TAG, "Initialization failed", e)
            _status.value = CoreStatus.Failed(e.message ?: "Couldn't start Zoocall")
        }
    }

    suspend fun completeOnboarding(displayName: String, role: String) {
        updateProfile(displayName, role)
        if (!started.value) startNetworking()
    }

    private fun buildServices() {
        transport = ConnectionManager(
            identity = identity,
            config = TransportConfig(appVersion = platform.appVersion, deviceClass = platform.deviceClass),
            linkFactory = app.zoocall.core.transport.TcpLinkFactory(),
            helloProvider = ::hello,
            admit = { fp -> blockedCache.value.none { it == fp } },
            logger = logger,
        )
        callManager = CallManager(
            scope,
            transport,
            platform.mediaEngine,
            policy = ::decideIncoming,
            pttPolicy = ::decidePushToTalk,
            intercomPolicy = ::decideIntercom,
            logger = logger,
        )
        files = FileTransferManager(
            scope = scope,
            transport = transport,
            attachments = store.attachments,
            fileSystem = platform.fileSystem,
            directory = platform.attachmentsDir,
            // Edge cases M7/M10: only contacts' files download by themselves, and only up to a sane size.
            autoDownload = { fp, meta -> contactsCache.value[fp]?.let { !it.blocked } == true && meta.size <= FileLimits.AUTO_DOWNLOAD_MAX },
            logger = logger,
        )
        chatManager = ChatManager(
            scope = scope,
            transport = transport,
            messages = store.messages,
            attachments = store.attachments,
            groupChats = store.groupChats,
            readReceiptsEnabled = { _settings.value.readReceipts },
            isQuiet = ::isQuietFor,
            onAttachmentReceived = files::onAttachmentReceived,
            disappearAfterS = ::disappearingSeconds,
            onPeerTimer = { peer, seconds -> if (seconds in DisappearingTimer.OPTIONS) setDisappearingTimer(peer, seconds) },
            logger = logger,
        )
        knockManager = KnockManager(
            scope = scope,
            transport = transport,
            isContact = { fp -> contactsCache.value[fp]?.let { !it.blocked } == true },
            isQuiet = ::isQuietFor,
            logger = logger,
        )
    }

    private val blockedCache = MutableStateFlow<Set<Fingerprint>>(emptySet())
    private val contactsCache = MutableStateFlow<Map<Fingerprint, Contact>>(emptyMap())

    /** Secrets shared with each contact, for private discovery tags (docs/03 §2). */
    private val pairSecrets = MutableStateFlow<Map<Fingerprint, ByteArray>>(emptyMap())

    /** Tags that identify contacts' private advertisements in the previous, current and next epoch. */
    private val tagIndex = MutableStateFlow<Map<String, Fingerprint>>(emptyMap())

    private data class Advertisement(val network: NetworkState, val settings: AppSettings, val port: Int?, val privateKey: Pair<Set<Fingerprint>, Long>?)

    private suspend fun startNetworking() {
        callManager.start()
        chatManager.start()
        knockManager.start()
        withContext(Dispatchers.IO) { files.start() }
        scope.launch {
            store.contacts.all.collect { list ->
                contactsCache.value = list.associateBy { it.fingerprint }
                blockedCache.value = list.filter { it.blocked }.map { it.fingerprint }.toSet()
                refreshPairSecrets(list)
            }
        }
        scope.launch {
            combine(pairSecrets, tagEpochs()) { secrets, epoch -> secrets to epoch }.collect { (secrets, epoch) ->
                tagIndex.value = buildMap {
                    for ((fp, secret) in secrets) for (e in epoch - 1..epoch + 1) put(DiscoveryTags.tag(secret, e), fp)
                }
            }
        }
        transport.start()
        started.value = true
        _status.value = CoreStatus.Running

        scope.launch { transport.events.collect(::onTransportEvent) }
        scope.launch { callManager.finishedCalls.collect(::recordCall) }
        // Presence shows "Busy" during a call; tell everyone whenever that changes, however the call ended.
        scope.launch {
            callManager.activeCall.map { it?.state?.isActive == true }.distinctUntilChanged().collect { transport.broadcastHello() }
        }
        scope.launch {
            combine(platform.network.state, _settings, transport.listenPort, pairSecrets, tagEpochs()) { net, s, port, secrets, epoch ->
                // Only a private advertisement changes with contacts and every epoch.
                Advertisement(net, s, port, if (s.visibility == Visibility.ContactsOnly) secrets.keys to epoch else null)
            }
                .distinctUntilChanged()
                .collect { updateAdvertisement(it.network, it.settings, it.port, it.privateKey?.second) }
        }
        scope.launch { reconnectContactsLoop() }
        scope.launch { deleteExpiredMessagesLoop() }
        // Linked devices travel in Hello: tell everyone when the list changes.
        scope.launch {
            contactsCache.map { linkedDeviceHexes(it.values) }.distinctUntilChanged().drop(1).collect { transport.broadcastHello() }
        }
        logger.info(TAG, "Zoocall core running")
    }

    // -- Discovery --------------------------------------------------------------------------------

    /** The current private-discovery epoch, emitted again at every boundary. */
    private fun tagEpochs(): Flow<Long> = kotlinx.coroutines.flow.flow {
        while (true) {
            val nowMs = now()
            emit(DiscoveryTags.epoch(nowMs))
            delay(DiscoveryTags.EPOCH_MS - nowMs % DiscoveryTags.EPOCH_MS + 50)
        }
    }

    private suspend fun refreshPairSecrets(contacts: List<Contact>) {
        val current = pairSecrets.value
        val next = HashMap<Fingerprint, ByteArray>()
        for (contact in contacts) {
            if (contact.blocked) continue
            next[contact.fingerprint] = current[contact.fingerprint] ?: run {
                val key = store.contacts.publicKey(contact.fingerprint) ?: return@run null
                runCatching { DiscoveryTags.pairSecret(identity, key) }.getOrNull()
            } ?: continue
        }
        pairSecrets.value = next
    }

    /** Our tags for the contacts most likely to look for us: favorites, then the most recently seen. */
    private fun privateTags(epoch: Long): List<String> {
        val secrets = pairSecrets.value
        return contactsCache.value.values
            .filter { !it.blocked && secrets.containsKey(it.fingerprint) }
            .sortedWith(compareByDescending<Contact> { it.favorite }.thenByDescending { it.lastSeenMs ?: 0 })
            .take(DiscoveryTags.MAX_TAGS)
            .map { DiscoveryTags.tag(secrets.getValue(it.fingerprint), epoch) }
            // Order carries no meaning.
            .sorted()
    }

    /** Which contact a discovered service belongs to: by its fingerprint, or by a private tag only we can match. */
    private fun contactShortId(service: DiscoveredService): String? =
        service.txt.fingerprintShortId ?: service.txt.tags.firstNotNullOfOrNull { tagIndex.value[it] }?.shortId

    private fun updateAdvertisement(net: NetworkState, s: AppSettings, port: Int?, privateEpoch: Long?) {
        val discovery = platform.discovery
        if (!net.onLocalNetwork || port == null) {
            discovery.stopAdvertising()
            discoveryJob?.cancel()
            discoveryJob = null
            _services.value = emptyMap()
            return
        }
        when (s.visibility) {
            Visibility.Everyone -> discovery.advertise(
                ServiceRecord(
                    instanceName = instanceName,
                    port = port,
                    fingerprintShortId = identity.fingerprint.shortId,
                    displayName = s.displayName,
                    role = s.role,
                    deviceClass = platform.deviceClass,
                ),
            )
            Visibility.ContactsOnly -> {
                val epoch = privateEpoch ?: DiscoveryTags.epoch(now())
                discovery.advertise(
                    ServiceRecord(
                        // A new instance name each epoch, so an advertisement can't be followed over time.
                        instanceName = DiscoveryTags.rotatingInstanceName(instanceName, epoch),
                        port = port,
                        fingerprintShortId = null,
                        displayName = null,
                        role = null,
                        deviceClass = platform.deviceClass,
                        tags = privateTags(epoch),
                    ),
                )
            }
            Visibility.Hidden -> discovery.stopAdvertising()
        }
        if (discoveryJob == null) {
            discoveryJob = scope.launch {
                try {
                    discovery.browse().collect { event ->
                        when (event) {
                            is DiscoveryEvent.Found -> {
                                _services.update { it + (event.service.instanceName to event.service) }
                                autoConnectIfContact(event.service)
                            }
                            is DiscoveryEvent.Lost -> _services.update { it - event.instanceName }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warn(TAG, "Discovery stopped", e)
                }
            }
        }
    }

    private fun autoConnectIfContact(service: DiscoveredService) {
        val shortId = contactShortId(service) ?: return
        val contact = contactsCache.value.values.firstOrNull { it.fingerprint.shortId == shortId && !it.blocked } ?: return
        if (transport.isConnected(contact.fingerprint)) return
        scope.launch { dial(service.addresses) }
    }

    /** Keeps contacts' control connections up for instant presence and ringing (docs/02 §8). */
    private suspend fun reconnectContactsLoop() {
        while (true) {
            delay(RECONNECT_INTERVAL_MS)
            if (!platform.network.state.value.onLocalNetwork) continue
            val services = _services.value.values
            for (contact in contactsCache.value.values) {
                if (contact.blocked || transport.isConnected(contact.fingerprint)) continue
                val discovered = services.firstOrNull { contactShortId(it) == contact.fingerprint.shortId }?.addresses
                val candidates = discovered ?: listOfNotNull(contact.lastAddress)
                if (candidates.isNotEmpty()) scope.launch { dial(candidates) }
            }
            // A contact's linked devices ring with them, so keep those connected as well when they're visible.
            val contactPeers = transport.peers.value.values.filter { contactsCache.value[it.fingerprint]?.blocked == false }
            for (hex in contactPeers.flatMap { it.hello.linked_devices }.toSet()) {
                val fp = runCatching { Fingerprint.fromHex(hex) }.getOrNull() ?: continue
                if (fp == identity.fingerprint || fp in blockedCache.value || contactsCache.value.containsKey(fp) || transport.isConnected(fp)) continue
                val addresses = services.firstOrNull { it.txt.fingerprintShortId == fp.shortId }?.addresses ?: continue
                scope.launch { dial(addresses) }
            }
        }
    }

    private suspend fun dial(addresses: List<PeerAddress>): ConnectedPeer? {
        for (address in addresses) {
            transport.connect(address).onSuccess { return it }
        }
        return null
    }

    // -- Transport events -------------------------------------------------------------------------

    private suspend fun onTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.Connected -> contactsCache.value[event.peer.fingerprint]?.let {
                store.contacts.updateSeen(it.fingerprint, event.peer.address, now())
                syncContactProfile(it, event.peer.hello)
            }
            is TransportEvent.Updated -> contactsCache.value[event.peer.fingerprint]?.let { syncContactProfile(it, event.peer.hello) }
            is TransportEvent.Disconnected -> contactsCache.value[event.fingerprint]?.let {
                store.contacts.updateSeen(it.fingerprint, it.lastAddress, now())
            }
            is TransportEvent.Received -> Unit
        }
    }

    private suspend fun syncContactProfile(contact: Contact, hello: Hello) {
        val name = hello.display_name.sanitizedName()
        if (name.isNotEmpty() && (name != contact.displayName || hello.role != contact.role)) {
            store.contacts.updateProfile(contact.fingerprint, name, hello.role.take(64))
        }
    }

    private suspend fun recordCall(call: CallState) {
        // A recording ends with its call.
        recordingLock.withLock { if (recordingCallId == call.callId.value) finishRecordingLocked() }
        val recording = finishedRecordings.remove(call.callId.value)
        // Push-to-talk sessions are walkie-talkie use, not phone calls: they stay out of Recents.
        // A call picked up on another linked device is in that device's Recents.
        if (call.pushToTalk || call.endReason == app.zoocall.core.model.EndReason.AnsweredElsewhere) return
        // Keep the caller's name for Recents even after they leave the network.
        rememberChatPartner(call.peer)
        store.calls.record(
            CallLogEntry(
                callId = call.callId.value,
                peer = call.peer,
                direction = call.direction,
                kind = call.kind,
                startedAtMs = call.startedAtMs,
                connectedAtMs = call.connectedAtMs,
                endedAtMs = call.endedAtMs ?: now(),
                endReason = call.endReason ?: app.zoocall.core.model.EndReason.Completed,
                recordingMs = recording?.second,
            ),
            recordingKey = recording?.first,
        )
    }

    // -- Call recording (docs/01 §4: with mandatory consent) -----------------------------------------

    private val recordingLock = Mutex()
    private var recorder: app.zoocall.media.CallRecording? = null
    private var recordingWriter: app.zoocall.core.files.EncryptedFileWriter? = null
    private var recordingCallId: String? = null
    private var recordingFileKey: ByteArray? = null

    /** Recordings of calls that haven't reached Recents yet: call id → (file key, length). */
    private val finishedRecordings = HashMap<String, Pair<ByteArray, Long>>()

    fun canRecordCall(): Boolean = started.value && recorder == null && callManager.canRecord() && platform.mediaEngine.createCallRecorderSupported

    /**
     * Records the current call to an encrypted file on this device. Everyone in the call is told
     * first (banner and chime on every device), so recording is never silent (docs/04, edge case H6).
     */
    suspend fun startRecording(): Boolean = recordingLock.withLock {
        val callId = activeCall.value?.state?.callId?.value ?: return@withLock false
        if (recorder != null || !callManager.canRecord()) return@withLock false
        val key = app.zoocall.core.files.EncryptedFiles.newKey()
        val writer = try {
            withContext(Dispatchers.IO) { app.zoocall.core.files.EncryptedFiles.create(platform.fileSystem, recordingPath(callId), key) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Couldn't create the recording file", e)
            return@withLock false
        }
        val newRecorder = platform.mediaEngine.createCallRecorder(
            output = { packet -> runCatching { writer.write(packet) } },
            onLimitReached = { scope.launch { stopRecording() } },
        )
        if (newRecorder == null || !callManager.setRecording(newRecorder)) {
            runCatching { writer.close() }
            return@withLock false
        }
        newRecorder.start()
        recorder = newRecorder
        recordingWriter = writer
        recordingCallId = callId
        recordingFileKey = key
        true
    }

    suspend fun stopRecording() = recordingLock.withLock { finishRecordingLocked() }

    /** Must be called with [recordingLock] held. */
    private suspend fun finishRecordingLocked() {
        val active = recorder ?: return
        recorder = null
        callManager.setRecording(null)
        val durationMs = active.stop()
        val writer = recordingWriter
        val callId = recordingCallId
        val key = recordingFileKey
        recordingWriter = null
        recordingCallId = null
        recordingFileKey = null
        withContext(Dispatchers.IO) {
            runCatching { writer?.finish() }
            runCatching { writer?.close() }
        }
        if (callId != null && key != null && durationMs > 0) finishedRecordings[callId] = key to durationMs
    }

    /** A playable WAV copy of a call's recording for the user to save; null when there's none or it can't be read. */
    suspend fun exportRecording(callId: String): Path? = withContext(Dispatchers.IO) {
        val key = store.calls.recordingKey(callId) ?: return@withContext null
        val audio = app.zoocall.core.files.EncryptedFiles.readAll(platform.fileSystem, recordingPath(callId), key) ?: return@withContext null
        val path = platform.attachmentsDir / "exports" / "recording-$callId.wav"
        try {
            platform.fileSystem.createDirectories(path.parent!!)
            val handle = platform.fileSystem.openReadWrite(path)
            try {
                handle.resize(0)
                var position = WAV_HEADER_BYTES.toLong()
                val bytes = ByteArray(2 * 320)
                platform.mediaEngine.decodeRecording(audio) { pcm ->
                    for (i in pcm.indices) {
                        bytes[2 * i] = pcm[i].toInt().toByte()
                        bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                    }
                    handle.write(position, bytes, 0, 2 * pcm.size)
                    position += 2 * pcm.size
                }
                val header = wavHeader((position - WAV_HEADER_BYTES).toInt(), app.zoocall.media.MediaEngine.RECORDING_SAMPLE_RATE)
                handle.write(0, header, 0, header.size)
            } finally {
                handle.close()
            }
            path
        } catch (e: Exception) {
            logger.warn(TAG, "Recording export failed", e)
            null
        }
    }

    private fun recordingPath(callId: String) = platform.attachmentsDir / "recordings" / "$callId.zca"

    private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray = okio.Buffer().apply {
        writeUtf8("RIFF")
        writeIntLe(36 + dataBytes)
        writeUtf8("WAVE")
        writeUtf8("fmt ")
        writeIntLe(16)
        writeShortLe(1)
        writeShortLe(1)
        writeIntLe(sampleRate)
        writeIntLe(sampleRate * 2)
        writeShortLe(2)
        writeShortLe(16)
        writeUtf8("data")
        writeIntLe(dataBytes)
    }.readByteArray()

    private fun hello() = with(_settings.value) {
        Hello(
            app_version = platform.appVersion,
            capabilities = Capabilities.DEFAULT,
            presence = (if (activeCallIsLive()) Presence.Busy else presence).toProto(),
            // The name travels only inside the authenticated channel, so it's shared in every visibility mode.
            display_name = displayName,
            role = role,
            device_class = platform.deviceClass.toProto(),
            status_text = statusText,
            linked_devices = linkedDeviceHexes(contactsCache.value.values),
        )
    }

    private fun linkedDeviceHexes(contacts: Collection<Contact>): List<String> =
        contacts.filter { it.linked && !it.blocked }.map { it.fingerprint.hex }.sorted().take(MAX_LINKED_DEVICES)

    private fun activeCallIsLive() = ::callManager.isInitialized && callManager.isInCall

    private suspend fun decideIncoming(caller: Fingerprint): IncomingDecision {
        val contact = contactsCache.value[caller]
        val s = _settings.value
        return when {
            contact?.blocked == true -> IncomingDecision.NotAllowed
            s.allowCallsFrom == AllowCallsFrom.Contacts && contact == null -> IncomingDecision.NotAllowed
            s.allowCallsFrom == AllowCallsFrom.Favorites && contact?.favorite != true -> IncomingDecision.NotAllowed
            s.presence == Presence.DoNotDisturb && contact?.favorite != true -> IncomingDecision.DoNotDisturb
            else -> IncomingDecision.Ring
        }
    }

    /** Do Not Disturb keeps knocks and announcements silent, except from favorites (like calls). */
    private fun isQuietFor(fp: Fingerprint): Boolean =
        _settings.value.presence == Presence.DoNotDisturb && contactsCache.value[fp]?.favorite != true

    /** Push-to-talk connects without ringing, so it's only for contacts the user opted in (docs/01 §4). */
    private suspend fun decidePushToTalk(caller: Fingerprint): IncomingDecision {
        val contact = contactsCache.value[caller]
        return when {
            contact == null || contact.blocked || !contact.allowPushToTalk -> IncomingDecision.NotAllowed
            _settings.value.presence == Presence.DoNotDisturb && !contact.favorite -> IncomingDecision.DoNotDisturb
            else -> IncomingDecision.Ring
        }
    }

    /**
     * A desk intercom line opens a live microphone without ringing (edge case H3), so it needs the
     * user to turn on desk intercom and allow the contact, and Do Not Disturb always refuses it.
     */
    private suspend fun decideIntercom(caller: Fingerprint): IncomingDecision {
        val contact = contactsCache.value[caller]
        val s = _settings.value
        return when {
            !s.deskIntercom || contact == null || contact.blocked || !contact.allowIntercom -> IncomingDecision.NotAllowed
            s.presence == Presence.DoNotDisturb -> IncomingDecision.DoNotDisturb
            else -> IncomingDecision.Ring
        }
    }

    // -- People -----------------------------------------------------------------------------------

    private fun buildPeople(
        contacts: List<Contact>,
        services: Map<String, DiscoveredService>,
        peers: Map<Fingerprint, ConnectedPeer>,
    ): List<Person> {
        val result = ArrayList<Person>()
        val matchedServices = HashSet<String>()
        val verifiedNames = contacts.filter { it.verified }.groupBy { it.displayName.lowercase() }

        fun serviceFor(fp: Fingerprint) = services.values.firstOrNull { contactShortId(it) == fp.shortId }
            ?.also { matchedServices += it.instanceName }

        for (contact in contacts) {
            val peer = peers[contact.fingerprint]
            val service = serviceFor(contact.fingerprint)
            result += Person(
                id = contact.fingerprint.hex,
                fingerprint = contact.fingerprint,
                displayName = contact.displayName,
                role = contact.role,
                deviceClass = peer?.hello?.device_class?.toModel() ?: service?.txt?.deviceClass ?: DeviceClass.Phone,
                presence = peer?.hello?.presence?.toModel() ?: Presence.Available,
                online = peer != null || service != null,
                isContact = true,
                verified = contact.verified,
                favorite = contact.favorite,
                blocked = contact.blocked,
                address = peer?.address ?: service?.addresses?.firstOrNull() ?: contact.lastAddress,
                lastSeenMs = if (peer != null) now() else contact.lastSeenMs,
                statusText = peer?.hello?.status_text?.sanitizedStatus().orEmpty(),
                allowPushToTalk = contact.allowPushToTalk,
                linkedDevice = contact.linked,
                linkConfirmed = contact.linked && peer?.hello?.linked_devices?.contains(identity.fingerprint.hex) == true,
                allowIntercom = contact.allowIntercom,
            )
        }
        val contactFps = contacts.map { it.fingerprint }.toSet()
        for ((fp, peer) in peers) {
            if (fp in contactFps) continue
            val service = serviceFor(fp)
            val name = peer.hello.display_name.sanitizedName().ifEmpty { service?.txt?.displayName ?: "Zoocall user" }
            result += Person(
                id = fp.hex,
                fingerprint = fp,
                displayName = name,
                role = peer.hello.role.take(64),
                deviceClass = peer.hello.device_class.toModel(),
                presence = peer.hello.presence.toModel(),
                online = true,
                isContact = false,
                verified = false,
                favorite = false,
                blocked = false,
                address = peer.address ?: service?.addresses?.firstOrNull(),
                lastSeenMs = now(),
                nameMatchesOtherVerified = verifiedNames.containsKey(name.lowercase()),
                statusText = peer.hello.status_text.sanitizedStatus(),
            )
        }
        for (service in services.values) {
            if (service.instanceName in matchedServices) continue
            // Someone else's private advertisement: they chose not to be seen by non-contacts.
            if (service.txt.fingerprintShortId == null && service.txt.tags.isNotEmpty()) continue
            if (service.txt.fingerprintShortId == identity.fingerprint.shortId) continue
            val name = service.txt.displayName ?: "Zoocall user"
            result += Person(
                id = "svc:${service.instanceName}",
                fingerprint = null,
                displayName = name,
                role = service.txt.role.orEmpty(),
                deviceClass = service.txt.deviceClass,
                presence = Presence.Available,
                online = true,
                isContact = false,
                verified = false,
                favorite = false,
                blocked = false,
                address = service.addresses.firstOrNull(),
                lastSeenMs = now(),
                nameMatchesOtherVerified = verifiedNames.containsKey(name.lowercase()),
            )
        }
        return result.sortedWith(
            compareByDescending<Person> { it.favorite }
                .thenByDescending { it.online }
                .thenByDescending { it.isContact }
                .thenBy { it.displayName.lowercase() },
        )
    }

    fun person(id: String): Person? = people.value.firstOrNull { it.id == id }

    /** Returns the person's fingerprint, connecting first when we only know an address. */
    suspend fun resolve(personId: String): Fingerprint? {
        val person = person(personId)
        person?.fingerprint?.let { fp ->
            if (transport.isConnected(fp)) return fp
            person.address?.let { dial(listOf(it)) }
            return fp
        }
        if (personId.startsWith("svc:")) {
            val service = _services.value[personId.removePrefix("svc:")] ?: return null
            return dial(service.addresses)?.fingerprint
        }
        return runCatching { Fingerprint.fromHex(personId) }.getOrNull()
    }

    /** Whether the connected peer supports a protocol feature (protocol/capabilities.md). */
    fun peerSupports(fp: Fingerprint, capability: String): Boolean =
        transport.peers.value[fp]?.hello?.capabilities?.contains(capability) == true

    // -- Knock ------------------------------------------------------------------------------------

    /** Sends a live "Are you free?" nudge. Knocks are never queued for later. */
    suspend fun knock(personId: String, text: String = ""): KnockResult {
        if (person(personId)?.blocked == true) return KnockResult.Blocked
        val fp = resolve(personId) ?: return KnockResult.Unreachable
        if (contactsCache.value[fp]?.blocked == true) return KnockResult.Blocked
        if (!transport.isConnected(fp)) return KnockResult.Unreachable
        if (!peerSupports(fp, app.zoocall.core.protocol.Capabilities.KNOCK_V1)) return KnockResult.NotSupported
        return if (knockManager.knock(fp, text) != null) KnockResult.Sent else KnockResult.Unreachable
    }

    suspend fun replyToKnock(peer: Fingerprint, knockId: String, reply: KnockReply): Boolean = knockManager.reply(peer, knockId, reply)

    // -- Calls ------------------------------------------------------------------------------------

    /** Opens a push-to-talk session: audio only, connects without ringing if the person allows it. */
    suspend fun startPushToTalk(personId: String): CallStartResult = startCall(personId, CallKind.Audio, pushToTalk = true)

    /** Push-to-talk: transmit while true. False when the other person is talking. */
    suspend fun setTalking(talking: Boolean): Boolean = callManager.setTalking(talking)

    suspend fun setAllowPushToTalk(fp: Fingerprint, allow: Boolean) = store.contacts.setAllowPushToTalk(fp, allow)

    // -- Group calls ------------------------------------------------------------------------------

    // -- Screen sharing ---------------------------------------------------------------------------

    fun canShareScreen(): Boolean = started.value && callManager.canShareScreen()

    suspend fun startScreenShare(source: app.zoocall.media.ScreenSource): Boolean = callManager.startScreenShare(source)

    suspend fun stopScreenShare() = callManager.stopScreenShare()

    /** Whether "Add people" is available: we host a connected call with room left. */
    fun canAddToCall(): Boolean = started.value && callManager.canAddParticipants()

    /** Invites someone into the current call, making it a group call (docs/02 §7). */
    /** Who the current call is being transferred to, if anyone. */
    val callTransferTarget: Flow<Fingerprint?> = runningFlow.flatMapLatest { if (it) callManager.transferTarget else flowOf(null) }

    fun canTransferCall(): Boolean = started.value && callManager.canTransfer()

    /** Hands the current 1:1 call over to [personId]: we leave once they've joined. */
    suspend fun transferCall(personId: String): app.zoocall.core.call.AddParticipantResult {
        val fp = resolve(personId) ?: return app.zoocall.core.call.AddParticipantResult.Unreachable
        return callManager.transferTo(fp)
    }

    suspend fun addToCall(personId: String): app.zoocall.core.call.AddParticipantResult {
        if (person(personId)?.blocked == true) return app.zoocall.core.call.AddParticipantResult.Unreachable
        val fp = resolve(personId) ?: return app.zoocall.core.call.AddParticipantResult.Unreachable
        return callManager.addParticipant(fp)
    }

    /** Opens a desk intercom line: audio only, connects without ringing if they allowed us. */
    suspend fun startIntercom(personId: String): CallStartResult = startCall(personId, CallKind.Audio, intercom = true)

    /** Whether [fp]'s app offers desk intercom (call.intercom). */
    fun supportsIntercom(fp: Fingerprint): Boolean = peerSupports(fp, Capabilities.CALL_INTERCOM)

    suspend fun setAllowIntercom(fp: Fingerprint, allow: Boolean) = store.contacts.setAllowIntercom(fp, allow)

    suspend fun startCall(personId: String, kind: CallKind, pushToTalk: Boolean = false, intercom: Boolean = false): CallStartResult {
        val ringingFromThem = activeCall.value?.state?.let { it.phase == CallPhase.IncomingRinging && it.peer.hex == personId } == true
        if (callManager.isInCall && !(ringingFromThem && !pushToTalk)) return CallStartResult.AlreadyInCall
        val person = person(personId)
        if (person?.blocked == true) return CallStartResult.Blocked
        val expected = person?.fingerprint ?: runCatching { Fingerprint.fromHex(personId) }.getOrNull()
        val fp = if (expected != null && transport.isConnected(expected)) {
            expected
        } else {
            val addresses = buildList {
                person?.address?.let(::add)
                if (personId.startsWith("svc:")) _services.value[personId.removePrefix("svc:")]?.addresses?.let(::addAll)
                expected?.let { contactsCache.value[it]?.lastAddress }?.let(::add)
            }.distinct()
            val peer = dial(addresses) ?: return CallStartResult.Unreachable
            if (expected != null && peer.fingerprint != expected) return CallStartResult.KeyMismatch
            peer.fingerprint
        }
        if (pushToTalk && !peerSupports(fp, Capabilities.PTT_V1)) return CallStartResult.NotSupported
        if (intercom && !peerSupports(fp, Capabilities.CALL_INTERCOM)) return CallStartResult.NotSupported
        val alsoRing = if (pushToTalk || intercom) emptyList() else linkedDevicesOf(fp)
        callManager.startCall(fp, kind, pushToTalk, alsoRing = alsoRing, intercom = intercom) ?: return CallStartResult.AlreadyInCall
        return CallStartResult.Started
    }

    /**
     * [fp]'s other devices we're connected to, when both sides list each other in Hello (device.link).
     * Each claim arrives over that device's own authenticated channel, so one device can't claim another alone.
     */
    private fun linkedDevicesOf(fp: Fingerprint): List<Fingerprint> {
        val peers = transport.peers.value
        val claimed = peers[fp]?.hello?.linked_devices?.toSet() ?: return emptyList()
        return peers.values
            .filter { it.fingerprint != fp && it.fingerprint.hex in claimed && fp.hex in it.hello.linked_devices }
            .map { it.fingerprint }
            .filter { device -> device !in blockedCache.value }
    }

    /** Marks a verified contact as one of the user's own devices, so calls to either ring on both. */
    suspend fun setLinkedDevice(fp: Fingerprint, linked: Boolean) = store.contacts.setLinked(fp, linked)

    suspend fun acceptCall(kind: CallKind) = callManager.accept(kind)

    /**
     * Declines the ringing call. A [quickReply] is shown on the caller's screen and also saved
     * as a normal chat message, so it stays in the conversation.
     */
    suspend fun declineCall(quickReply: String? = null) {
        val caller = activeCall.value?.state?.peer
        callManager.decline(quickReply)
        if (caller != null && !quickReply.isNullOrBlank()) sendMessage(caller, quickReply)
    }

    suspend fun hangUp() = callManager.hangUp()

    suspend fun setMicMuted(muted: Boolean) = callManager.setMicMuted(muted)
    suspend fun setCameraOn(on: Boolean) = callManager.setCameraOn(on)
    suspend fun setOnHold(hold: Boolean) = callManager.setOnHold(hold)
    fun switchCamera() = callManager.switchCamera()

    suspend fun requestVideoUpgrade(): Boolean = callManager.requestVideoUpgrade()
    suspend fun cancelVideoUpgrade() = callManager.cancelVideoUpgrade()
    suspend fun respondToVideoUpgrade(accept: Boolean) = callManager.respondToVideoUpgrade(accept)

    suspend fun acceptWaitingCall(kind: CallKind) = callManager.acceptWaiting(kind)
    suspend fun declineWaitingCall() = callManager.declineWaiting()

    // -- Chat -------------------------------------------------------------------------------------

    fun conversation(peer: Fingerprint): Flow<List<ChatMessageRecord>> = store.messages.conversation(peer)

    fun reactions(peer: Fingerprint): Flow<Map<MessageId, List<ReactionRecord>>> = store.messages.reactions(peer)

    suspend fun sendMessage(peer: Fingerprint, text: String, replyTo: MessageId? = null): MessageId? {
        prepareChat(peer)
        return chatManager.send(peer, text, replyTo)
    }

    suspend fun sendVoice(peer: Fingerprint, clip: app.zoocall.core.model.VoiceClip, replyTo: MessageId? = null): MessageId? {
        prepareChat(peer)
        return chatManager.sendVoice(peer, clip, replyTo)
    }

    /** Copies the file into private storage, hashing it, then sends the file message. */
    suspend fun sendFile(peer: Fingerprint, file: PickedFile, replyTo: MessageId? = null): SendFileResult {
        if ((file.size ?: 0) > FileLimits.MAX_SIZE) return SendFileResult.TooLarge
        prepareChat(peer)
        val id = MessageId.generate(now())
        val source = runCatching { withContext(Dispatchers.IO) { file.open() } }.getOrNull() ?: return SendFileResult.Unreadable
        val imported = files.importFile(id, file.name, file.mime, source) ?: return SendFileResult.Unreadable
        chatManager.sendAttachment(peer, id, imported.meta, replyTo, fileKey = imported.fileKey)
        return SendFileResult.Sent
    }

    suspend fun voiceClip(id: MessageId): app.zoocall.core.model.VoiceClip? = store.messages.voiceClip(id)

    /** Our reaction on a message; null removes it. */
    suspend fun react(peer: Fingerprint, id: MessageId, emoji: String?) = chatManager.react(peer, id, emoji)

    suspend fun searchMessages(query: String): List<ChatMessageRecord> = store.messages.search(query)

    /** Deletes one message on this device only (delete-for-me). */
    suspend fun deleteMessage(id: MessageId) {
        if (chatManager.deleteMessage(id)) files.deleteFiles(id)
    }

    /** The conversation's disappearing-message timer in seconds; 0 when off. */
    fun disappearingTimer(peer: Fingerprint): Flow<Long> =
        store.settings.all.map { it[disappearKey(peer)]?.toLongOrNull() ?: DisappearingTimer.OFF }.distinctUntilChanged()

    /** Applies to messages sent from now on, and to the other person's once their app sees one. */
    suspend fun setDisappearingTimer(peer: Fingerprint, seconds: Long) {
        if (seconds <= 0) store.settings.remove(disappearKey(peer)) else store.settings.put(disappearKey(peer), seconds.toString())
    }

    private suspend fun disappearingSeconds(peer: Fingerprint): Long = store.settings.snapshot()[disappearKey(peer)]?.toLongOrNull() ?: 0

    private fun disappearKey(peer: Fingerprint) = "$KEY_DISAPPEAR_PREFIX${peer.hex}"

    private suspend fun deleteExpiredMessagesLoop() {
        while (true) {
            try {
                store.messages.expiredIds(now()).forEach { deleteMessage(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.warn(TAG, "Deleting expired messages failed", e)
            }
            delay(EXPIRY_SWEEP_MS)
        }
    }

    suspend fun downloadFile(id: MessageId) = files.download(id)
    suspend fun pauseDownload(id: MessageId) = files.pauseDownload(id)
    suspend fun pauseUpload(id: MessageId) = files.pauseUpload(id)
    suspend fun resumeUpload(id: MessageId) = files.resumeUpload(id)

    /**
     * A decrypted copy of a finished attachment, for opening in another app or saving (attachments
     * are encrypted at rest). Only on the user's request; never opened automatically.
     */
    suspend fun attachmentForExport(id: MessageId): Path? = files.decryptedCopy(id)

    /** A finished attachment's bytes for an in-app preview; null when missing or larger than [maxBytes]. */
    suspend fun attachmentBytes(id: MessageId, maxBytes: Long): ByteArray? = files.readBytes(id, maxBytes)

    /** Reconnects if needed and keeps the person's name by saving them as an (unverified) contact. */
    private suspend fun prepareChat(peer: Fingerprint) {
        rememberChatPartner(peer)
        if (!transport.isConnected(peer)) {
            (contactsCache.value[peer]?.lastAddress ?: person(peer.hex)?.address)?.let { scope.launch { dial(listOf(it)) } }
        }
    }

    private suspend fun rememberChatPartner(peer: Fingerprint) {
        if (contactsCache.value[peer] == null && transport.isConnected(peer)) addContact(peer, verified = false)
    }

    // -- Group chats (docs/01 §5: small group chats ≤ 32) -------------------------------------------

    fun groupChat(id: String): Flow<app.zoocall.core.store.GroupChat?> = store.groupChats.observe(id)

    fun groupConversation(id: String): Flow<List<ChatMessageRecord>> = store.messages.groupConversation(id)

    fun groupDelivery(id: String): Flow<Map<MessageId, app.zoocall.core.store.GroupDelivery>> = store.messages.groupDelivery(id)

    /**
     * Opens a group chat with the people in a contact group, reusing one with the same name and
     * members. Null when the group is empty or larger than a group chat allows.
     */
    suspend fun startGroupChat(contactGroupId: String): String? {
        val group = store.groups.get(contactGroupId) ?: return null
        val members = (listOf(identity.fingerprint) + group.members).distinct()
        if (members.size < 2 || members.size > MAX_GROUP_CHAT) return null
        store.groupChats.list().firstOrNull { it.name == group.name && it.members.toSet() == members.toSet() }?.let { return it.id }
        val chat = app.zoocall.core.store.GroupChat(MessageId.generate(now()).value, group.name, members, now())
        // Members learn about the group with its first message.
        chatManager.saveGroup(chat)
        return chat.id
    }

    suspend fun sendGroupMessage(groupId: String, text: String, replyTo: MessageId? = null): MessageId? {
        prepareGroup(groupId)
        return chatManager.sendToGroup(groupId, text, replyTo)
    }

    suspend fun sendGroupVoice(groupId: String, clip: app.zoocall.core.model.VoiceClip, replyTo: MessageId? = null): MessageId? {
        prepareGroup(groupId)
        return chatManager.sendVoiceToGroup(groupId, clip, replyTo)
    }

    suspend fun markGroupRead(groupId: String) = chatManager.markGroupRead(groupId)

    /** Leaves a group chat: the others still here are told, and new messages for it are ignored. History stays. */
    suspend fun leaveGroupChat(groupId: String) {
        val chat = store.groupChats.get(groupId) ?: return
        val remaining = chat.members - identity.fingerprint
        chatManager.saveGroup(chat.copy(members = remaining, updatedAtMs = now(), left = true), notify = remaining)
    }

    /** Connects to members who aren't connected yet, so queued messages go out as soon as possible. */
    private suspend fun prepareGroup(groupId: String) {
        val chat = store.groupChats.get(groupId) ?: return
        for (member in chat.members) {
            if (member == identity.fingerprint || member in blockedCache.value || transport.isConnected(member)) continue
            (contactsCache.value[member]?.lastAddress ?: person(member.hex)?.address)?.let { scope.launch { dial(listOf(it)) } }
        }
    }

    suspend fun markConversationRead(peer: Fingerprint) = chatManager.markRead(peer)
    suspend fun onUserTyping(peer: Fingerprint, active: Boolean) = chatManager.onUserTyping(peer, active)

    suspend fun deleteConversation(peer: Fingerprint) {
        store.messages.deleteConversation(peer).forEach { files.deleteFiles(it) }
    }

    /**
     * Messages from someone who isn't a contact arrive as a request (edge case M10).
     * Accepting saves them as an unverified contact.
     */
    suspend fun acceptMessageRequest(peer: Fingerprint) = addContact(peer, verified = false)

    // -- Contacts & verification ------------------------------------------------------------------

    /** Connects if needed and returns the codes both people compare. */
    suspend fun verificationInfo(personId: String): VerificationInfo? {
        val fp = resolve(personId) ?: return null
        val peer = transport.peers.value[fp] ?: return null
        val contact = contactsCache.value[fp]
        return VerificationInfo(
            fingerprint = fp,
            displayName = contact?.displayName ?: peer.hello.display_name.sanitizedName().ifEmpty { "Zoocall user" },
            role = contact?.role ?: peer.hello.role,
            safetyCode = peer.safetyCode,
            safetyNumber = SafetyCodes.safetyNumber(identity.fingerprint, fp),
            isContact = contact != null,
            verified = contact?.verified == true,
        )
    }

    /** Saves a connected peer as a contact. [verified] only after the user confirmed matching codes. */
    suspend fun addContact(fp: Fingerprint, verified: Boolean) {
        val peer = transport.peers.value[fp]
        val existing = contactsCache.value[fp]
        if (existing != null) {
            if (verified && !existing.verified) store.contacts.setVerified(fp, true)
            return
        }
        peer ?: return
        store.contacts.add(
            fp = fp,
            publicKey = peer.publicKey,
            name = peer.hello.display_name.sanitizedName().ifEmpty { "Zoocall user" },
            role = peer.hello.role.take(64),
            verified = verified,
            address = peer.address,
            nowMs = now(),
        )
    }

    suspend fun setFavorite(fp: Fingerprint, favorite: Boolean) = store.contacts.setFavorite(fp, favorite)

    suspend fun setBlocked(fp: Fingerprint, blocked: Boolean) {
        if (contactsCache.value[fp] == null) addContact(fp, verified = false)
        store.contacts.setBlocked(fp, blocked)
        if (blocked) transport.disconnect(fp)
    }

    suspend fun removeContact(fp: Fingerprint) {
        store.contacts.delete(fp)
        store.groups.removeEverywhere(fp)
    }

    // -- Contact groups ---------------------------------------------------------------------------

    /** Local contact groups ("Kitchen", "Security team"). Only this device knows them. */
    val groups: Flow<List<app.zoocall.core.store.ContactGroup>> =
        runningFlow.flatMapLatest { if (it) store.groups.all else flowOf(emptyList()) }

    /** Returns the new group's id, or null when the name is empty. */
    suspend fun createGroup(name: String, members: Collection<Fingerprint>): String? {
        val clean = name.cleanGroupName() ?: return null
        return store.groups.create(clean, members.distinct(), now())
    }

    suspend fun renameGroup(id: String, name: String) {
        name.cleanGroupName()?.let { store.groups.rename(id, it) }
    }

    suspend fun setGroupMembers(id: String, members: Collection<Fingerprint>) = store.groups.setMembers(id, members.distinct())

    suspend fun deleteGroup(id: String) = store.groups.delete(id)

    /** Calls everyone reachable in a group: whoever answers the connection first, then the others are added. */
    suspend fun callGroup(groupId: String, kind: CallKind): CallStartResult = startGroupSession(groupId) { startCall(it.hex, kind) }

    /** Opens a push-to-talk channel with a group; each person joins only if they allow push-to-talk from us. */
    suspend fun pushToTalkGroup(groupId: String): CallStartResult = startGroupSession(groupId) { startPushToTalk(it.hex) }

    private suspend fun startGroupSession(groupId: String, start: suspend (Fingerprint) -> CallStartResult): CallStartResult {
        val group = store.groups.get(groupId) ?: return CallStartResult.Unreachable
        val reachable = group.members.filter { fp -> contactsCache.value[fp]?.blocked != true && person(fp.hex)?.online == true }
        var result = CallStartResult.Unreachable
        for ((index, first) in reachable.withIndex()) {
            result = start(first)
            if (result == CallStartResult.AlreadyInCall) return result
            if (result == CallStartResult.Started) {
                val callId = activeCall.value?.state?.callId
                val others = reachable.drop(index + 1)
                if (callId != null && others.isNotEmpty()) scope.launch { inviteWhenConnected(callId, others) }
                return result
            }
        }
        return result
    }

    private suspend fun inviteWhenConnected(callId: app.zoocall.core.model.CallId, others: List<Fingerprint>) {
        val call = kotlinx.coroutines.withTimeoutOrNull(GROUP_CONNECT_WAIT_MS) {
            activeCall.first { it == null || it.state.callId != callId || it.state.phase == CallPhase.Connected || !it.state.isActive }
        }
        if (call == null || call.state.callId != callId || call.state.phase != CallPhase.Connected) return
        for (fp in others) {
            if (resolve(fp.hex) != null) callManager.addParticipant(fp)
        }
    }

    /** Sends a text announcement to everyone in a group; each person is alerted once. Returns how many it went to. */
    suspend fun sendAnnouncement(groupId: String, text: String): Int {
        if (text.isBlank()) return 0
        val recipients = announcementRecipients(groupId)
        recipients.forEach { fp ->
            prepareChat(fp)
            chatManager.send(fp, text, announcement = true)
        }
        return recipients.size
    }

    suspend fun sendVoiceAnnouncement(groupId: String, clip: app.zoocall.core.model.VoiceClip): Int {
        val recipients = announcementRecipients(groupId)
        recipients.forEach { fp ->
            prepareChat(fp)
            chatManager.sendVoice(fp, clip, announcement = true)
        }
        return recipients.size
    }

    private suspend fun announcementRecipients(groupId: String): List<Fingerprint> =
        store.groups.get(groupId)?.members?.filter { contactsCache.value[it]?.blocked != true }.orEmpty()

    private fun String.cleanGroupName(): String? = filterNot { it.isISOControl() }.trim().take(MAX_GROUP_NAME).ifEmpty { null }

    /** Adds by manual `ip[:port]` (for networks that block discovery). */
    suspend fun connectToAddress(input: String): AddResult {
        val address = PeerAddress.parse(input) ?: return AddResult.InvalidCode
        val peer = transport.connect(address).getOrNull() ?: return AddResult.Unreachable
        return AddResult.Added(peer.fingerprint, peer.hello.display_name.sanitizedName())
    }

    /** Adds from a scanned QR / deep link. The key in the code must match: that makes the contact verified. */
    suspend fun addFromCode(uri: String): AddResult {
        val code = ContactCode.parse(uri) ?: return AddResult.InvalidCode
        if (Sodium.constantTimeEquals(code.publicKey, identity.publicKey)) return AddResult.Self
        val expected = app.zoocall.core.crypto.Fingerprints.of(code.publicKey)
        val peer = if (transport.isConnected(expected)) {
            transport.peers.value[expected]
        } else {
            val discovered = _services.value.values.firstOrNull { it.txt.fingerprintShortId == expected.shortId }?.addresses.orEmpty()
            dial((code.addresses + discovered).distinct())
        } ?: return AddResult.Unreachable
        if (!Sodium.constantTimeEquals(peer.publicKey, code.publicKey)) return AddResult.KeyMismatch
        addContact(peer.fingerprint, verified = true)
        return AddResult.Added(peer.fingerprint, peer.hello.display_name.sanitizedName())
    }

    fun myContactCode(): ContactCode? {
        val port = transport.listenPort.value ?: return null
        val addresses = platform.network.state.value.localAddresses.take(3).map { PeerAddress(it, port) }
        return ContactCode(identity.publicKey, addresses, _settings.value.displayName)
    }

    // -- Recents ----------------------------------------------------------------------------------

    suspend fun markRecentsSeen() = store.calls.markAllSeen()
    suspend fun deleteRecent(callId: String) {
        store.calls.delete(callId)
        withContext(Dispatchers.IO) { runCatching { platform.fileSystem.delete(recordingPath(callId), mustExist = false) } }
    }

    suspend fun clearRecents() {
        store.calls.clear()
        withContext(Dispatchers.IO) { runCatching { platform.fileSystem.deleteRecursively(platform.attachmentsDir / "recordings") } }
    }

    // -- Settings ---------------------------------------------------------------------------------

    suspend fun updateProfile(displayName: String, role: String) {
        val name = displayName.sanitizedName()
        require(name.isNotEmpty()) { "Name is required" }
        store.settings.put(KEY_NAME, name)
        store.settings.put(KEY_ROLE, role.sanitizedName())
        _settings.update { it.copy(displayName = name, role = role.sanitizedName()) }
        if (started.value) transport.broadcastHello()
    }

    suspend fun setPresence(presence: Presence) = updateSetting(KEY_PRESENCE, presence.name) { it.copy(presence = presence) }
    suspend fun setStatusText(text: String) {
        val value = text.sanitizedStatus()
        updateSetting(KEY_STATUS_TEXT, value) { it.copy(statusText = value) }
    }
    suspend fun setVisibility(visibility: Visibility) = updateSetting(KEY_VISIBILITY, visibility.name) { it.copy(visibility = visibility) }
    suspend fun setAllowCallsFrom(value: AllowCallsFrom) = updateSetting(KEY_ALLOW_CALLS, value.name) { it.copy(allowCallsFrom = value) }
    suspend fun setTheme(theme: ThemeMode) = updateSetting(KEY_THEME, theme.name) { it.copy(theme = theme) }
    suspend fun setDynamicColor(enabled: Boolean) = updateSetting(KEY_DYNAMIC_COLOR, enabled.toString()) { it.copy(dynamicColor = enabled) }
    suspend fun setReadReceipts(enabled: Boolean) = updateSetting(KEY_READ_RECEIPTS, enabled.toString()) { it.copy(readReceipts = enabled) }

    suspend fun setRingtone(id: String) = updateLocalSetting(KEY_RINGTONE, id.take(512)) { it.copy(ringtone = id.take(512)) }
    suspend fun setVibrateOnRing(enabled: Boolean) = updateLocalSetting(KEY_VIBRATE, enabled.toString()) { it.copy(vibrateOnRing = enabled) }
    suspend fun setCallSounds(enabled: Boolean) = updateLocalSetting(KEY_CALL_SOUNDS, enabled.toString()) { it.copy(callSounds = enabled) }
    suspend fun setFlashAlert(enabled: Boolean) = updateLocalSetting(KEY_FLASH_ALERT, enabled.toString()) { it.copy(flashAlert = enabled) }
    suspend fun setGlobalMuteHotkey(enabled: Boolean) = updateLocalSetting(KEY_GLOBAL_MUTE, enabled.toString()) { it.copy(globalMuteHotkey = enabled) }

    // -- Backup (encrypted export / import, docs/01 §9) ---------------------------------------------

    /**
     * Writes a passphrase-encrypted backup of the profile, contacts, groups and chats and returns its
     * path for the user to save. It never includes this device's identity key (edge case D2),
     * attachment files or disappearing messages.
     */
    suspend fun exportBackup(passphrase: String): Path? = withContext(Dispatchers.IO) {
        try {
            val s = _settings.value
            val backup = store.buildBackup(identity.fingerprint, s.displayName, s.role, now())
            // The exports folder is cleared on every start, and the file is encrypted anyway.
            val path = platform.attachmentsDir / "exports" / BACKUP_FILE
            writeBackupFile(platform.fileSystem, path, backup, passphrase)
            path
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Backup export failed", e)
            null
        }
    }

    /** Restores a backup into this device. Before onboarding, it also brings back the name and role. */
    suspend fun restoreBackup(file: PickedFile, passphrase: String): RestoreResult = withContext(Dispatchers.IO) {
        val backup = try {
            readBackupFile(file.open(), passphrase)
        } catch (e: BackupFormatException) {
            return@withContext e.result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.warn(TAG, "Backup unreadable", e)
            return@withContext RestoreResult.Unreadable
        }
        val result = store.applyBackup(backup, identity.fingerprint, now())
        val name = backup.display_name.sanitizedName()
        if (!_settings.value.isOnboarded && name.isNotEmpty()) completeOnboarding(name, backup.role.take(64))
        result
    }

    /** App lock with the device's own lock (Android); turning it off also forgets any passcode. */
    suspend fun setAppLock(enabled: Boolean) {
        if (enabled) updateLocalSetting(KEY_APP_LOCK, "true") { it.copy(appLock = true) } else disableAppLock()
    }

    /** App lock with a passcode (desktop). Only its Argon2id hash is stored. */
    suspend fun enableAppLockPasscode(passcode: String) {
        val hash = withContext(Dispatchers.Default) { Sodium.passwordHash(passcode) }
        updateLocalSetting(KEY_APP_LOCK_HASH, hash) { it.copy(appLockHasPasscode = true) }
        setAppLock(true)
    }

    suspend fun disableAppLock() {
        updateLocalSetting(KEY_APP_LOCK, "false") { it.copy(appLock = false, appLockHasPasscode = false) }
        store.settings.remove(KEY_APP_LOCK_HASH)
    }

    suspend fun verifyAppLockPasscode(passcode: String): Boolean {
        val hash = store.settings.snapshot()[KEY_APP_LOCK_HASH] ?: return false
        return withContext(Dispatchers.Default) { Sodium.verifyPasswordHash(hash, passcode) }
    }

    suspend fun setDeskIntercom(enabled: Boolean) = updateLocalSetting(KEY_DESK_INTERCOM, enabled.toString()) { it.copy(deskIntercom = enabled) }

    suspend fun setStrongNoiseSuppression(enabled: Boolean) {
        updateLocalSetting(KEY_STRONG_NS, enabled.toString()) { it.copy(strongNoiseSuppression = enabled) }
        platform.mediaEngine.setStrongNoiseSuppression(enabled)
    }

    private suspend fun updateSetting(key: String, value: String, change: (AppSettings) -> AppSettings) {
        updateLocalSetting(key, value, change)
        if (started.value) transport.broadcastHello()
    }

    /** For settings peers never see. */
    private suspend fun updateLocalSetting(key: String, value: String, change: (AppSettings) -> AppSettings) {
        store.settings.put(key, value)
        _settings.update(change)
    }

    private fun Map<String, String>.toSettings() = AppSettings(
        displayName = this[KEY_NAME].orEmpty(),
        role = this[KEY_ROLE].orEmpty(),
        presence = enumOr(this[KEY_PRESENCE], Presence.Available),
        visibility = enumOr(this[KEY_VISIBILITY], Visibility.Everyone),
        allowCallsFrom = enumOr(this[KEY_ALLOW_CALLS], AllowCallsFrom.Everyone),
        theme = enumOr(this[KEY_THEME], ThemeMode.System),
        dynamicColor = this[KEY_DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
        readReceipts = this[KEY_READ_RECEIPTS]?.toBooleanStrictOrNull() ?: true,
        statusText = this[KEY_STATUS_TEXT].orEmpty(),
        ringtone = this[KEY_RINGTONE].orEmpty(),
        vibrateOnRing = this[KEY_VIBRATE]?.toBooleanStrictOrNull() ?: true,
        callSounds = this[KEY_CALL_SOUNDS]?.toBooleanStrictOrNull() ?: true,
        flashAlert = this[KEY_FLASH_ALERT]?.toBooleanStrictOrNull() ?: false,
        globalMuteHotkey = this[KEY_GLOBAL_MUTE]?.toBooleanStrictOrNull() ?: false,
        strongNoiseSuppression = this[KEY_STRONG_NS]?.toBooleanStrictOrNull() ?: false,
        deskIntercom = this[KEY_DESK_INTERCOM]?.toBooleanStrictOrNull() ?: false,
        appLock = this[KEY_APP_LOCK]?.toBooleanStrictOrNull() ?: false,
        appLockHasPasscode = this[KEY_APP_LOCK_HASH] != null,
    )

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    fun safetyNumberWith(fp: Fingerprint): String = SafetyCodes.safetyNumber(identity.fingerprint, fp)

    fun shutdown() {
        platform.discovery.stopAdvertising()
        if (started.value) transport.stop()
    }

    private companion object {
        const val TAG = "Core"
        const val RECONNECT_INTERVAL_MS = 20_000L
        const val GROUP_CONNECT_WAIT_MS = 60_000L
        const val MAX_GROUP_NAME = 40
        const val KEY_INSTANCE = "discovery.instance"
        const val KEY_NAME = "profile.name"
        const val KEY_ROLE = "profile.role"
        const val KEY_PRESENCE = "profile.presence"
        const val KEY_STATUS_TEXT = "profile.statusText"
        const val KEY_VISIBILITY = "privacy.visibility"
        const val KEY_ALLOW_CALLS = "privacy.allowCallsFrom"
        const val KEY_THEME = "ui.theme"
        const val KEY_DYNAMIC_COLOR = "ui.dynamicColor"
        const val KEY_READ_RECEIPTS = "chat.readReceipts"
        const val KEY_RINGTONE = "sound.ringtone"
        const val KEY_VIBRATE = "sound.vibrateOnRing"
        const val KEY_CALL_SOUNDS = "sound.callSounds"
        const val KEY_FLASH_ALERT = "sound.flashAlert"
        const val KEY_GLOBAL_MUTE = "desktop.globalMuteHotkey"
        const val KEY_STRONG_NS = "media.strongNoiseSuppression"
        const val MAX_LINKED_DEVICES = 8
        const val KEY_DESK_INTERCOM = "desktop.deskIntercom"
        const val KEY_DISAPPEAR_PREFIX = "chat.disappear."
        const val BACKUP_FILE = "zoocall-backup.zcbackup"
        const val MAX_GROUP_CHAT = 32
        const val WAV_HEADER_BYTES = 44
        const val KEY_APP_LOCK = "security.appLock"
        const val KEY_APP_LOCK_HASH = "security.appLockHash"
        const val EXPIRY_SWEEP_MS = 30_000L
    }
}

/** Names are untrusted: strip control/bidi characters and cap the length. */
internal fun String.sanitizedName(): String =
    filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }.trim().take(64)

/** Custom status text, same rules as names with a longer limit. */
internal fun String.sanitizedStatus(): String =
    filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }.trim().take(80)
