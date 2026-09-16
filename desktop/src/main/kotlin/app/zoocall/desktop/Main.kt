package app.zoocall.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiCalling3
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Tray
import java.awt.Frame
import java.awt.event.WindowStateListener
import java.beans.PropertyChangeListener
import javax.swing.Timer
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import kotlinx.coroutines.delay
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import app.zoocall.core.app.DesktopNetworkMonitor
import app.zoocall.core.app.Platform
import app.zoocall.core.app.ZoocallCore
import app.zoocall.core.app.coreModule
import app.zoocall.core.call.CallPhase
import app.zoocall.core.discovery.JmdnsDiscovery
import app.zoocall.core.model.CallDirection
import app.zoocall.core.model.CallKind
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Logger
import app.zoocall.core.model.PrintLogger
import app.zoocall.core.store.DesktopDatabaseDriverFactory
import app.zoocall.core.store.DesktopKeyStore
import app.zoocall.core.store.DesktopPaths
import app.zoocall.media.DesktopMediaEngine
import app.zoocall.media.DeviceKind
import app.zoocall.ui.LocalCore
import app.zoocall.ui.LocalSnackbar
import app.zoocall.ui.ZoocallApp
import app.zoocall.ui.call.CallScreen
import app.zoocall.ui.platform.LocalPlatformActions
import app.zoocall.ui.platform.NoPlatformActions
import app.zoocall.ui.settings.AppInfo
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import app.zoocall.core.chat.KnockEvent
import app.zoocall.ui.ShellText
import app.zoocall.core.chat.KnockReply
import org.koin.core.context.startKoin
import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/** Desktop OS integrations, shared by the main window and call windows. Set once at startup. */
private lateinit var platformActions: DesktopPlatformActions

