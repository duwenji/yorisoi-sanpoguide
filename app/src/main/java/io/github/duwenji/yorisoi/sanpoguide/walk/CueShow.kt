package io.github.duwenji.yorisoi.sanpoguide.walk

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.duwenji.yorisoi.sanpoguide.R
import io.github.duwenji.yorisoi.sanpoguide.SanpoApp
import io.github.duwenji.yorisoi.sanpoguide.companion.CompanionFeed
import io.github.duwenji.yorisoi.sanpoguide.companion.Utterance
import io.github.duwenji.yorisoi.kit.voice.Speaker
import io.github.duwenji.yorisoi.sanpoguide.material.Content
import io.github.duwenji.yorisoi.sanpoguide.material.CueDetail
import io.github.duwenji.yorisoi.sanpoguide.material.CueLayout
import io.github.duwenji.yorisoi.sanpoguide.material.CueStep
import io.github.duwenji.yorisoi.sanpoguide.material.Material
import io.github.duwenji.yorisoi.sanpoguide.material.bitmap
import io.github.duwenji.yorisoi.sanpoguide.material.creditLine
import io.github.duwenji.yorisoi.sanpoguide.material.promotionNotice
import io.github.duwenji.yorisoi.sanpoguide.ui.MainActivity

/**
 * Shows a channel's cue: spoken lines, cards in the feed and a notification, in
 * the cue's order. Its sheet and full-screen image go with the last line it puts in the feed (the
 * one the folded card shows) and with its notification, to be opened only by a tap (API-003
 *「見せ方」); a cue that puts no line in the feed gets one that says so.
 */
class CueShow(private val context: Context, private val feed: CompanionFeed, private val speaker: Speaker) {
    /**
     * @param title the notification's title when no spot is named: the channel's name.
     * @param say how a spoken line is said; outside a walk, into the feed and the speech queue.
     */
    fun show(layout: CueLayout, title: String, spotName: String?, say: (Utterance) -> Unit = ::sayOutsideWalk) {
        val now = System.currentTimeMillis()
        val last = layout.steps.indexOfLast { it !is CueStep.Notify }
        var name = spotName
        fun line(i: Int, m: Material): Utterance {
            val image = m.takeIf { it.content !is Content.Text }
            return Utterance(now, textOf(m) ?: "", name, image, creditLine(m), layout.detail.takeIf { i == last }, promotionNotice(m)).also { name = null }
        }
        layout.steps.forEachIndexed { i, step ->
            when (step) {
                is CueStep.Speak -> say(line(i, step.material))
                is CueStep.Card -> feed.add(line(i, step.material))
                is CueStep.Notify -> notify(step.material, spotName ?: title, layout.detail)
            }
        }
        if (last < 0) layout.detail?.let { feed.add(Utterance(now, "${spotName?.let { "${it}の" } ?: ""}解説があります", name, detail = it)) }
    }

    private fun sayOutsideWalk(u: Utterance) {
        feed.add(u)
        speaker.speak(spoken(u))
    }

    private fun notify(m: Material, title: String, detail: CueDetail?) {
        val text = textOf(m) ?: m.alt.orEmpty()
        val credit = creditLine(m)
        val intent = Intent(context, MainActivity::class.java)
            .apply { detail?.let { putExtra(MainActivity.EXTRA_CUE, it.id) } }
        val builder = NotificationCompat.Builder(context, SanpoApp.CHANNEL_CUE)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(credit)
            .setContentIntent(PendingIntent.getActivity(context, CUE_NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
        val picture = m.bitmap(NOTIFICATION_IMAGE_PX)
        builder.setStyle(
            if (picture != null) NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(text)
            else NotificationCompat.BigTextStyle().bigText(text),
        )
        context.getSystemService(NotificationManager::class.java).notify(CUE_NOTIFICATION_ID, builder.build())
    }

    companion object {
        // One slot: each cue's notification replaces the last.
        private const val CUE_NOTIFICATION_ID = 4
        private const val NOTIFICATION_IMAGE_PX = 1024

        fun textOf(m: Material): String? = (m.content as? Content.Text)?.text

        /** What is said for a line: the spot's name first, as for the companion's own lines, then a promotion's notice. */
        fun spoken(u: Utterance): String = listOfNotNull(u.spotName?.let { "${it}。" }, u.spokenPrefix, u.text).joinToString("")
    }
}
