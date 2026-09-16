package app.zoocall.ui.components

import androidx.compose.runtime.Composable
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.time_hours_ago
import app.zoocall.ui.resources.time_just_now
import app.zoocall.ui.resources.time_minutes_ago
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

private val zone get() = TimeZone.currentSystemDefault()

fun localDateOf(ms: Long, timeZone: TimeZone = zone): LocalDate = Instant.fromEpochMilliseconds(ms).toLocalDateTime(timeZone).date

fun today(): LocalDate = Clock.System.now().toLocalDateTime(zone).date

/** "14:05" */
fun formatClock(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/** "14:05" today, "Mon" within a week, otherwise "12/9". Locale-neutral short forms. */
fun formatShortTimestamp(ms: Long): String {
    val date = localDateOf(ms)
    val days = today().toEpochDays() - date.toEpochDays()
    return when {
        days == 0L -> formatClock(ms)
        days in 1..6 -> date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        else -> "${date.day}/${date.month.ordinal + 1}"
    }
}

/** "just now", "5 min ago", "2 h ago", or a short date — translated. */
@Composable
fun relativeTime(ms: Long): String {
    val diff = Clock.System.now().toEpochMilliseconds() - ms
    return when {
        diff < 60_000 -> stringResource(Res.string.time_just_now)
        diff < 3_600_000 -> stringResource(Res.string.time_minutes_ago, (diff / 60_000).toInt())
        diff < 86_400_000 -> stringResource(Res.string.time_hours_ago, (diff / 3_600_000).toInt())
        else -> formatShortTimestamp(ms)
    }
}
