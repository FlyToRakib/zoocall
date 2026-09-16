package app.zoocall.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zoocall.core.model.Presence
import app.zoocall.ui.platform.QrMatrix
import app.zoocall.ui.resources.Res
import app.zoocall.ui.resources.not_verified
import app.zoocall.ui.resources.presence_available
import app.zoocall.ui.resources.presence_away
import app.zoocall.ui.resources.presence_busy
import app.zoocall.ui.resources.presence_dnd
import app.zoocall.ui.resources.presence_offline
import app.zoocall.ui.resources.verified
import app.zoocall.ui.theme.Spacing
import app.zoocall.ui.theme.ZoocallTheme
import org.jetbrains.compose.resources.stringResource

/** Accessible avatar palette: white initials meet 4.5:1 on every color. */
private val AvatarColors = listOf(
    Color(0xFF00695C), Color(0xFF1565C0), Color(0xFF6A1B9A), Color(0xFFAD1457),
    Color(0xFFC62828), Color(0xFF4E342E), Color(0xFF37474F), Color(0xFF2E7D32),
)

fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/** Initials avatar with an optional presence dot. Decorative for screen readers: the row text carries meaning. */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    presence: Presence? = null,
    online: Boolean = true,
    /** Colour behind the avatar; the dot's ring uses it so the dot looks cut into the avatar edge. */
    ringColor: Color = MaterialTheme.colorScheme.background,
) {
    val color = AvatarColors[(name.hashCode() and 0x7fffffff) % AvatarColors.size]
    Box(modifier.size(size).clearAndSetSemantics { }) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsOf(name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
        if (presence != null) {
            // Readable at every avatar size: never smaller than 8 dp, ring drawn around (not inside) the dot.
            val dot = maxOf(8.dp, size * 0.24f)
            val ring = maxOf(1.5.dp, size * 0.05f)
            val outer = dot + ring * 2
            // Centre the dot on the circle's edge at 45° (bottom-right), not on the square's corner,
            // which lies outside a round avatar.
            val centre = size / 2 + size * 0.3536f
            Box(
                Modifier
                    .offset(x = centre - outer / 2, y = centre - outer / 2)
                    .size(outer)
                    .clip(CircleShape)
                    .background(ringColor)
                    .padding(ring)
                    .clip(CircleShape)
                    .background(presenceColor(presence, online)),
            )
        }
    }
}

/**
 * An avatar used as a button (e.g. the profile shortcut in top bars). Unlike wrapping it in an
 * IconButton, nothing clips the presence dot that sits on the avatar's edge. 48 dp touch target.
 */
@Composable
fun AvatarButton(
    name: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    presence: Presence? = null,
    online: Boolean = true,
) {
    Box(
        modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Avatar(name, size = size, presence = presence, online = online)
    }
}

@Composable
fun presenceColor(presence: Presence, online: Boolean): Color = when {
    !online -> MaterialTheme.colorScheme.outline
    presence == Presence.Available -> ZoocallTheme.colors.presenceAvailable
    presence == Presence.Away -> ZoocallTheme.colors.presenceAway
    else -> ZoocallTheme.colors.presenceBusy
}

@Composable
fun presenceLabel(presence: Presence, online: Boolean): String = when {
    !online -> stringResource(Res.string.presence_offline)
    presence == Presence.Available -> stringResource(Res.string.presence_available)
    presence == Presence.Busy -> stringResource(Res.string.presence_busy)
    presence == Presence.DoNotDisturb -> stringResource(Res.string.presence_dnd)
    else -> stringResource(Res.string.presence_away)
}

/** Verified state is always icon + text, never color alone. */
@Composable
fun VerificationLabel(verified: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = if (verified) ZoocallTheme.colors.verified else MaterialTheme.colorScheme.onSurfaceVariant
    val text = stringResource(if (verified) Res.string.verified else Res.string.not_verified)
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (verified) Icons.Rounded.Verified else Icons.Rounded.GppMaybe,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
        )
        if (!compact || !verified) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(start = Spacing.xs))
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .semantics { heading() },
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(44.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        actions()
    }
}

@Composable
fun Banner(text: String, icon: ImageVector, modifier: Modifier = Modifier, warning: Boolean = false) {
    Surface(
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.m))
        }
    }
}

@Composable
fun QrCode(matrix: QrMatrix, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(Color.White)
            .padding(Spacing.l)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(240.dp)) {
            val cell = size.width / matrix.size
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

/** Centers content and caps its width so lists and chats stay readable in wide desktop windows. */
fun Modifier.readableContentWidth(max: Dp = 760.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)

/** 12:34 or 1:02:03 */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}
