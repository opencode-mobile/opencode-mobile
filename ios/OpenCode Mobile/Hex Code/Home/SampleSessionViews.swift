import SwiftUI

/// The playground's strip under the top bar: it says home shows a sample, with Leave. Feature-local,
/// in the waiting line's panel style.
struct SampleStrip: View {
    let onLeave: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous)
        HStack(spacing: 8) {
            HexIconView(.play, size: HexIconSize.small)
                .foregroundStyle(HexColor.text3)
                .accessibilityHidden(true)
            Text(LocalizedStringResource("sample.banner", defaultValue: "Sample session — not connected to a computer"))
                .hexFont(HexTextStyle.small.weight(.regular))
                .foregroundStyle(HexColor.text2)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            HexButton(LocalizedStringResource("sample.leave", defaultValue: "Leave"), action: onLeave)
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .background(HexColor.panel, in: shape)
        .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }
}

/// The playground's suggestion over the dock: a tap sends that take, so the whole sample works
/// with no speech model and no microphone.
struct SampleSuggestion: View {
    let take: String
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous)
        Button(action: action) {
            HStack(spacing: 8) {
                HexIconView(.send, size: HexIconSize.small)
                    .foregroundStyle(HexColor.text3)
                Text(LocalizedStringResource("sample.try", defaultValue: "Try: “\(take)”"))
                    .hexFont(HexTextStyle.small.weight(.regular))
                    .foregroundStyle(HexColor.text1)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(HexColor.panel, in: shape)
            .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(HexPressStyle())
        .accessibilityHint(Text(LocalizedStringResource("sample.try.hint", defaultValue: "Sends it to the sample agent")))
    }
}
