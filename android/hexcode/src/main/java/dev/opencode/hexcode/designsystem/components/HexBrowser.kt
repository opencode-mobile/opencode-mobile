package dev.opencode.hexcode.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opencode.hexcode.designsystem.HexIcons
import dev.opencode.hexcode.designsystem.HexTheme

/** [HexBrowser]'s words, from the app's strings. */
data class HexBrowserLabels(
    val done: String,
    val reload: String,
    val back: String,
    val forward: String,
    /** "Open in browser"; null hides it. */
    val openSystem: String?,
    val loading: String,
)

/** The page area's error: a title, a detail and a button. */
data class HexBrowserError(
    val title: String,
    val detail: String,
    val button: String,
    val onClick: () -> Unit,
)

/**
 * The in-app browser's page (`HexBrowser`, the prototype's `.sv-top` and `.sv-bar`): a top bar on `panel` with Done,
 * the page's [title] over its [host] (no lock: the page is plain HTTP) and Reload, with a 2 pt `text1` line along its
 * bottom edge while [progress] (0 to 1) loads; the [page] itself, or the [error] in its place; and a bottom bar with
 * Back and Forward, dimmed to 35 % when there's nowhere to go, and "Open in browser". The chrome follows the app's
 * look; the page draws itself as it is.
 */
@Composable
fun HexBrowser(
    title: String,
    host: String,
    progress: Float?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    labels: HexBrowserLabels,
    onDone: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onOpenSystem: () -> Unit,
    modifier: Modifier = Modifier,
    error: HexBrowserError? = null,
    page: @Composable () -> Unit,
) {
    val colors = HexTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.panel)
            .semantics { paneTitle = title },
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp),
            ) {
                Box(Modifier.widthIn(min = 64.dp)) {
                    Text(
                        labels.done,
                        style = HexTheme.type.button,
                        color = colors.text1,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(HexTheme.radius.s))
                                .clickable(role = Role.Button, onClick = onDone)
                                .heightIn(min = HexTheme.size.touch)
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        title,
                        style = HexTheme.type.callout,
                        color = colors.text1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(host, style = HexTheme.type.caption, color = colors.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) {
                    HexIconButton(HexIcons.refresh, labels.reload, onClick = onReload)
                }
            }
            // The bar's bottom edge, with the loading line along it.
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .align(Alignment.BottomStart)
                        .background(colors.line),
                )
                if (progress != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0.05f, 1f))
                            .height(2.dp)
                            .background(colors.text1)
                            .semantics {
                                contentDescription = labels.loading
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(colors.background)) {
            page()
            if (error != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 28.dp),
                ) {
                    Text(
                        error.title,
                        style = HexTheme.type.headline,
                        color = colors.text1,
                        modifier =
                            Modifier.semantics {
                                heading()
                                liveRegion = LiveRegionMode.Assertive
                            },
                    )
                    Text(error.detail, style = HexTheme.type.body, color = colors.text2)
                    Spacer(Modifier.height(4.dp))
                    HexButton(onClick = error.onClick, text = error.button, icon = HexIcons.refresh, size = HexButtonSize.Regular)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            HexIconButton(HexIcons.chevL, labels.back, onClick = onBack, enabled = canGoBack)
            HexIconButton(HexIcons.chevR, labels.forward, onClick = onForward, enabled = canGoForward)
            Spacer(Modifier.weight(1f))
            if (labels.openSystem != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(HexTheme.radius.l))
                            .clickable(role = Role.Button, onClick = onOpenSystem)
                            .heightIn(min = HexTheme.size.touch)
                            .padding(horizontal = 12.dp),
                ) {
                    Text(labels.openSystem, style = HexTheme.type.button, color = colors.text2)
                    Icon(HexIcons.out, null, tint = colors.text2, modifier = Modifier.size(HexTheme.size.icon).alpha(1f))
                }
            }
        }
    }
}