fun main(args: Array<String>) {
    val profile = System.getProperty("zoocall.profile")
    val dataDir = DesktopPaths.dataDir(profile)
    // Started at login: stay in the tray until the user opens the window.
    val startHidden = DesktopIntegration.BACKGROUND_ARG in args
    platformActions = DesktopPlatformActions(dataDir)
    Logger.current = PrintLogger(verbose = System.getProperty("zoocall.verbose") != null)
    AppInfo.version = System.getProperty("zoocall.version") ?: "dev"

    if (!acquireSingleInstanceLock(dataDir)) {
        val message = kotlinx.coroutines.runBlocking { ShellText.AlreadyRunning.get() }
        JOptionPane.showMessageDialog(null, message, "Zoocall", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }

    val mediaEngine = DesktopMediaEngine(devicePreferences = File(dataDir, "devices.properties"))
    val platform = Platform(
        keyStore = DesktopKeyStore(dataDir),
        databaseFactory = DesktopDatabaseDriverFactory(dataDir),
        discovery = JmdnsDiscovery(),
        mediaEngine = mediaEngine,
        network = DesktopNetworkMonitor(),
        deviceClass = DeviceClass.Desktop,
        appVersion = AppInfo.version,
        logger = Logger.current,
        fileSystem = okio.FileSystem.SYSTEM,
        attachmentsDir = okio.Path.Companion.run { File(dataDir, "attachments").absolutePath.toPath() },
        diagnostics = DesktopDiagnostics(),
    )
    val core: ZoocallCore = startKoin { modules(coreModule(platform)) }.koin.get()
    // The ringtone plays on the speaker chosen in Settings → Audio & video.
    val ringer = DesktopRinger { mediaEngine.devices.activeName(DeviceKind.Speaker) }
    platformActions.ringtones = object : app.zoocall.ui.platform.RingtoneChooser {
        override val builtIn = DesktopRinger.STYLES
        override fun preview(id: String) = ringer.preview(id)
        override fun stopPreview() = ringer.stopPreview()
    }

    application {
        ZoocallDesktop(core, ringer, profile, startHidden)
    }
}

@Composable
private fun ApplicationScope.ZoocallDesktop(core: ZoocallCore, ringer: DesktopRinger, profile: String?, startHidden: Boolean) {
    val glyph = rememberVectorPainter(Icons.Rounded.WifiCalling3)
    val icon = remember(glyph) { BrandIconPainter(glyph) }
    val nav = rememberNavController()
    var mainVisible by remember { mutableStateOf(!startHidden) }
    val call by core.activeCall.collectAsState()
    val title = if (profile.isNullOrBlank()) "Zoocall" else "Zoocall ($profile)"

    fun quit() {
        ringer.stop()
        core.shutdown()
        exitApplication()
    }

    // Ring while an incoming call waits; ringback while an outgoing call rings.
    LaunchedEffect(call?.state?.phase) {
        when (call?.state?.phase) {
            CallPhase.IncomingRinging -> {
                ringer.startRinging(core.settings.value.ringtone)
                runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().requestUserAttention(true, false) }
            }
            CallPhase.OutgoingRinging -> ringer.startRingback()
            else -> ringer.stop()
        }
    }

    // A second call waiting while in a call: soft beeps (edge case C2).
    val waiting = call?.waiting != null
    LaunchedEffect(waiting) {
        if (waiting && core.settings.value.callSounds) ringer.startCallWaiting() else if (call?.state?.phase != CallPhase.IncomingRinging) ringer.stop()
    }

    // While the window is hidden or minimized, knocks and messages show as system notifications.
    val trayState = androidx.compose.ui.window.rememberTrayState()
    val mainWindowState = rememberWindowState(size = DpSize(1120.dp, 760.dp), position = WindowPosition(Alignment.Center))
    val startupLogged = remember { booleanArrayOf(false) }
    val windowAway by rememberUpdatedState(!mainVisible || mainWindowState.isMinimized)

    // Opt-in global mute shortcut, registered only while a call is live.
    val appScope = rememberCoroutineScope()
    val settings by core.settings.collectAsState()
    val hotkey = remember {
        GlobalMuteHotkey {
            appScope.launch {
                val active = core.activeCall.value?.takeIf { it.state.isActive && !it.state.pushToTalk } ?: return@launch
                core.setMicMuted(!active.micMuted)
                if (core.settings.value.callSounds) ringer.playMuteCue(muted = !active.micMuted)
            }
        }
    }
    val callLive = call?.state?.isActive == true
    LaunchedEffect(settings.globalMuteHotkey, callLive) {
        if (settings.globalMuteHotkey && callLive) {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { hotkey.register() }
            if (!ok && hotkey.isSupported) {
                trayState.sendNotification(
                    androidx.compose.ui.window.Notification("Zoocall", ShellText.HotkeyInUse.get(GlobalMuteHotkey.LABEL)),
                )
            }
        } else {
            hotkey.unregister()
        }
    }
    DisposableEffect(hotkey) { onDispose { hotkey.unregister() } }
    LaunchedEffect(core) {
        launch {
            core.knocks.collect { event ->
                if (event is KnockEvent.Received && !event.quiet && core.settings.value.callSounds) ringer.playKnock()
                if (!windowAway) return@collect
                val name = core.person(event.from.hex)?.displayName ?: ShellText.UnknownPerson.get()
                val (heading, body) = when (event) {
                    is KnockEvent.Received -> "👋 " + ShellText.Knocked.get(name) to event.text.ifBlank { ShellText.KnockDefault.get() }
                    is KnockEvent.Replied -> "Zoocall" to when (event.reply) {
                        KnockReply.CallMe -> ShellText.RepliedCallMe.get(name)
                        KnockReply.TwoMinutes -> ShellText.RepliedTwoMinutes.get(name)
                        KnockReply.Busy -> ShellText.RepliedBusy.get(name)
                    }
                }
                trayState.sendNotification(androidx.compose.ui.window.Notification(heading, body))
            }
        }
        core.incomingMessages.collect { message ->
            // Announcements alert once even while the window is open.
            if (message.isAnnouncement && !message.quiet && core.settings.value.callSounds) ringer.playKnock()
            if (!windowAway && !message.isAnnouncement) return@collect
            val name = core.person(message.from.hex)?.displayName ?: ShellText.UnknownPerson.get()
            val body = when {
                message.isVoice -> ShellText.VoiceMessage.get()
                message.isFile -> "📎 ${message.text}"
                else -> message.text.take(200)
            }
            val group = message.groupName
            val notificationTitle = if (message.isAnnouncement) "📢 $name" else group ?: name
            val notificationBody = if (group != null && !message.isAnnouncement) "$name: $body" else body
            trayState.sendNotification(androidx.compose.ui.window.Notification(notificationTitle, notificationBody))
        }
    }

    // Push-to-talk chirps when either side starts or stops talking (docs/05 §5.6).
    val selfTalking = call?.state?.pushToTalk == true && call?.talking == true
    val otherTalking = call?.state?.pushToTalk == true && call?.remoteTalking == true
    val lastPtt = remember { booleanArrayOf(false, false) }
    LaunchedEffect(selfTalking, otherTalking) {
        if (selfTalking != lastPtt[0]) ringer.playPttChirp(start = selfTalking)
        else if (otherTalking != lastPtt[1]) ringer.playPttChirp(start = otherTalking)
        lastPtt[0] = selfTalking
        lastPtt[1] = otherTalking
    }

    // Recording starts with a chime on every device in the call (docs/04: recording consent).
    val recordingOn = call?.let { it.recording || it.remoteRecording || it.members.any { m -> m.recording } } == true
    LaunchedEffect(recordingOn) { if (recordingOn) ringer.playIntercomChime() }

    // A desk intercom line connects without ringing, so both ends hear a chime when it opens (edge case H3).
    val intercomLive = call?.state?.intercom == true && call?.state?.phase == app.zoocall.core.call.CallPhase.Connected
    LaunchedEffect(intercomLive) { if (intercomLive) ringer.playIntercomChime() }

    Tray(
        state = trayState,
        icon = icon,
        tooltip = title,
        onAction = { mainVisible = true },
        menu = {
            Item(ShellText.TrayOpen.text(), onClick = { mainVisible = true })
            Separator()
            Item(ShellText.TrayQuit.text(), onClick = ::quit)
        },
    )

    // On Windows the Skia layer can keep a stale size/offset when the window is shown (first launch or
    // reopened from the tray): content renders ~30 px too high with a blank strip at the bottom.
    // A 1 px resize once it's on screen forces a correct layout.
    // The first real frame can arrive seconds later on high-DPI screens, so re-layout a few times.
    LaunchedEffect(mainVisible) {
        if (!mainVisible) return@LaunchedEffect
        // Startup budget (docs/08 §5): time from JVM start to the first visible window.
        if (!startupLogged[0]) {
            startupLogged[0] = true
            val sinceLaunch = System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
            Logger.current.info("Startup", "Main window shown $sinceLaunch ms after launch")
        }
        for (wait in listOf(300L, 700L, 1_500L)) {
            delay(wait)
            if (mainWindowState.placement != WindowPlacement.Floating) continue
            val size = mainWindowState.size
            mainWindowState.size = size.copy(width = size.width + 1.dp)
            delay(80)
            mainWindowState.size = size
            Logger.current.debug("Window") { "Layout refresh after show (+${wait} ms)" }
        }
    }

    Window(
        onCloseRequest = { mainVisible = false }, // keep running in the tray so calls still arrive
        visible = mainVisible,
        title = title,
        icon = icon,
        state = mainWindowState,
        onPreviewKeyEvent = { event ->
            // Esc goes back, like the Android back button.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && nav.previousBackStackEntry != null) {
                nav.popBackStack()
                true
            } else {
                false
            }
        },
    ) {
        StaleLayoutGuard()
        CompositionLocalProvider(LocalPlatformActions provides platformActions) {
            ZoocallApp(core = core, showCallOverlay = false, navController = nav, appVisible = !windowAway)
        }
    }

    CallWindowHost(core, icon, title)
}

