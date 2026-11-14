import SwiftUI

/// Where a step of a `HexSteps` list is (the prototype's `.fst` classes).
enum HexStepState: Equatable {
    /// Not reached: an empty `text4` ring, the title in `text3`.
    case todo
    /// Under way: a spinner.
    case doing
    /// Under way but waiting on another step (the agent while an approval waits): a dimmed spinner.
    case dimmed
    /// Needs the person: an amber dot with a soft ring, the title in `amber`.
    case warn
    /// Done: a green check.
    case done
    /// Didn't happen: a red disc with ✕, the title in `red`.
    case failed
}

/// One step: its title, an optional detail (`live` for a one-line `mono` status, such as what the
/// agent is doing now) and an optional small button, such as Review.
struct HexStep: Identifiable {
    var id: String
    var state: HexStepState
    var title: String
    var detail: String?
    var live = false
    var action: (title: String, run: () -> Void)?
}

/// A vertical list of steps that ticks through (the prototype's `.fl-steps`): an 18 pt mark per
/// step on a 1 pt `line2` thread, the title in `row` medium, the detail in `caption` `text2`.
struct HexSteps: View {
    let steps: [HexStep]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                row(step, last: index == steps.count - 1)
            }
        }
    }

    private func row(_ step: HexStep, last: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            mark(step.state)
                .frame(width: 18, height: 18)
                .padding(.top, 1)
                .frame(maxHeight: .infinity, alignment: .top)
                .background(alignment: .top) {
                    if !last {
                        Rectangle()
                            .fill(HexColor.line2)
                            .frame(width: 1)
                            .padding(.top, 22)
                            .padding(.bottom, -10)
                    }
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: step.title)
                    .hexFont(step.state == .todo ? .row : HexTextStyle.row.weight(.medium))
                    .foregroundStyle(titleColor(step.state))
                    .fixedSize(horizontal: false, vertical: true)
                if let detail = step.detail {
                    Text(verbatim: detail)
                        .hexFont(step.live ? .mono : .caption)
                        .foregroundStyle(step.live ? HexColor.text3 : step.state == .todo ? HexColor.text4 : HexColor.text2)
                        .lineLimit(step.live ? 1 : nil)
                        .truncationMode(.middle)
                        .fixedSize(horizontal: false, vertical: !step.live)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let action = step.action {
                HexButton(text: Text(verbatim: action.title), action: action.run)
                    .padding(.top, -3)
            }
        }
        .padding(.vertical, 7)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func mark(_ state: HexStepState) -> some View {
        switch state {
        case .todo:
            Circle()
                .strokeBorder(HexColor.text4, lineWidth: 1.5)
                .frame(width: 14, height: 14)
        case .doing:
            HexSpinner(size: 18)
        case .dimmed:
            HexSpinner(size: 18).opacity(0.45)
        case .warn:
            Circle()
                .fill(HexColor.amber)
                .frame(width: 10, height: 10)
                .background(Circle().fill(HexColor.amberSoft).frame(width: 18, height: 18))
        case .done:
            HexIconView(.check, size: 11)
                .foregroundStyle(HexColor.onSolid)
                .frame(width: 18, height: 18)
                .background(HexColor.green, in: Circle())
        case .failed:
            HexIconView(.x, size: 9)
                .foregroundStyle(HexColor.background)
                .frame(width: 18, height: 18)
                .background(HexColor.red, in: Circle())
        }
    }

    private func titleColor(_ state: HexStepState) -> Color {
        switch state {
        case .todo: HexColor.text3
        case .dimmed: HexColor.text2
        case .warn: HexColor.amber
        case .failed: HexColor.red
        case .doing, .done: HexColor.text1
        }
    }
}

/// The one-time hint under the card's tiles (the prototype's `.arthint`): a `compass`, "Open pages
/// like this on your phone" over "Turn on artifacts ›", and ✕. A tap anywhere but ✕ acts.
struct HexArtifactHint: View {
    let title: String
    let action: String
    let closeLabel: Text
    let onTap: () -> Void
    let onClose: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 11, style: .continuous)
        HStack(spacing: 9) {
            Button(action: onTap) {
                HStack(spacing: 9) {
                    HexIconView(.compass, size: 14)
                        .foregroundStyle(HexColor.text3)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: title)
                            .foregroundStyle(HexColor.text1)
                        Text(verbatim: action + " ›")
                            .foregroundStyle(HexColor.text2)
                    }
                    .hexFont(.small)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(HexPressStyle())
            Button(action: onClose) {
                HexIconView(.x, size: 11)
                    .foregroundStyle(HexColor.text3)
                    .frame(width: 30, height: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(HexPressStyle(scale: 0.94))
            .accessibilityLabel(closeLabel)
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .frame(minHeight: 40)
        .background(HexColor.panel, in: shape)
        .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
    }
}
