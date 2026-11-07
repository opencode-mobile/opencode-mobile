import Observation
import SwiftUI
import UIKit

/// One notice: an icon tone, a title, an optional one-line detail and an optional action, shown
/// for `duration` seconds.
struct HexToastItem: Identifiable, Equatable {
    /// The icon and its colour (`.toast.t-*` in the prototype).
    enum Tone: Equatable, Sendable {
        /// Red stop icon: stopped, hold to stop.
        case stop
        /// Amber info icon: something to know, such as a failed check.
        case info
        /// Green steer icon: done, sent, saved.
        case steer
        /// Sun in `text2`: a new day or a new session.
        case sun
        /// Undo arrow in `text2`: something was taken away (a dropped take, a deleted chunk).
        case undo
    }

    /// A small button at the end of the card, such as Undo.
    struct Action: Equatable {
        var title: String
        var icon: HexIcon?
    }

    let id = UUID()
    var tone: Tone
    var title: String
    var detail: String?
    var action: Action?
    var duration: TimeInterval = 2.4
    /// The duration is a timer of its own (the Now hold's 3 s send delay): a screen reader doesn't
    /// lengthen it.
    var fixedDuration = false

    /// How long it shows: a notice with an action stays twice as long while a screen reader is on.
    func shownDuration(screenReader: Bool) -> TimeInterval {
        screenReader && action != nil && !fixedDuration ? duration * 2 : duration
    }

    /// What VoiceOver announces: the title and the detail.
    var announcement: String {
        [title, detail].compactMap { $0 }.joined(separator: ". ")
    }
}

/// The prototype's `.toast`: a `panel2` card with a `line2` border and a shadow, at the top of the
/// screen. Show one with a `HexToaster` and `.hexToastHost(_:)`.
struct HexToast: View {
    let item: HexToastItem
    var onAction: () -> Void = {}

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        HStack(spacing: 10) {
            HexIconView(icon, size: HexIconSize.regular)
                .foregroundStyle(iconColor)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: item.title)
                    .hexFont(.callout)
                    .foregroundStyle(HexColor.text1)
                    .lineLimit(1)
                if let detail = item.detail {
                    Text(verbatim: detail)
                        .hexFont(.caption)
                        .foregroundStyle(HexColor.text2)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if let action = item.action {
                HexButton(text: Text(verbatim: action.title), icon: action.icon, action: onAction)
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(HexColor.panel2, in: shape)
        .overlay(shape.strokeBorder(HexColor.line2, lineWidth: 1))
        .shadow(color: HexColor.shadow, radius: 16, y: 12)
    }

    private var icon: HexIcon {
        switch item.tone {
        case .stop: .stop
        case .info: .info
        case .steer: .steer
        case .sun: .sun
        case .undo: .undo
        }
    }

    private var iconColor: Color {
        switch item.tone {
        case .stop: HexColor.red
        case .info: HexColor.amber
        case .steer: HexColor.green
        case .sun, .undo: HexColor.text2
        }
    }
}

/// Shows one `HexToastItem` at a time and hides it after its duration. A new one replaces the one
/// showing. Screens put `.hexToastHost(toaster)` on their root; models call `show`.
@MainActor
@Observable
final class HexToaster {
    private(set) var current: HexToastItem?
    /// While true (the stop button is held) no notice shows; one that arrives meanwhile shows after.
    var isHeld = false
    /// A sheet with its own host is up: notices show at the top of the sheet instead of under it.
    var inSheet = false

    @ObservationIgnored private var onAction: (() -> Void)?
    @ObservationIgnored private var timer: Task<Void, Never>?

    init() {}

    /// Shows `item`; `onAction` runs when its action button is tapped.
    func show(_ item: HexToastItem, onAction: (() -> Void)? = nil) {
        current = item
        self.onAction = onAction
        timer?.cancel()
        let id = item.id
        let duration = item.shownDuration(screenReader: UIAccessibility.isVoiceOverRunning)
        AccessibilityNotification.Announcement(item.announcement).post()
        timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(duration))
            guard !Task.isCancelled, self?.current?.id == id else { return }
            self?.dismiss()
        }
    }

    /// The action button was tapped: hide the notice, then run its action.
    func performAction() {
        let action = onAction
        dismiss()
        action?()
    }

    func dismiss() {
        timer?.cancel()
        timer = nil
        current = nil
        onAction = nil
    }
}

extension View {
    /// Shows the toaster's notice over the top of this view, 8 pt under the safe area. A sheet that
    /// sets `inSheet` hosts them itself (`sheet: true`) while it is up, so they aren't hidden under it.
    func hexToastHost(_ toaster: HexToaster, sheet: Bool = false) -> some View {
        modifier(HexToastHost(toaster: toaster, sheet: sheet))
    }
}

private struct HexToastHost: ViewModifier {
    let toaster: HexToaster
    let sheet: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .top) {
                if let item = toaster.current, !toaster.isHeld, toaster.inSheet == sheet {
                    HexToast(item: item, onAction: { toaster.performAction() })
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .id(item.id)
                        // With Reduce Motion it fades only.
                        .transition(HexMotion.transition(.offset(y: -4).combined(with: .opacity), reduced: reduceMotion))
                }
            }
            .animation(HexMotion.ease(HexMotion.fade), value: toaster.current?.id)
            .animation(HexMotion.ease(HexMotion.fade), value: toaster.isHeld)
    }
}
