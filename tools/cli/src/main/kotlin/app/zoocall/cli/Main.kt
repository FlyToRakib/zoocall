package app.zoocall.cli

import app.zoocall.core.app.AddResult
import app.zoocall.core.app.CoreStatus
import app.zoocall.core.app.DesktopNetworkMonitor
import app.zoocall.core.app.Platform
import app.zoocall.core.app.ZoocallCore
import app.zoocall.core.call.CallPhase
import app.zoocall.core.discovery.JmdnsDiscovery
import app.zoocall.core.model.CallId
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.PrintLogger
import app.zoocall.core.store.DesktopDatabaseDriverFactory
import app.zoocall.core.store.DesktopKeyStore
import app.zoocall.core.store.DesktopPaths
import app.zoocall.media.MediaEngine
import app.zoocall.media.MediaSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * Headless Zoocall peer for testing discovery, verification and chat against the apps.
 *
 * ```
 * gradlew :tools:cli:run --args="--name TestPeer --profile peer1"
 * ```
 * It has no media engine: incoming calls are declined.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val options = args.toList().windowed(2, 2, partialWindows = true).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val name = options["name"] ?: "Zoocall CLI"
    val dir = DesktopPaths.dataDir(options["profile"] ?: "cli")
    val logger = PrintLogger(verbose = options.containsKey("verbose"))

    val core = ZoocallCore(
        Platform(
            keyStore = DesktopKeyStore(dir),
            databaseFactory = DesktopDatabaseDriverFactory(dir),
            discovery = JmdnsDiscovery(logger),
            mediaEngine = NoMediaEngine,
            network = DesktopNetworkMonitor(),
            deviceClass = DeviceClass.Desktop,
            appVersion = "cli-0.1.0",
            logger = logger,
            fileSystem = okio.FileSystem.SYSTEM,
            attachmentsDir = okio.Path.Companion.run { java.io.File(dir, "attachments").absolutePath.toPath() },
        ),
    )
    core.initialize()
    when (val status = core.status.value) {
        is CoreStatus.Failed -> {
            println("Failed to start: ${status.message}")
            exitProcess(1)
        }
        CoreStatus.NeedsOnboarding -> core.completeOnboarding(name, "CLI test peer")
        else -> Unit
    }
    core.status.first { it == CoreStatus.Running }
    println("Zoocall CLI running as \"${core.settings.value.displayName}\" (tag ${core.localFingerprint.tag}), data: $dir")
    println("Addresses: ${core.network.value.localAddresses.joinToString()} port ${core.listenPort.first { it != null }}")
    printHelp()

    launch {
        core.people.map { list -> list.map { "${it.displayName}${if (it.online) "" else " (offline)"}" } }
            .distinctUntilChanged()
            .collect { println("People: ${it.ifEmpty { listOf("nobody yet") }.joinToString()}") }
    }
    launch {
        core.incomingMessages.collect { m ->
            val from = core.person(m.from.hex)?.displayName ?: m.from.tag
            if (m.isFile) println("File from $from: ${m.text}. Download with: get ${m.id.value}") else println("Message from $from: ${m.text}")
        }
    }
    launch {
        core.activeCall.collect { call ->
            if (call?.state?.phase == CallPhase.IncomingRinging) {
                println("Incoming ${call.state.kind} call — the CLI has no media, declining.")
                core.declineCall()
            }
        }
    }

    while (true) {
        val line = withContext(Dispatchers.IO) { readlnOrNull() } ?: break
        val parts = line.trim().split(' ', limit = 3)
        val people = core.people.value
        fun person(index: String) = index.toIntOrNull()?.let { people.getOrNull(it - 1) }
        when (parts.firstOrNull()) {
            "people", "ls" -> people.forEachIndexed { i, p ->
                println("${i + 1}. ${p.displayName} [${if (p.online) "online" else "offline"}${if (p.verified) ", verified" else ""}] ${p.address ?: ""}")
            }
            "add" -> println(
                when (val r = core.connectToAddress(parts.getOrElse(1) { "" })) {
                    is AddResult.Added -> "Connected to ${r.name}. Use: verify <n>"
                    else -> "Couldn't add: $r"
                },
            )
            "verify" -> person(parts.getOrElse(1) { "" })?.let { p ->
                val info = core.verificationInfo(p.id)
                if (info == null) {
                    println("Couldn't reach ${p.displayName}")
                } else {
                    println("Safety code with ${info.displayName}: ${info.safetyCode}")
                    print("Do the codes match? [y/N] ")
                    if (withContext(Dispatchers.IO) { readlnOrNull() }?.trim()?.lowercase() == "y") {
                        core.addContact(info.fingerprint, verified = true)
                        println("${info.displayName} is verified.")
                    }
                }
            } ?: println("Unknown person")
            "msg" -> {
                val p = person(parts.getOrElse(1) { "" })
                val text = parts.getOrElse(2) { "" }
                val fp = p?.let { core.resolve(it.id) }
                if (fp == null || text.isBlank()) println("Usage: msg <n> <text>") else core.sendMessage(fp, text).also { println("Queued") }
            }
            "file" -> {
                val p = person(parts.getOrElse(1) { "" })
                val file = java.io.File(parts.getOrElse(2) { "" }.trim())
                val fp = p?.let { core.resolve(it.id) }
                if (fp == null || !file.isFile) {
                    println("Usage: file <n> <path>")
                } else {
                    val picked = app.zoocall.core.app.PickedFile(
                        name = file.name,
                        mime = runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream",
                        size = file.length(),
                        open = { okio.FileSystem.SYSTEM.source(okio.Path.Companion.run { file.absolutePath.toPath() }) },
                    )
                    println("Sending ${file.name}: ${core.sendFile(fp, picked)}")
                }
            }
            "get" -> {
                val id = parts.getOrElse(1) { "" }.trim()
                if (id.length != 26) println("Usage: get <message id>") else {
                    val messageId = app.zoocall.core.model.MessageId(id)
                    core.downloadFile(messageId)
                    launch {
                        var exported = core.attachmentForExport(messageId)
                        while (exported == null) {
                            kotlinx.coroutines.delay(250)
                            exported = core.attachmentForExport(messageId)
                        }
                        println("Downloaded to $exported")
                    }
                }
            }
            "code" -> println(core.myContactCode()?.toUri() ?: "Not on a local network")
            "help" -> printHelp()
            "quit", "exit" -> break
            "", null -> Unit
            else -> println("Unknown command. Type help.")
        }
    }
    core.shutdown()
    exitProcess(0)
}

private fun printHelp() = println(
    """
    Commands:
      people              list people
      add <ip[:port]>     connect by address
      verify <n>          compare safety codes and mark verified
      msg <n> <text>      send a message
      file <n> <path>     send a file
      get <message id>    download a received file
      code                print my contact code (zoocall://add…)
      quit
    """.trimIndent(),
)

private object NoMediaEngine : MediaEngine {
    override val hasCamera = false
    override fun createSession(callId: CallId, kind: CallKind, isOfferer: Boolean): MediaSession =
        throw UnsupportedOperationException("The CLI peer has no media engine")

    override fun createVoiceRecorder(): app.zoocall.media.VoiceRecorder = throw UnsupportedOperationException("No microphone in the CLI")
    override val voicePlayer: app.zoocall.media.VoicePlayer get() = throw UnsupportedOperationException("No speaker in the CLI")
}
