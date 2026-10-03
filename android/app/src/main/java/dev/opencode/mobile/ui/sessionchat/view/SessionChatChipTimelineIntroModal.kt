package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeModal
import dev.opencode.mobile.ui.components.OpenCodePageIndicator
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustration
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustrationTopic

/** One page of the Chip timeline intro carousel. */
internal data class SessionChatChipTimelineIntroPage(
    val title: String,
    val body: String,
    val illustration: OpenCodeSkeletonIllustrationTopic,
)

internal val ChipTimelineIntroPages =
    listOf(
        SessionChatChipTimelineIntroPage(
            title = "See the work at a glance",
            body = "Tool calls show as chips under the sentence that explains them.",
            illustration = OpenCodeSkeletonIllustrationTopic.ChipTimelineGlance,
        ),
        SessionChatChipTimelineIntroPage(
            title = "Long runs, short timeline",
            body = "Twenty tool calls take a few chips instead of a screen of rows.",
            illustration = OpenCodeSkeletonIllustrationTopic.ChipTimelineLongRuns,
        ),
        SessionChatChipTimelineIntroPage(
            title = "Details stay one tap away",
            body = "Tap a chip for its diffs, command output, or files.",
            illustration = OpenCodeSkeletonIllustrationTopic.ChipTimelineDetails,
        ),
    )

/**
 * One-time offer to turn on Chip timeline (OPE-270): an onboarding-style carousel
 * in a centered modal. Close, scrim tap, and back count as Later.
 */
@Composable
internal fun SessionChatChipTimelineIntroModal(
    visible: Boolean,
    onEnable: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_CHIP_TIMELINE_INTRO,
        presentation = ProductAnalytics.Presentation.DIALOG,
        visible = visible,
    )
    val pagerState = rememberPagerState(pageCount = { ChipTimelineIntroPages.size })
    LaunchedEffect(visible) {
        if (visible) pagerState.scrollToPage(0)
    }

    OpenCodeModal(visible = visible, onDismissRequest = onLater) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxWidth().height(274.dp),
        ) { index ->
            ChipTimelineIntroPageContent(ChipTimelineIntroPages[index])
        }
        OpenCodePageIndicator(
            pageCount = ChipTimelineIntroPages.size,
            currentPage = pagerState.currentPage,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 22.dp),
        )
        OpenCodeButton(
            variant = OpenCodeButtonVariant.FullWidthText("Turn on Chip timeline"),
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            ChipTimelineIntroTextAction("Later", onLater)
            ChipTimelineIntroTextAction("Don't show again", onNever)
        }
    }
}

/** Quiet secondary action, styled like onboarding's Skip. */
@Composable
private fun ChipTimelineIntroTextAction(
    title: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(text = title, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChipTimelineIntroPageContent(page: SessionChatChipTimelineIntroPage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeSkeletonIllustration(
            topic = page.illustration,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(184.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(openCodeColor("surface-raised-base"))
                    .padding(top = 20.dp),
        )
        Text(
            text = page.title,
            style = MaterialTheme.typography.titleLarge,
            color = openCodeColor("text-strong"),
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-base"),
        )
    }
}
