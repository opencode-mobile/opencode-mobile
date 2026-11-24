#if DEBUG
import SwiftUI

extension ComponentCaptureRegistry {
    static var foundationsEntries: [ComponentCaptureEntry] {
        [colorsEntry, iconsEntry, fileIconEntry, skeletonIllustrationEntry, typographyEntry]
    }
}

/// Reference pages taller than one screen become several tiles: `default`, `default-2`, ...
private func pagedStates(
    count: Int,
    perPage: Int,
    note: String,
    @ViewBuilder page: @escaping (Range<Int>) -> some View
) -> [ComponentCaptureState] {
    stride(from: 0, to: count, by: perPage).enumerated().map { number, start in
        let range = start ..< min(start + perPage, count)
        return ComponentCaptureState(number == 0 ? "default" : "default-\(number + 1)", note: note) { page(range) }
    }
}

private extension ComponentCaptureRegistry {
    static var colorsEntry: ComponentCaptureEntry {
        let count = colorGalleryTokenNames.count
        return ComponentCaptureEntry(
            componentID: "colors",
            states: pagedStates(count: count, perPage: 5, note: "The native Colors page, 5 tokens per tile") { range in
                ColorGalleryView(tokens: range)
            }
        )
    }

    static var iconsEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "icons",
            states: pagedStates(count: OpenCodeIcon.allCases.count, perPage: 32, note: "The native Icons page, 32 icons per tile") { range in
                IconsGalleryView(icons: range)
            }
        )
    }

    static var fileIconEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(componentID: "file-icon", states: [
            ComponentCaptureState("default", note: "The native File Icon page in colour, without its Colour and Mono choice") {
                FileIconGalleryView(showsModeChoice: false)
            },
        ])
    }

    static var skeletonIllustrationEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "skeleton-illustration",
            states: pagedStates(
                count: OpenCodeSkeletonIllustrationTopic.allCases.count,
                perPage: 3,
                note: "The native Skeleton Illustration page, 3 topics per tile"
            ) { range in
                SkeletonIllustrationGalleryView(topics: range)
            }
        )
    }

    static var typographyEntry: ComponentCaptureEntry {
        ComponentCaptureEntry(
            componentID: "typography",
            states: pagedStates(count: TypographyGalleryView.roleCount, perPage: 4, note: "The native Typography page, 4 roles per tile") { range in
                TypographyGalleryView(roles: range)
            }
        )
    }
}
#endif
