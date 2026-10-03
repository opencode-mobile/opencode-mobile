package dev.opencode.mobile.ui.sessionchat.view

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductPresentedScreenViewEffect
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeBottomSheet
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonContrast
import dev.opencode.mobile.ui.components.OpenCodeButtonSize
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
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
 * One-time offer to turn on Chip timeline (OPE-270), in the onboarding carousel
 * style. Swipe, scrim, and back count as Later.
 */
@Composable
internal fun SessionChatChipTimelineIntroSheet(
    visible: Boolean,
    onEnable: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    ProductPresentedScreenViewEffect(
        screenId = ProductAnalytics.Screen.SESSION_CHIP_TIMELINE_INTRO,
        presentation = ProductAnalytics.Presentation.SHEET,
        visible = visible,
    )
    val pagerState = rememberPagerState(pageCount = { ChipTimelineIntroPages.size })
    LaunchedEffect(visible) {
        if (visible) pagerState.scrollToPage(0)
    }

    OpenCodeBottomSheet(
        visible = visible,
        title = "Try Chip timeline",
        onDismissRequest = onLater,
        alwaysFixedHeight = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalPager(
                state = pagerState,
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxWidth().height(196.dp),
            ) { index ->
                ChipTimelineIntroPageContent(ChipTimelineIntroPages[index])
            }
            OpenCodePageIndicator(
                pageCount = ChipTimelineIntroPages.size,
                currentPage = pagerState.currentPage,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.FullWidthText("Turn on Chip timeline"),
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Later"),
                        onClick = onLater,
                        contrast = OpenCodeButtonContrast.Low,
                        size = OpenCodeButtonSize.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Don't show again"),
                        onClick = onNever,
                        contrast = OpenCodeButtonContrast.Low,
                        size = OpenCodeButtonSize.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipTimelineIntroPageContent(page: SessionChatChipTimelineIntroPage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OpenCodeSkeletonIllustration(
            topic = page.illustration,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .clip(RoundedCornerShape(20.dp)),
        )
        Text(
            text = page.title,
            style = MaterialTheme.typography.titleLarge,
            color = openCodeColor("text-strong"),
        )
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = openCodeColor("text-base"),
        )
    }
}
