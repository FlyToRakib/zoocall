package app.zoocall.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opt-in global mute shortcut (Ctrl+Shift+M) on Windows, registered only while a call is live.
 *
 * Uses Win32 `RegisterHotKey`, which delivers just this one key combination: unlike a global
 * keyboard hook it never sees other keystrokes (ADR 0003).
 */
class GlobalMuteHotkey(private val onPressed: () -> Unit) {
    val isSupported: Boolean = DesktopIntegration.isWindows

    @Volatile private var worker: Thread? = null
    @Volatile private var workerId = 0

    /** Returns false when another app already owns the shortcut. */
    @Synchronized
    fun register(): Boolean {
        if (!isSupported) return false
        if (worker != null) return true
        val registered = CompletableFuture<Boolean>()
        // Hotkey messages go to the registering thread's queue, so that thread runs the message loop.
        worker = thread(isDaemon = true, name = "zoocall-hotkey") {
            workerId = Kernel32.INSTANCE.GetCurrentThreadId()
            val ok = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, MOD_CONTROL or MOD_SHIFT or MOD_NOREPEAT, VK_M)
            registered.complete(ok)
            if (!ok) return@thread
            try {
                val msg = WinUser.MSG()
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WM_HOTKEY && msg.wParam.toInt() == HOTKEY_ID) runCatching(onPressed)
                }
            } finally {
                User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID)
            }
        }
        val ok = runCatching { registered.get(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!ok) worker = null
        return ok
    }

    @Synchronized
    fun unregister() {
        worker ?: return
        worker = null
        User32.INSTANCE.PostThreadMessage(workerId, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
    }

    companion object {
        const val LABEL = "Ctrl+Shift+M"
        private const val HOTKEY_ID = 0x5A01
        private const val MOD_CONTROL = 0x0002
        private const val MOD_SHIFT = 0x0004
        private const val MOD_NOREPEAT = 0x4000
        private const val VK_M = 0x4D
        private const val WM_HOTKEY = 0x0312
        private const val WM_QUIT = 0x0012
    }
}
