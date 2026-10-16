package io.github.duwenji.yorisoi.sanpoguide.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import io.github.duwenji.yorisoi.sanpoguide.report.Report
import io.github.duwenji.yorisoi.sanpoguide.report.ReportTarget

/**
 * Opens a draft email to whoever answers for the channel (platform ADR-004 H-11), about one line
 * ([said]: when and what) or the channel as a whole. The user writes it and sends it, or not.
 */
@Composable
fun ReportLink(target: ReportTarget, said: Pair<Long, String>?, label: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            context.startActivity(Report.mail(target, said, BuildConfig.VERSION_NAME))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "メールのアプリがありません。${target.contact} までお知らせください", Toast.LENGTH_LONG).show()
        }
    }) { Text(label) }
}