/**
 * One window per call. It starts as a compact always-on-top window in the corner while ringing
 * and grows into the full call window when answered, instead of closing and reopening. A call
 * that ends without being answered (missed, declined, cancelled) never opens the big window.
 */
@Composable
private fun CallWindowHost(core: ZoocallCore, icon: Painter, title: String) {
    val call by core.activeCall.collectAsState()
    val current = call ?: return
    key(current.state.callId) {
        val answered = remember { booleanArrayOf(false) }
        val phase = current.state.phase
        if (phase != CallPhase.IncomingRinging && phase != CallPhase.Ended) answered[0] = true
        // No early return inside key { }: it generates an invalid "<anonymous>" method on the JVM.
        val unansweredIncomingEnded = phase == CallPhase.Ended && current.state.direction == CallDirection.Incoming && !answered[0]
        if (!unansweredIncomingEnded) CallWindow(core, icon, title, phase)
    }
}

@Composable
private fun CallWindow(core: ZoocallCore, icon: Painter, title: String, phase: CallPhase) {
    // Window size and position are remembered per call (CallWindowHost keys this by call id).
    run {
        val scope = rememberCoroutineScope()
        val ringing = phase == CallPhase.IncomingRinging
        val bounds = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
        val compact = DpSize(380.dp, 600.dp)
        val full = DpSize(960.dp, 680.dp)
        val state = rememberWindowState(
            size = if (ringing) compact else full,
            position = if (ringing) WindowPosition((bounds.x + bounds.width - 400).dp, (bounds.y + bounds.height - 620).dp) else WindowPosition(Alignment.Center),
        )
        LaunchedEffect(ringing) {
            if (!ringing && state.size == compact) {
                state.size = full
                state.position = WindowPosition(Alignment.Center)
            }
        }

        Window(
            onCloseRequest = { scope.launch { if (ringing) core.declineCall() else core.hangUp() } },
            title = if (ringing) ShellText.IncomingCallWindow.text() else ShellText.CallWindow.text(title),
            icon = icon,
            state = state,
            alwaysOnTop = ringing,
            resizable = !ringing,
            onPreviewKeyEvent = { event ->
                val down = event.type == KeyEventType.KeyDown
                val primary = event.isCtrlPressed || event.isMetaPressed
                val active = core.activeCall.value
                when {
                    // Push-to-talk: hold Space to talk.
                    active?.state?.pushToTalk == true && event.key == Key.Spacebar -> {
                        if (down || event.type == KeyEventType.KeyUp) scope.launch { core.setTalking(down) }
                        true
                    }
                    down && ringing && primary && event.key == Key.Enter -> {
                        scope.launch { core.acceptCall(active?.state?.kind ?: CallKind.Audio) }
                        true
                    }
                    down && ringing && event.key == Key.Escape -> {
                        scope.launch { core.declineCall() }
                        true
                    }
                    down && primary && event.isShiftPressed && event.key == Key.M && active != null -> {
                        scope.launch { core.setMicMuted(!active.micMuted) }
                        true
                    }
                    down && primary && event.isShiftPressed && event.key == Key.V && active != null -> {
                        scope.launch { core.setCameraOn(!active.cameraOn) }
                        true
                    }
                    down && primary && event.isShiftPressed && event.key == Key.E -> {
                        scope.launch { core.hangUp() }
                        true
                    }
                    else -> false
                }
            },
        ) {
            StaleLayoutGuard()
            CompositionLocalProvider(
                LocalCore provides core,
                LocalPlatformActions provides platformActions,
                LocalSnackbar provides remember { SnackbarHostState() },
            ) {
                CallScreen()
            }
        }
    }
}

