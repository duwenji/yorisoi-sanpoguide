package io.github.duwenji.yorisoi.sanpoguide.report

import android.content.Intent
import android.net.Uri
import io.github.duwenji.yorisoi.kit.channel.report.Report
import io.github.duwenji.yorisoi.kit.channel.report.ReportTarget

/** SanpoGuide's reports (kit-channel [Report]): an email the user writes and sends themselves. */
object AppReport {
    /** The app's contact, as in the privacy policy. */
    const val CONTACT = "duwenji@gmail.com"

    val report = Report("SanpoGuide", CONTACT)

    /** Opens the user's mail app with the draft; nothing is sent until they send it. */
    fun mail(target: ReportTarget, said: Pair<Long, String>?, appVersion: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(target.contact))
            putExtra(Intent.EXTRA_SUBJECT, report.subject(target, said))
            putExtra(Intent.EXTRA_TEXT, report.body(target, said, appVersion))
        }
}
