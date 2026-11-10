// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.zeffin.MainActivity
import dev.zeffin.link.PermissionAnswer
import dev.zeffin.relay.RelayController

/**
 * The phone's notifications for the agent (docs/design/captures/notify-*): three channels, one notification per
 * conversation for a turn's end and one per permission, tagged by their ids so two never overwrite each other.
 * Every notification hides its content on the lock screen behind a public version; a permission's public version
 * keeps only Deny, so nothing is allowed without being seen.
 */
class AndroidNotifications(
    private val context: Context,
    /** A conversation's title, from the chat's list. */
    private val titleOf: (String) -> String?,
    /** The agent's name (hello), for the texts that name it. */
    private val agentName: () -> String? = { null },
    /** The server's home folder (hello), which a request's path shows as "~". */
    private val home: () -> String? = { null },
) : NotifySink {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private fun words() = PresentNotify(RelayController.currentLang(context), agentName(), home())

    /** Creates the channels, or renames them after a language change. */
    fun ensureChannels() {
        val w = words()
        fun channel(id: String, name: String, desc: String, importance: Int) =
            NotificationChannel(id, name, importance).apply { description = desc; setShowBadge(importance > NotificationManager.IMPORTANCE_LOW) }
        nm.createNotificationChannels(listOf(
            channel(CH_WORKING, w.workingChannel, w.workingChannelDesc, NotificationManager.IMPORTANCE_LOW),
            channel(CH_TURNS, w.turnsChannel, w.turnsChannelDesc, NotificationManager.IMPORTANCE_DEFAULT),
            channel(CH_PERMISSIONS, w.permissionsChannel, w.permissionsChannelDesc, NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    /** The ongoing notification of [NotifyService]: no buttons, so Android holds it back ~10 s and a short turn shows none. */
    fun working(work: Work): Notification {
        val w = words()
        return builder(CH_WORKING, work.conversation)
            .setContentTitle(w.working)
            .setContentText(w.title(titleOf(work.conversation)))
            .setWhen((work.startedAt * 1000).toLong())
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPublicVersion(builder(CH_WORKING, null).setContentTitle(w.working).build())
            .build()
    }

    override fun turnEnded(note: TurnNote) {
        val text = words().turnEnded(note, titleOf(note.conversation))
        nm.notify(note.conversation, ID_TURN, builder(CH_TURNS, note.conversation, text)
            .setStyle(Notification.BigTextStyle().bigText(text.text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build())
    }

    override fun ask(note: AskNote) {
        val w = words()
        val p = note.permission
        val text = w.ask(p, titleOf(note.conversation))
        val plan = w.isPlan(p)
        val b = builder(CH_PERMISSIONS, note.conversation, text, publicDeny = if (plan) null else answer(p.id, PermissionAnswer.DENY))
            .setStyle(Notification.BigTextStyle().bigText(text.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen((p.deadline * 1000).toLong())
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(((p.deadline - System.currentTimeMillis() / 1000.0) * 1000).toLong().coerceAtLeast(1_000) + 5_000)
            .setAutoCancel(true)
        if (!plan) {
            if (text.allowOnce) b.addAction(Notification.Action.Builder(null, w.allowOnce, answer(p.id, PermissionAnswer.ONCE))
                .setAuthenticationRequired(true).build())
            b.addAction(Notification.Action.Builder(null, w.deny, answer(p.id, PermissionAnswer.DENY)).build())
        }
        nm.notify(p.id, ID_ASK, b.build())
    }

    override fun sending(id: String, answer: PermissionAnswer) = brief(id, words().sending(answer), done = false)

    override fun notSent(id: String, why: NotSent) = brief(id, words().notSent(why), done = true)

    override fun lost(conversation: String) {
        val w = words()
        val text = NoteText(w.lostSub, w.lostTitle, w.lostText, w.lostTitle, w.lostText)
        nm.notify(conversation, ID_TURN, builder(CH_TURNS, conversation, text).setCategory(Notification.CATEGORY_ERROR).setAutoCancel(true).build())
    }

    override fun cancelAsk(id: String) = nm.cancel(id, ID_ASK)

    override fun clearAll() {
        nm.activeNotifications.filter { it.id == ID_TURN || it.id == ID_ASK }.forEach { nm.cancel(it.tag, it.id) }
    }

    /** Replaces a permission's notification with a short line, without sound; gone after a moment when [done]. */
    private fun brief(id: String, line: String, done: Boolean) {
        val old = nm.activeNotifications.firstOrNull { it.id == ID_ASK && it.tag == id }?.notification ?: return
        val b = Notification.Builder.recoverBuilder(context, old)
        b.setActions().setStyle(null).setContentText(line).setOnlyAlertOnce(true).setUsesChronometer(false)
            .setPublicVersion(Notification.Builder(context, CH_PERMISSIONS).setSmallIcon(dev.zeffin.relay.R.drawable.ic_relay)
                .setColor(ACCENT).setContentTitle(line).build())
        if (done) b.setTimeoutAfter(4_000)
        nm.notify(id, ID_ASK, b.build())
    }

    /** A notification of [channel] that opens [conversation] when tapped; its lock-screen version carries [publicDeny] only. */
    private fun builder(channel: String, conversation: String?, text: NoteText? = null, publicDeny: PendingIntent? = null): Notification.Builder {
        val b = Notification.Builder(context, channel)
            .setSmallIcon(dev.zeffin.relay.R.drawable.ic_relay)
            .setColor(ACCENT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (conversation != null) b.setContentIntent(open(conversation))
        if (text != null) {
            val public = Notification.Builder(context, channel).setSmallIcon(dev.zeffin.relay.R.drawable.ic_relay).setColor(ACCENT)
                .setSubText(text.sub).setContentTitle(text.publicTitle).setContentText(text.publicText)
            if (publicDeny != null) public.addAction(Notification.Action.Builder(null, words().deny, publicDeny).build())
            b.setSubText(text.sub).setContentTitle(text.title).setContentText(text.text).setPublicVersion(public.build())
        }
        return b
    }

    private fun open(conversation: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(RelayController.EXTRA_OPEN, OPEN_CONVERSATION + conversation)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, conversation.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun answer(id: String, a: PermissionAnswer): PendingIntent {
        val intent = Intent(context, NotifyReceiver::class.java).setAction(NotifyReceiver.ACTION_ANSWER)
            .putExtra(NotifyReceiver.EXTRA_ID, id).putExtra(NotifyReceiver.EXTRA_ANSWER, a.wire)
        return PendingIntent.getBroadcast(context, (id + a.wire).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CH_WORKING = "agent_working"
        const val CH_TURNS = "agent_turns"
        const val CH_PERMISSIONS = "agent_permissions"
        /** The relay's notification is 1. */
        const val ID_WORKING = 2
        const val ID_TURN = 3
        const val ID_ASK = 4
        /** RelayController.EXTRA_OPEN's value that opens a conversation: "conversation/<id>". */
        const val OPEN_CONVERSATION = "conversation/"
        private const val ACCENT = 0xFFFF8200.toInt()
    }
}
