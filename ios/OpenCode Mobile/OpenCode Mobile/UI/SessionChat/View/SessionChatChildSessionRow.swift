import SwiftUI

struct SessionChatChildSessionRow: View {
    let agentName: String
    let letter: String
    let isBackground: Bool
    let secondLine: String?
    let timeStartEpochMs: Int64?
    let showsChevron: Bool
    let action: (() -> Void)?

    var body: some View {
        if timeStartEpochMs != nil {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                rowContent(nowEpochMs: Int64(context.date.timeIntervalSince1970 * 1000))
            }
        } else {
            rowContent(nowEpochMs: 0)
        }
    }

    @ViewBuilder
    private func rowContent(nowEpochMs: Int64) -> some View {
        let elapsed = SessionChatChildSessionElapsed.format(
            startEpochMs: timeStartEpochMs,
            nowEpochMs: nowEpochMs
        )
        let label = accessibilityLabel(elapsed: elapsed)

        if let action {
            Button(action: action) {
                visualContent(elapsed: elapsed)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(label)
        } else {
            visualContent(elapsed: elapsed)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(label)
        }
    }

    private func visualContent(elapsed: String?) -> some View {
        HStack(alignment: .center, spacing: 12) {
            letterAvatar
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(agentName)
                        .font(OpenCodeTypography.heading)
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                    if isBackground {
                        Text("background")
                            .font(OpenCodeTypography.caption)
                            .foregroundStyle(OpenCodeColors.textWeaker)
                            .lineLimit(1)
                    }
                }
                if let secondLine, !secondLine.isEmpty {
                    Text(secondLine)
                        .font(OpenCodeTypography.caption)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                if let elapsed {
                    Text(elapsed)
                        .font(OpenCodeTypography.caption.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.textWeaker)
                }
                if showsChevron {
                    OpenCodeIconImage(icon: .chevronRight, size: 16)
                        .foregroundStyle(OpenCodeColors.iconBase)
                        .accessibilityHidden(true)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(OpenCodeColors.surfaceBase)
                .overlay(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                )
        )
    }

    private var letterAvatar: some View {
        Text(letter)
            .font(.system(size: 14, weight: .bold, design: .rounded))
            .foregroundStyle(OpenCodeColors.textStrong)
            .frame(width: 32, height: 32)
            .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
            .accessibilityHidden(true)
    }

    private func accessibilityLabel(elapsed: String?) -> String {
        var parts = [agentName]
        if isBackground { parts.append("background") }
        if let secondLine, !secondLine.isEmpty { parts.append(secondLine) }
        if let elapsed { parts.append(elapsed) }
        return parts.joined(separator: ", ")
    }
}
