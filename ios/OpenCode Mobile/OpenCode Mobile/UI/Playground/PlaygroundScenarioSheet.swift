import SwiftUI

extension View {
    /// The scenario picker the playground's chat title opens: scenarios grouped as the issue lists
    /// them, the open one selected, and previous / next in the header to step through them in place.
    func playgroundScenarioSheet(
        isPresented: Binding<Bool>,
        current: PlaygroundScenarioID,
        stepCounts: [PlaygroundScenarioID: Int],
        onSelect: @escaping (PlaygroundScenarioID) -> Void,
        onPrevious: @escaping () -> Void,
        onNext: @escaping () -> Void
    ) -> some View {
        openCodeBottomSheet(
            isPresented: isPresented,
            title: .verbatim(current.label),
            leadingAction: OpenCodeBottomSheetHeaderAction(
                icon: .chevronLeft,
                accessibilityLabel: "Previous scenario",
                accessibilityIdentifier: "playgroundPreviousScenario",
                action: onPrevious
            ),
            trailingAction: OpenCodeBottomSheetHeaderAction(
                icon: .chevronRight,
                accessibilityLabel: "Next scenario",
                accessibilityIdentifier: "playgroundNextScenario",
                action: onNext
            ),
            scrollTargetID: current.rawValue,
            height: .half
        ) {
            ForEach(PlaygroundScenarioGroup.allCases, id: \.self) { group in
                OpenCodeSectionHeader(title: .verbatim(group.title))
                ForEach(PlaygroundScenarioID.allCases.filter { $0.group == group }, id: \.self) { id in
                    let steps = stepCounts[id] ?? 0
                    OpenCodeListItem(
                        title: .verbatim(id.label),
                        subtitle: .verbatim(id.summary),
                        caption: .verbatim(ifPresent: steps > 0 ? "\(steps) steps" : nil),
                        selected: id == current,
                        action: { onSelect(id) }
                    )
                    .id(id.rawValue)
                    .accessibilityIdentifier("playgroundScenario.\(id.rawValue)")
                }
            }
        }
    }
}
