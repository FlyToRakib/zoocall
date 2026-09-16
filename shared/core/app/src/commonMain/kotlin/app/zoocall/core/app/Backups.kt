package app.zoocall.core.app

import app.zoocall.core.crypto.CryptoException
import app.zoocall.core.crypto.Fingerprints
import app.zoocall.core.crypto.Sodium
import app.zoocall.core.model.Fingerprint
import app.zoocall.core.model.MessageId
import app.zoocall.core.model.MessageKind
import app.zoocall.core.model.VoiceClip
import app.zoocall.core.store.ChatMessageRecord
import app.zoocall.core.store.MessageState
import app.zoocall.core.store.ZoocallStore
import app.zoocall.protocol.v1.Backup
import app.zoocall.protocol.v1.BackupContact
import app.zoocall.protocol.v1.BackupGroup
import app.zoocall.protocol.v1.BackupMessage
import kotlinx.coroutines.flow.first
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use

/** A backup file that can't be restored, with the reason to show. */
internal class BackupFormatException(val result: RestoreResult) : Exception(result.toString())

/**
 * The encrypted `.zcbackup` container (protocol/spec/backup-file.md):
 * `"ZCB1" | salt (16) | opslimit (u32 BE) | memlimit (u32 BE)`, then chunks of up to 64 KiB of
 * plaintext, each ChaCha20-Poly1305 under an Argon2id key, with the header as associated data and
 * the chunk index as nonce. The last chunk's nonce has its top bit set, so a cut-off file fails.
 */
internal object BackupFile {
    private val MAGIC = "ZCB1".encodeToByteArray()
    const val HEADER_BYTES = 4 + Sodium.PASSWORD_SALT_BYTES + 8
    const val CHUNK = 64 * 1024

    /** Argon2id cost for new files: libsodium's "moderate" time with 64 MiB, fine on phones. */
    const val OPS_LIMIT = 3L
    const val MEM_LIMIT = 64 * 1024 * 1024

    // Limits for files we open, so a crafted header can't demand absurd work or memory.
    private const val MAX_OPS = 10L
    private const val MIN_MEM = 8 * 1024
    private const val MAX_MEM = 256 * 1024 * 1024
    const val MAX_PLAINTEXT = 256L * 1024 * 1024

    fun write(payload: ByteArray, passphrase: String, sink: BufferedSink) {
        val salt = Sodium.randomBytes(Sodium.PASSWORD_SALT_BYTES)
        val header = MAGIC + salt + OPS_LIMIT.toInt().toBytes() + MEM_LIMIT.toBytes()
        val key = Sodium.passwordKey(passphrase, salt, OPS_LIMIT, MEM_LIMIT)
        try {
            sink.write(header)
            val chunks = maxOf(1, (payload.size + CHUNK - 1) / CHUNK)
            for (index in 0 until chunks) {
                val plain = payload.copyOfRange(index * CHUNK, minOf(payload.size, (index + 1) * CHUNK))
                sink.write(Sodium.chaChaPolyEncrypt(key, nonce(index.toLong(), last = index == chunks - 1), header, plain))
            }
        } finally {
            Sodium.wipe(key)
        }
    }

