package io.github.duwenji.yorisoi.sanpoguide.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import io.github.duwenji.yorisoi.sanpoguide.report.AppReport
import io.github.duwenji.yorisoi.kit.channel.report.ReportTarget

/**
 * Opens a draft email to whoever answers for the channel (platform ADR-004 H-11), about one line
 * ([said]: when and what) or the channel as a whole. The user writes it and sends it, or not.
 */
@Composable
fun ReportLink(target: ReportTarget, said: Pair<Long, String>?, label: String) {
    val context = LocalContext.current
    TextButton(onClick = {
        try {
            context.startActivity(AppReport.mail(target, said, BuildConfig.VERSION_NAME))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "メールのアプリがありません。${target.contact} までお知らせください", Toast.LENGTH_LONG).show()
        }
    }) { Text(label) }
}

/** Shown with the AI's narration and its settings: what it says may be wrong (platform ADR-004 H-15). */
const val AI_MAY_BE_WRONG = "AI の発言には誤りがありえます。事実は、現地の案内板や公式の情報で確かめてください"
