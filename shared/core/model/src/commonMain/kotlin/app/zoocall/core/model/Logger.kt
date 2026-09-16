package app.zoocall.core.model

/**
 * Minimal logging port. Platform shells provide Logcat / rolling-file implementations.
 *
 * Never log message bodies, keys, full SDP or full IP addresses in release builds.
 */
interface Logger {
    fun debug(tag: String, message: () -> String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)

    companion object {
        var current: Logger = NoopLogger
    }
}

object NoopLogger : Logger {
    override fun debug(tag: String, message: () -> String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, error: Throwable?) = Unit
    override fun error(tag: String, message: String, error: Throwable?) = Unit
}

/** Simple stdout logger for tests and the CLI peer. */
class PrintLogger(private val verbose: Boolean = false) : Logger {
    override fun debug(tag: String, message: () -> String) {
        if (verbose) println("D/$tag: ${message()}")
    }
    override fun info(tag: String, message: String) = println("I/$tag: $message")
    override fun warn(tag: String, message: String, error: Throwable?) =
        println("W/$tag: $message${error?.let { " — $it" } ?: ""}")
    override fun error(tag: String, message: String, error: Throwable?) =
        println("E/$tag: $message${error?.let { " — $it" } ?: ""}")
}