    /** Throws [BackupFormatException] when it isn't a backup, the passphrase is wrong or the file is damaged. */
    fun read(source: BufferedSource, passphrase: String): ByteArray {
        val header = source.readUpTo(HEADER_BYTES)
        if (header.size != HEADER_BYTES || !header.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupFormatException(RestoreResult.NotABackup)
        val salt = header.copyOfRange(4, 4 + Sodium.PASSWORD_SALT_BYTES)
        val ops = header.readUInt(4 + Sodium.PASSWORD_SALT_BYTES)
        val mem = header.readUInt(8 + Sodium.PASSWORD_SALT_BYTES)
        if (ops !in 1..MAX_OPS || mem !in MIN_MEM..MAX_MEM) throw BackupFormatException(RestoreResult.NotABackup)

        val key = Sodium.passwordKey(passphrase, salt, ops, mem.toInt())
        try {
            val out = Buffer()
            var index = 0L
            while (true) {
                val sealed = source.readUpTo(CHUNK + Sodium.CHACHAPOLY_TAG_BYTES)
                if (sealed.isEmpty()) throw BackupFormatException(RestoreResult.Damaged)
                val last = source.exhausted()
                val plain = try {
                    Sodium.chaChaPolyDecrypt(key, nonce(index, last), header, sealed)
                } catch (e: CryptoException) {
                    // The first chunk only opens with the right key; after that, a failure means damage.
                    throw BackupFormatException(if (index == 0L) RestoreResult.WrongPassphrase else RestoreResult.Damaged)
                }
                out.write(plain)
                if (out.size > MAX_PLAINTEXT) throw BackupFormatException(RestoreResult.Damaged)
                if (last) return out.readByteArray()
                index++
            }
        } finally {
            Sodium.wipe(key)
        }
    }

    private fun nonce(index: Long, last: Boolean) = ByteArray(Sodium.CHACHAPOLY_NONCE_BYTES).also { n ->
        for (i in 0 until 8) n[n.size - 1 - i] = (index ushr (8 * i)).toByte()
        if (last) n[0] = 0x80.toByte()
    }

    private fun Int.toBytes() = byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte())

    private fun ByteArray.readUInt(at: Int): Long =
        ((this[at].toLong() and 0xff) shl 24) or ((this[at + 1].toLong() and 0xff) shl 16) or
            ((this[at + 2].toLong() and 0xff) shl 8) or (this[at + 3].toLong() and 0xff)

    private fun BufferedSource.readUpTo(count: Int): ByteArray {
        val buffer = Buffer()
        while (buffer.size < count) {
            if (read(buffer, count - buffer.size) == -1L) break
        }
        return buffer.readByteArray()
    }
}

internal fun writeBackupFile(fileSystem: FileSystem, path: Path, backup: Backup, passphrase: String) {
    val payload = Backup.ADAPTER.encode(backup)
    try {
        path.parent?.let(fileSystem::createDirectories)
        fileSystem.sink(path).buffer().use { BackupFile.write(payload, passphrase, it) }
    } finally {
        payload.fill(0)
    }
}

internal fun readBackupFile(source: Source, passphrase: String): Backup {
    val payload = source.buffer().use { BackupFile.read(it, passphrase) }
    return try {
        Backup.ADAPTER.decode(payload)
    } catch (e: Exception) {
        throw BackupFormatException(RestoreResult.Damaged)
    } finally {
        payload.fill(0)
    }
}

/** Profile, contacts, groups, and text and voice messages; never this device's key, files or disappearing messages. */
internal suspend fun ZoocallStore.buildBackup(self: Fingerprint, displayName: String, role: String, nowMs: Long): Backup {
    val contactList = contacts.all.first().filter { it.fingerprint != self }.mapNotNull { contact ->
        val key = contacts.publicKey(contact.fingerprint) ?: return@mapNotNull null
        BackupContact(
            fingerprint = contact.fingerprint.hex,
            public_key = key.toByteString(),
            display_name = contact.displayName,
            role = contact.role,
            verified = contact.verified,
            favorite = contact.favorite,
            blocked = contact.blocked,
            allow_push_to_talk = contact.allowPushToTalk,
            allow_intercom = contact.allowIntercom,
        )
    }
    val groupList = groups.all.first().map { group -> BackupGroup(name = group.name, members = group.members.map { it.hex }) }
    val messageList = messages.forBackup().mapNotNull { record ->
        val voice = if (record.kind == MessageKind.Voice) messages.voiceClip(record.id) ?: return@mapNotNull null else null
        BackupMessage(
            id = record.id.value,
            peer = record.peer.hex,
            outgoing = record.outgoing,
            body = record.body,
            state = record.state.dbValue,
            sent_at_ms = record.sentAtMs,
            received_at_ms = record.receivedAtMs,
            reply_to = record.replyTo?.value.orEmpty(),
            kind = record.kind.dbValue,
            duration_ms = record.durationMs ?: 0,
            announcement = record.announcement,
            voice_audio = voice?.audio?.toByteString() ?: ByteString.EMPTY,
            voice_waveform = voice?.waveform?.toByteString() ?: ByteString.EMPTY,
        )
    }
    return Backup(
        version = BACKUP_VERSION,
        created_at_ms = nowMs,
        display_name = displayName,
        role = role,
        contacts = contactList,
        groups = groupList,
        messages = messageList,
    )
}

