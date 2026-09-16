package app.zoocall.android.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.zoocall.android.R
import app.zoocall.android.ZoocallApplication
import app.zoocall.android.call.CallActivity
import app.zoocall.android.call.mainActivityIntent
import app.zoocall.core.call.CallPhase
import app.zoocall.core.chat.KnockReply
import app.zoocall.core.model.Fingerprint
import kotlinx.coroutines.launch

object Notifications {
    const val CHANNEL_INCOMING = "incoming_calls_v1"
    const val CHANNEL_ONGOING = "ongoing_calls_v1"
    const val CHANNEL_MESSAGES = "messages_v1"
    const val CHANNEL_MISSED = "missed_calls_v1"
    const val CHANNEL_REACHABLE = "reachable_v1"
    const val CHANNEL_KNOCKS = "knocks_v1"
    const val CHANNEL_ANNOUNCEMENTS = "announcements_v1"

    const val ID_SERVICE = 1
    private const val ID_INCOMING = 2
    private const val ID_MISSED_BASE = 1_000
    private const val ID_MESSAGE_BASE = 10_000
    private const val ID_KNOCK_BASE = 20_000
    private const val ID_ANNOUNCEMENT_BASE = 30_000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.channel_incoming_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                    // The app plays the ringtone itself so it can stop the moment the call is answered.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.channel_ongoing_calls), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_KNOCKS, context.getString(R.string.channel_knocks), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_ANNOUNCEMENTS, context.getString(R.string.channel_announcements), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_MISSED, context.getString(R.string.channel_missed_calls), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_REACHABLE, context.getString(R.string.channel_reachable), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.channel_reachable_description)
                    setShowBadge(false)
                },
            ),
        )
    }

    fun reachable(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_REACHABLE)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(context.getString(R.string.reachable_title))
            .setContentText(context.getString(R.string.reachable_text))
            .setContentIntent(activityIntent(context, 10, context.mainActivityIntent()))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun showIncomingCall(context: Context, name: String, video: Boolean) {
        val person = Person.Builder().setName(name).setImportant(true).build()
        val fullScreen = activityIntent(context, 20, Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val answer = activityIntent(
            context, 21,
            Intent(context, CallActivity::class.java)
                .setAction(CallActivity.ACTION_ACCEPT)
                .putExtra(CallActivity.EXTRA_VIDEO, video)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val decline = broadcastIntent(context, 22, CallActionReceiver.ACTION_DECLINE)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(name)
            .setContentText(context.getString(if (video) R.string.notify_incoming_video else R.string.notify_incoming_audio))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video))
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        notify(context, ID_INCOMING, notification)
    }

    fun cancelIncomingCall(context: Context) = NotificationManagerCompat.from(context).cancel(ID_INCOMING)

    fun ongoingCall(context: Context, name: String, video: Boolean, connectedAtMs: Long?): Notification {
        val person = Person.Builder().setName(name).build()
        val open = activityIntent(context, 30, Intent(context, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val hangUp = broadcastIntent(context, 31, CallActionReceiver.ACTION_HANG_UP)
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(name)
            .setContentText(context.getString(if (connectedAtMs != null) R.string.notify_ongoing else R.string.notify_calling))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp).setIsVideo(video))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (connectedAtMs != null) {
                    setWhen(connectedAtMs)
                    setUsesChronometer(true)
                }
            }
            .build()
    }

    fun showMissedCall(context: Context, name: String, fpHex: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(context.getString(R.string.notify_missed_title))
            .setContentText(context.getString(R.string.notify_missed_text, name))
            .setContentIntent(activityIntent(context, 40, context.mainActivityIntent()))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .build()
        notify(context, ID_MISSED_BASE + (fpHex.hashCode() and 0xfff), notification)
    }

    /** [fpHex] is the conversation to open: a fingerprint, or a group chat target from MainActivity. */
    fun showMessage(context: Context, name: String, fpHex: String, text: String, groupName: String? = null) {
        val sender = Person.Builder().setName(name).build()
        val me = Person.Builder().setName(context.getString(R.string.app_name)).build()
        val style = NotificationCompat.MessagingStyle(me).addMessage(text.take(500), System.currentTimeMillis(), sender)
        if (groupName != null) {
            style.conversationTitle = groupName
            style.isGroupConversation = true
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setStyle(style)
            .setContentIntent(activityIntent(context, 50 + (fpHex.hashCode() and 0xfff), context.mainActivityIntent(fpHex)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        notify(context, ID_MESSAGE_BASE + (fpHex.hashCode() and 0xfff), notification)
    }

    /** A knock with one-tap replies right in the notification. [quiet]: Do Not Disturb, no sound. */
    fun showKnock(context: Context, name: String, fpHex: String, knockId: String, text: String, quiet: Boolean) {
        val slot = fpHex.hashCode() and 0xfff
        val replies = listOf(
            KnockReply.CallMe to R.string.notify_knock_call_me,
            KnockReply.TwoMinutes to R.string.notify_knock_two_minutes,
            KnockReply.Busy to R.string.notify_knock_busy,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_KNOCKS)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(context.getString(R.string.notify_knock_title, name))
            .setContentText(text.ifBlank { context.getString(R.string.notify_knock_text) })
            .setContentIntent(activityIntent(context, 60_000 + slot, context.mainActivityIntent()))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(quiet)
            .setAutoCancel(true)
            // A knock is about "now".
            .setTimeoutAfter(KNOCK_TIMEOUT_MS)
            .apply {
                replies.forEach { (reply, label) ->
                    val intent = Intent(context, KnockActionReceiver::class.java)
                        .setAction(KnockActionReceiver.ACTION_REPLY)
                        .putExtra(KnockActionReceiver.EXTRA_PEER, fpHex)
                        .putExtra(KnockActionReceiver.EXTRA_KNOCK, knockId)
                        .putExtra(KnockActionReceiver.EXTRA_REPLY, reply.name)
                    val pending = PendingIntent.getBroadcast(
                        context, 70_000 + slot * 4 + reply.ordinal, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(0, context.getString(label), pending)
                }
            }
            .build()
        notify(context, ID_KNOCK_BASE + slot, notification)
    }

    fun showKnockReply(context: Context, name: String, fpHex: String, reply: KnockReply) {
        val slot = fpHex.hashCode() and 0xfff
        val text = when (reply) {
            KnockReply.CallMe -> R.string.notify_knock_replied_call_me
            KnockReply.TwoMinutes -> R.string.notify_knock_replied_two_minutes
            KnockReply.Busy -> R.string.notify_knock_replied_busy
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_KNOCKS)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(name)
            .setContentText(context.getString(text))
            .setContentIntent(activityIntent(context, 60_000 + slot, context.mainActivityIntent()))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(KNOCK_TIMEOUT_MS)
            .build()
        notify(context, ID_KNOCK_BASE + slot, notification)
    }

    /** A group announcement alerts once, even while Zoocall is open. [quiet]: Do Not Disturb. */
    fun showAnnouncement(context: Context, name: String, fpHex: String, text: String, quiet: Boolean) {
        val slot = fpHex.hashCode() and 0xfff
        val body = text.take(500)
        val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS)
            .setSmallIcon(R.drawable.ic_stat_zoocall)
            .setContentTitle(context.getString(R.string.notify_announcement_title, name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(activityIntent(context, 80_000 + slot, context.mainActivityIntent(fpHex)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(quiet)
            .setAutoCancel(true)
            .build()
        notify(context, ID_ANNOUNCEMENT_BASE + slot, notification)
    }

    fun cancelKnock(context: Context, fpHex: String) =
        NotificationManagerCompat.from(context).cancel(ID_KNOCK_BASE + (fpHex.hashCode() and 0xfff))

    private const val KNOCK_TIMEOUT_MS = 10 * 60_000L

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun activityIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcastIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Decline / hang up straight from the notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ZoocallApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                val phase = app.core.activeCall.value?.state?.phase
                when (intent.action) {
                    ACTION_DECLINE -> if (phase == CallPhase.IncomingRinging) app.core.declineCall()
                    ACTION_HANG_UP -> if (phase != null && phase != CallPhase.Ended) app.core.hangUp()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.zoocall.action.DECLINE"
        const val ACTION_HANG_UP = "app.zoocall.action.HANG_UP"
    }
}

/** One-tap knock replies from the notification. */
class KnockActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val fpHex = intent.getStringExtra(EXTRA_PEER) ?: return
        val knockId = intent.getStringExtra(EXTRA_KNOCK) ?: return
        val reply = intent.getStringExtra(EXTRA_REPLY)?.let { name -> KnockReply.entries.firstOrNull { it.name == name } } ?: return
        val peer = runCatching { Fingerprint.fromHex(fpHex) }.getOrNull() ?: return
        val app = context.applicationContext as ZoocallApplication
        Notifications.cancelKnock(context, fpHex)
        val pending = goAsync()
        app.appScope.launch {
            try {
                app.core.replyToKnock(peer, knockId, reply)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.zoocall.action.KNOCK_REPLY"
        const val EXTRA_PEER = "peer"
        const val EXTRA_KNOCK = "knock"
        const val EXTRA_REPLY = "reply"
    }
}
