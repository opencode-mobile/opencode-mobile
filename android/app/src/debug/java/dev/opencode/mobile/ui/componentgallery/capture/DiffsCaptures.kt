package dev.opencode.mobile.ui.componentgallery.capture

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ui.componentgallery.components.CodeFileGallerySample
import dev.opencode.mobile.ui.componentgallery.components.CodeFileSamples
import dev.opencode.mobile.ui.componentgallery.components.ReviewChangesPillGallerySummary
import dev.opencode.mobile.ui.componentgallery.components.unifiedDiffGalleryFiles
import dev.opencode.mobile.ui.components.OpenCodeDiffStat
import dev.opencode.mobile.ui.components.OpenCodeDiffStatSeparator
import dev.opencode.mobile.ui.components.OpenCodeDiffStatStyle
import dev.opencode.mobile.ui.components.OpenCodeHunkNavigationPill
import dev.opencode.mobile.ui.components.OpenCodeReviewChangesPill
import dev.opencode.mobile.ui.components.OpenCodeReviewChangesPillVariant
import dev.opencode.mobile.ui.components.OpenCodeUnifiedDiff
import dev.opencode.mobile.ui.components.UnifiedDiffContextMode
import dev.opencode.mobile.ui.components.UnifiedDiffParser

internal fun diffsCaptures(): List<ComponentCaptureComponent> =
    listOf(
        diffStatCaptures(),
        hunkNavigationPillCaptures(),
        reviewChangesPillCaptures(),
        unifiedDiffCaptures(),
        codeFileCaptures(),
    )

private fun diffStatCaptures() =
    ComponentCaptureComponent(
        id = "diff-stat",
        states =
            listOf(
                ComponentCaptureState("mixed") { OpenCodeDiffStat(additions = 12, deletions = 3) },
                ComponentCaptureState("additions-only") { OpenCodeDiffStat(additions = 12) },
                ComponentCaptureState("deletions-only") { OpenCodeDiffStat(deletions = 3) },
                ComponentCaptureState("zeros-shown") {
                    OpenCodeDiffStat(additions = 0, deletions = 0, separator = OpenCodeDiffStatSeparator.Split)
                },
                ComponentCaptureState("disabled") {
                    OpenCodeDiffStat(additions = 12, deletions = 3, enabled = false, style = OpenCodeDiffStatStyle.Button)
                },
                ComponentCaptureState("file-row") {
                    OpenCodeDiffStat(
                        additions = 8,
                        deletions = 2,
                        style = OpenCodeDiffStatStyle.Label,
                        separator = OpenCodeDiffStatSeparator.Split,
                    )
                },
            ),
    )

private fun hunkNavigationPillCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        previous: Boolean = true,
        next: Boolean = true,
        context: Boolean = true,
        expanded: Boolean = false,
        press: CapturePress? = null,
    ) = ComponentCaptureState(id, press = press) {
        OpenCodeHunkNavigationPill(
            previousEnabled = previous,
            nextEnabled = next,
            contextEnabled = context,
            contextExpanded = expanded,
            onPrevious = {},
            onToggleContext = {},
            onNext = {},
            modifier = Modifier.testTag(CAPTURE_PRESS_TAG),
        )
    }
    return ComponentCaptureComponent(
        id = "hunk-navigation-pill",
        alignment = Alignment.Center,
        states =
            listOf(
                state("middle"),
                state("first", previous = false),
                state("last", next = false),
                state("expanded", expanded = true),
                state("no-context", context = false),
                state("previous-pressed", press = CapturePress(fromStart = 22.dp)),
                state("context-pressed", press = CapturePress()),
                state("next-pressed", press = CapturePress(fromEnd = 22.dp)),
            ),
    )
}

private fun reviewChangesPillCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        summary: ReviewChangesPillGallerySummary,
        enabled: Boolean = true,
        pressed: Boolean = false,
        note: String? = null,
    ) = ComponentCaptureState(id, press = if (pressed) CapturePress() else null, note = note) {
        OpenCodeReviewChangesPill(
            variant = OpenCodeReviewChangesPillVariant.LatestTurn,
            changedFileCount = summary.changedFileCount,
            additions = summary.additions,
            deletions = summary.deletions,
            enabled = enabled,
            onClick = {},
        )
    }
    return ComponentCaptureComponent(
        id = "review-changes-pill",
        alignment = Alignment.Center,
        states =
            listOf(
                state("mixed", ReviewChangesPillGallerySummary.Mixed),
                state("additions-only", ReviewChangesPillGallerySummary.AdditionsOnly),
                state("deletions-only", ReviewChangesPillGallerySummary.DeletionsOnly),
                state("zero-lines", ReviewChangesPillGallerySummary.ZeroLine),
                state("1-file", ReviewChangesPillGallerySummary.Singular),
                state("pressed", ReviewChangesPillGallerySummary.Mixed, pressed = true),
                state("disabled", ReviewChangesPillGallerySummary.Mixed, enabled = false),
                state("disabled-zero-lines", ReviewChangesPillGallerySummary.ZeroLine, enabled = false),
            ),
    )
}

private fun unifiedDiffCaptures(): ComponentCaptureComponent {
    fun state(
        id: String,
        sample: Int,
        expanded: Boolean = false,
        selected: Boolean = true,
    ) = ComponentCaptureState(id) {
        val file = checkNotNull(unifiedDiffGalleryFiles[sample])
        OpenCodeUnifiedDiff(
            file = file,
            contextMode = if (expanded) UnifiedDiffContextMode.Expanded else UnifiedDiffContextMode.Collapsed,
            selectedHunkId = if (selected) 0 else null,
            document = UnifiedDiffParser.parse(file.patch.orEmpty()),
        )
    }
    return ComponentCaptureComponent(
        id = "unified-diff",
        states =
            listOf(
                state("modified", 0),
                state("expanded-context", 0, expanded = true),
                state("no-selected-hunk", 0, selected = false),
                state("added", 1),
                state("deleted", 2),
            ),
        skipped =
            listOf(
                ComponentCaptureSkip("large-line-numbers", "The Android gallery has no large line number sample"),
                ComponentCaptureSkip("unavailable", "Android Unified Diff has no unavailable state; the Changes screen shows plain text"),
            ),
    )
}

private fun codeFileCaptures(): ComponentCaptureComponent {
    val source = CodeFileSamples.indexOfFirst { it.second == "kotlin" }
    val json = CodeFileSamples.indexOfFirst { it.second == "json" }
    val plain = CodeFileSamples.indexOfFirst { it.second == null }

    fun state(
        id: String,
        sample: Int = source,
        wrap: Boolean = false,
        marked: Boolean = false,
        highlighted: Boolean = true,
    ) = ComponentCaptureState(id) {
        CodeFileGallerySample(sample = sample, wrap = wrap, marked = marked, highlighted = highlighted)
    }
    return ComponentCaptureComponent(
        id = "code-file",
        states =
            listOf(
                state("highlighted"),
                state("wrapped", wrap = true),
                state("marked-ranges", marked = true),
                state("not-highlighted", highlighted = false),
                state("json", sample = json),
                state("plain-text", sample = plain, highlighted = false),
            ),
    )
}