/**
 * Adds what the backup has and this device doesn't. Existing contacts and messages are kept as they
 * are; a contact whose fingerprint doesn't match its key is skipped.
 */
internal suspend fun ZoocallStore.applyBackup(backup: Backup, self: Fingerprint, nowMs: Long): RestoreResult.Restored {
    val existing = contacts.all.first().map { it.fingerprint }.toMutableSet()
    var restoredContacts = 0
    for (entry in backup.contacts) {
        val fp = runCatching { Fingerprint.fromHex(entry.fingerprint) }.getOrNull() ?: continue
        val key = entry.public_key.toByteArray()
        if (fp == self || fp in existing || key.size != Sodium.X25519_KEY_BYTES || Fingerprints.of(key) != fp) continue
        val name = entry.display_name.sanitizedName().ifEmpty { continue }
        contacts.add(fp, key, name, entry.role.take(MAX_ROLE), entry.verified, address = null, nowMs = nowMs)
        if (entry.favorite) contacts.setFavorite(fp, true)
        if (entry.blocked) contacts.setBlocked(fp, true)
        if (entry.allow_push_to_talk) contacts.setAllowPushToTalk(fp, true)
        if (entry.allow_intercom) contacts.setAllowIntercom(fp, true)
        existing += fp
        restoredContacts++
    }

    val knownGroups = groups.all.first().map { it.name to it.members.toSet() }.toSet()
    for (entry in backup.groups) {
        val name = entry.name.filterNot { it.isISOControl() }.trim().take(MAX_GROUP_NAME).ifEmpty { continue }
        val members = entry.members.mapNotNull { runCatching { Fingerprint.fromHex(it) }.getOrNull() }.filter { it in existing }.distinct()
        if ((name to members.toSet()) !in knownGroups) groups.create(name, members, nowMs)
    }

    var restoredMessages = 0
    for (entry in backup.messages) {
        if (entry.id.length !in 10..64 || !entry.id.all { it.isLetterOrDigit() }) continue
        val peer = runCatching { Fingerprint.fromHex(entry.peer) }.getOrNull() ?: continue
        val kind = MessageKind.entries.firstOrNull { it.dbValue == entry.kind }?.takeIf { it == MessageKind.Text || it == MessageKind.Voice } ?: continue
        val voice = if (kind == MessageKind.Voice) {
            VoiceClip(entry.voice_audio.toByteArray(), entry.duration_ms, entry.voice_waveform.toByteArray()).takeIf { it.isAcceptable() } ?: continue
        } else {
            null
        }
        // A message still waiting to go out would now be sent from this device's new key: keep it as sent history.
        val state = MessageState.entries.firstOrNull { it.dbValue == entry.state }
            ?.let { if (it == MessageState.Sending) MessageState.Sent else it }
            ?: continue
        val record = ChatMessageRecord(
            id = MessageId(entry.id),
            peer = peer,
            outgoing = entry.outgoing,
            body = if (kind == MessageKind.Text) entry.body.take(MAX_MESSAGE_TEXT) else "",
            state = state,
            sentAtMs = entry.sent_at_ms,
            receivedAtMs = entry.received_at_ms,
            replyTo = entry.reply_to.takeIf { it.isNotEmpty() && it.length <= 64 }?.let(::MessageId),
            kind = kind,
            durationMs = voice?.durationMs,
            announcement = entry.announcement,
        )
        if (messages.insert(record, voice = voice)) restoredMessages++
    }
    return RestoreResult.Restored(restoredContacts, restoredMessages)
}

private const val BACKUP_VERSION = 1
private const val MAX_ROLE = 64
private const val MAX_GROUP_NAME = 40
private const val MAX_MESSAGE_TEXT = 8_000
