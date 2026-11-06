package dev.opencode.hexcode.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import dev.opencode.hexcode.designsystem.HexTheme
import dev.opencode.hexcode.session.SummaryLinks
import dev.opencode.hexcode.session.SummaryRun
import dev.opencode.hexcode.session.SummaryText

/**
 * [text]'s inline Markdown drawn (app-plan.md, Formatting): code in the system monospace at 0.84 of the size on a
 * faint tint, bold in [bold] and `text1`. [prefix] and [suffix] go round it unformatted ("Next: …").
 */
@Composable
internal fun summaryText(
    text: String,
    bold: FontWeight = FontWeight.SemiBold,
    prefix: String = "",
    suffix: String = "",
): AnnotatedString {
    val tint = HexTheme.colors.tint(7f)
    val strong = HexTheme.colors.text1
    return remember(text, bold, prefix, suffix, tint, strong) { formatted(text, bold, tint, strong, prefix, suffix) }
}

private fun formatted(
    text: String,
    bold: FontWeight,
    tint: Color,
    strong: Color,
    prefix: String,
    suffix: String,
): AnnotatedString =
    buildAnnotatedString {
        append(prefix)
        SummaryText.runs(text).forEach { run ->
            when (run.style) {
                SummaryRun.Style.Plain -> append(run.text)
                SummaryRun.Style.Code ->
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.84.em,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.em,
                            background = tint,
                        ),
                    ) { append(run.text) }
                SummaryRun.Style.Bold -> withStyle(SpanStyle(fontWeight = bold, color = strong)) { append(run.text) }
            }
        }
        append(suffix)
    }

/**
 * [text] formatted inside [template], a string resource filled with [SUMMARY_MARK] where the text goes ("Next: %1$s"):
 * the resource's own words stay plain.
 */
@Composable
internal fun summaryTextIn(
    template: String,
    text: String,
): AnnotatedString {
    val at = template.indexOf(SUMMARY_MARK)
    if (at < 0) return summaryText(text)
    return summaryText(text, prefix = template.substring(0, at), suffix = template.substring(at + SUMMARY_MARK.length))
}

/** Stands in for the text in a template given to [summaryTextIn]. */
internal const val SUMMARY_MARK = "\u0000"

/** What a link tile shows under its label: the host, or for a local URL the computer's address with its port. */
internal fun tileHost(
    url: String,
    computer: String?,
): String = SummaryLinks.host(if (computer != null && SummaryLinks.isLocal(url)) SummaryLinks.onComputer(url, computer) else url)

/**
 * Opens a link tile's [url] (app-plan.md, Links): a `localhost` page in the in-app browser (a Custom Tab) at the
 * paired [computer]'s address, with the same port and path; anything else in the system browser.
 */
internal fun openLink(
    context: Context,
    url: String,
    computer: String?,
) {
    val local = SummaryLinks.isLocal(url)
    val target = if (local && computer != null) SummaryLinks.onComputer(url, computer) else url
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            // A Custom Tab is a view intent with this extra, which browsers that support them read.
            if (local) putExtras(Bundle().apply { putBinder(CUSTOM_TAB_SESSION, null) })
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Log.w(TalkLog.TAG, "nothing opens $target", error)
    }
}

/** `CustomTabsIntent.EXTRA_SESSION`. */
private const val CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"
