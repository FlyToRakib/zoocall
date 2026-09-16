package app.zoocall.core.model

/** What a file message describes. [hash] is BLAKE2b-256 of the whole file. */
class AttachmentMeta(
    val name: String,
    val mime: String,
    val size: Long,
    val hash: ByteArray,
) {
    val isImage: Boolean get() = mime.startsWith("image/") && mime.substringAfter('/') in setOf("jpeg", "png", "webp", "gif", "bmp")

    /** Types that can run code when opened (edge case M8). Shown with a warning, never opened automatically. */
    val isRisky: Boolean get() = name.substringAfterLast('.', "").lowercase() in RISKY_EXTENSIONS

    companion object {
        const val HASH_BYTES = 32
        const val MAX_NAME = 128

        private val RISKY_EXTENSIONS = setOf(
            "apk", "aab", "exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar",
            "sh", "command", "app", "dmg", "pkg", "deb", "rpm", "appimage", "lnk", "reg",
        )
        private val WINDOWS_RESERVED = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)

        /** File names are untrusted (edge case M9): no paths, control or bidi characters, or reserved names. */
        fun sanitizeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base
                .filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' || it in "<>:\"|?*" }
                .trim()
                .trimStart('.')
                .trimEnd('.', ' ')
            val safe = if (cleaned.isEmpty() || WINDOWS_RESERVED.matches(cleaned)) "file${if (cleaned.isEmpty()) "" else "_$cleaned"}" else cleaned
            if (safe.length <= MAX_NAME) return safe
            val ext = safe.substringAfterLast('.', "").take(16)
            return if (ext.isEmpty()) safe.take(MAX_NAME) else safe.take(MAX_NAME - ext.length - 1) + "." + ext
        }

        fun sanitizeMime(raw: String): String =
            raw.trim().lowercase().takeIf { it.length <= 100 && Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(it) } ?: "application/octet-stream"
    }
}

enum class TransferState(val dbValue: String) {
    /** Incoming: available to download. */
    Offered("offered"),

    /** Incoming: the user wants it; bytes are (or will be) flowing. */
    Requested("requested"),
    Paused("paused"),

    /** Incoming: downloaded and verified. Outgoing: the local copy is available. */
    Done("done"),
    Failed("failed"),
    ;

    companion object {
        fun from(value: String) = entries.firstOrNull { it.dbValue == value } ?: Failed
    }
}

/** An attachment with its transfer state, as shown in a conversation. */
data class AttachmentInfo(
    val name: String,
    val mime: String,
    val size: Long,
    val state: TransferState,
    val transferred: Long,
) {
    val meta: AttachmentMeta get() = AttachmentMeta(name, mime, size, ByteArray(0))
}
