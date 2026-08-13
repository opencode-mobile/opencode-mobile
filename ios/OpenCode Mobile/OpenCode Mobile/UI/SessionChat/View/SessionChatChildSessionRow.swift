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
        Group {
            if timeStartEpochMs != nil {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    rowContent(nowEpochMs: Int64(context.date.timeIntervalSince1970 * 1000))
                }
            } else {
                rowContent(nowEpochMs: 0)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(action == nil ? [] : .isButton)
    }

    @ViewBuilder
    private func rowContent(nowEpochMs: Int64) -> some View {
        let elapsed = SessionChatChildSessionElapsed.format(
            startEpochMs: timeStartEpochMs,
            nowEpochMs: nowEpochMs
        )
        let content = HStack(alignment: .center, spacing: 12) {
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

        if let action {
            Button(action: action) { content }
                .buttonStyle(.plain)
        } else {
            content
        }
    }

    private var letterAvatar: some View {
        Text(letter)
            .font(.system(size: 14, weight: .bold, design: .rounded))
            .foregroundStyle(OpenCodeColors.textStrong)
            .frame(width: 32, height: 32)
            .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
    }

    private var accessibilityLabel: String {
        var parts = [agentName]
        if isBackground { parts.append("background") }
        if let secondLine, !secondLine.isEmpty { parts.append(secondLine) }
        if let elapsed = SessionChatChildSessionElapsed.format(
            startEpochMs: timeStartEpochMs,
            nowEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        ) {
            parts.append(elapsed)
        }
        return parts.joined(separator: ", ")
    }
}