/**
 * Compose Desktop on Windows can keep a stale layer size after the display scale changes, the window
 * moves to a monitor with a different scale, or it's maximized/restored: content renders shifted
 * or clipped at the bottom. A 1 px resize and back forces a correct layout.
 */
@Composable
private fun FrameWindowScope.StaleLayoutGuard() {
    DisposableEffect(window) {
        val frame = window
        fun nudgeOnce(reason: String) {
            if (!frame.isShowing || frame.extendedState != Frame.NORMAL) return
            val size = frame.size
            frame.setSize(size.width + 1, size.height)
            Timer(80) { frame.setSize(size.width, size.height) }.apply { isRepeats = false }.start()
            Logger.current.debug("Window") { "Layout refresh: $reason" }
        }
        // The new scale is applied to the layer asynchronously, so refresh a few times after the change.
        fun nudge(reason: String) {
            for (wait in listOf(200, 700, 1_500)) {
                Timer(wait) { nudgeOnce(reason) }.apply { isRepeats = false }.start()
            }
        }
        val scaleListener = PropertyChangeListener { nudge("display scale changed") }
        val stateListener = WindowStateListener { nudge("window state changed") }
        frame.addPropertyChangeListener("graphicsConfiguration", scaleListener)
        frame.addWindowStateListener(stateListener)
        onDispose {
            frame.removePropertyChangeListener("graphicsConfiguration", scaleListener)
            frame.removeWindowStateListener(stateListener)
        }
    }
}

/** App/tray icon: the phone glyph in white on a Zoocall-teal circle, visible on light and dark taskbars. */
private class BrandIconPainter(private val glyph: Painter) : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF0F9D8A))
        val inset = size.minDimension * 0.22f
        translate(inset, inset) {
            with(glyph) {
                draw(Size(size.width - inset * 2, size.height - inset * 2), colorFilter = ColorFilter.tint(Color.White))
            }
        }
    }
}

/** One instance per data directory; a second launch shows a message instead of fighting for the port. */
private fun acquireSingleInstanceLock(dir: File): Boolean = runCatching {
    val channel = FileChannel.open(File(dir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock = channel.tryLock() ?: return false
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { lock.release(); channel.close() } })
    true
}.getOrDefault(true)
