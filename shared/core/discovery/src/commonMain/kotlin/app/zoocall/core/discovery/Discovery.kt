package app.zoocall.core.discovery

import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.PeerAddress
import kotlinx.coroutines.flow.Flow

/** What this device announces on the LAN (docs/03-protocol.md §2). */
data class ServiceRecord(
    /** Random per install. Never the person's name. */
    val instanceName: String,
    val port: Int,
    /** Omitted in "Contacts only" visibility. */
    val fingerprintShortId: String?,
    /** Omitted when visibility isn't "Everyone". */
    val displayName: String?,
    val role: String?,
    val deviceClass: DeviceClass,
    /** "Contacts only": rotating tags only contacts can recognize. */
    val tags: List<String> = emptyList(),
)

data class DiscoveredService(
    val instanceName: String,
    val addresses: List<PeerAddress>,
    val txt: TxtRecord,
)

sealed interface DiscoveryEvent {
    data class Found(val service: DiscoveredService) : DiscoveryEvent
    data class Lost(val instanceName: String) : DiscoveryEvent
}

/** Platform port: Android `NsdManager`, Desktop JmDNS. */
interface Discovery {
    /** Starts (or replaces) the local advertisement. */
    fun advertise(record: ServiceRecord)
    fun stopAdvertising()

    /** Cold flow: browsing runs while collected. Own advertisement is filtered out. */
    fun browse(): Flow<DiscoveryEvent>
}

/** Parsed, validated TXT record. Untrusted input: every field is length-capped. */
data class TxtRecord(
    val versions: List<Int>,
    val fingerprintShortId: String?,
    val displayName: String?,
    val role: String?,
    val deviceClass: DeviceClass,
    /** Private discovery tags (8 hex characters each). */
    val tags: List<String> = emptyList(),
) {
    fun toAttributes(): Map<String, String> = buildMap {
        put(KEY_VERSIONS, versions.joinToString(","))
        fingerprintShortId?.let { put(KEY_FP, it) }
        if (tags.isNotEmpty()) put(KEY_TAGS, tags.take(MAX_TAGS).joinToString(","))
        displayName?.let { put(KEY_NAME, it.take(MAX_NAME)) }
        role?.takeIf { it.isNotBlank() }?.let { put(KEY_ROLE, it.take(MAX_ROLE)) }
        put(
            KEY_DEVICE,
            when (deviceClass) {
                DeviceClass.Phone -> "phone"
                DeviceClass.Tablet -> "tablet"
                DeviceClass.Desktop -> "desktop"
            },
        )
    }

    companion object {
        const val SERVICE_TYPE = "_zoocall._tcp"
        const val KEY_VERSIONS = "v"
        const val KEY_FP = "fp"
        const val KEY_NAME = "n"
        const val KEY_ROLE = "r"
        const val KEY_DEVICE = "d"
        const val KEY_TAGS = "t"
        private const val MAX_NAME = 64
        private const val MAX_ROLE = 64
        private const val MAX_TAGS = 16

        fun from(record: ServiceRecord) = TxtRecord(
            versions = listOf(1),
            fingerprintShortId = record.fingerprintShortId,
            displayName = record.displayName,
            role = record.role,
            deviceClass = record.deviceClass,
            tags = record.tags,
        )

        fun parse(attributes: Map<String, String?>): TxtRecord = TxtRecord(
            versions = attributes[KEY_VERSIONS].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.take(8),
            fingerprintShortId = attributes[KEY_FP]?.takeIf { fp -> fp.length == 20 && fp.all { it in 'a'..'z' || it in '2'..'7' } },
            displayName = attributes[KEY_NAME]?.sanitized(MAX_NAME),
            role = attributes[KEY_ROLE]?.sanitized(MAX_ROLE),
            deviceClass = when (attributes[KEY_DEVICE]) {
                "desktop" -> DeviceClass.Desktop
                "tablet" -> DeviceClass.Tablet
                else -> DeviceClass.Phone
            },
            tags = attributes[KEY_TAGS].orEmpty().split(',').map { it.trim() }
                .filter { tag -> tag.length == 8 && tag.all { it in '0'..'9' || it in 'a'..'f' } }
                .take(MAX_TAGS),
        )

        /** Strips control and bidi-override characters so names can't spoof the UI. */
        private fun String.sanitized(max: Int): String? =
            filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' }
                .trim()
                .take(max)
                .takeIf { it.isNotEmpty() }
    }
}
