import SwiftUI

private struct ColorToken: Identifiable {
    let id: String
    let light: Color
    let lightHex: String
    let dark: Color
    let darkHex: String
}

private let colorTokens = [
    ColorToken(id: "background.primary", light: hex(0xF8F8F8), lightHex: "#F8F8F8", dark: hex(0x121212), darkHex: "#121212"),
    ColorToken(id: "background.secondary", light: hex(0xF3F3F3), lightHex: "#F3F3F3", dark: hex(0x191919), darkHex: "#191919"),
    ColorToken(id: "background.elevated", light: hex(0xFCFCFC), lightHex: "#FCFCFC", dark: hex(0x151515), darkHex: "#151515"),
    ColorToken(id: "surface.primary", light: hex(0xFFFFFF), lightHex: "#FFFFFF", dark: hex(0x1C1C1C), darkHex: "#1C1C1C"),
    ColorToken(id: "surface.secondary", light: hex(0xF3F3F3), lightHex: "#F3F3F3", dark: hex(0x282727), darkHex: "#282727"),
    ColorToken(id: "surface.inset", light: hex(0xF0F0F0), lightHex: "#F0F0F0", dark: hex(0x202020), darkHex: "#202020"),
    ColorToken(id: "text.primary", light: hex(0x171717), lightHex: "#171717", dark: hex(0xEDEDED), darkHex: "#EDEDED"),
    ColorToken(id: "text.secondary", light: hex(0x6F6F6F), lightHex: "#6F6F6F", dark: hex(0xA0A0A0), darkHex: "#A0A0A0"),
    ColorToken(id: "text.tertiary", light: hex(0x8F8F8F), lightHex: "#8F8F8F", dark: hex(0x707070), darkHex: "#707070"),
    ColorToken(id: "text.disabled", light: hex(0xC7C7C7), lightHex: "#C7C7C7", dark: hex(0x505050), darkHex: "#505050"),
    ColorToken(id: "text.interactive", light: hex(0x034CFF), lightHex: "#034CFF", dark: hex(0xC0D4FB), darkHex: "#C0D4FB"),
    ColorToken(id: "border.subtle", light: hex(0xE5E5E5), lightHex: "#E5E5E5", dark: hex(0x282828), darkHex: "#282828"),
    ColorToken(id: "border.default", light: hex(0x000000, alpha: 0.16), lightHex: "#00000029", dark: hex(0x4D4C4A), darkHex: "#4D4C4A"),
    ColorToken(id: "icon.default", light: hex(0x8F8F8F), lightHex: "#8F8F8F", dark: hex(0x7E7E7E), darkHex: "#7E7E7E"),
    ColorToken(id: "icon.strong", light: hex(0x171717), lightHex: "#171717", dark: hex(0xEDE8E4), darkHex: "#EDE8E4"),
    ColorToken(id: "interactive.accent", light: hex(0x034CFF), lightHex: "#034CFF", dark: hex(0x1456F7), darkHex: "#1456F7"),
    ColorToken(id: "status.success", light: hex(0x12C905), lightHex: "#12C905", dark: hex(0x089B00), darkHex: "#089B00"),
    ColorToken(id: "status.warning", light: hex(0xFBDD46), lightHex: "#FBDD46", dark: hex(0xC78B05), darkHex: "#C78B05"),
    ColorToken(id: "status.error", light: hex(0xFC533A), lightHex: "#FC533A", dark: hex(0xCB321C), darkHex: "#CB321C"),
    ColorToken(id: "status.info", light: hex(0xA753AE), lightHex: "#A753AE", dark: hex(0xCD73D5), darkHex: "#CD73D5"),
]

struct ColorGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Semantic Tokens")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textPrimary)

            ForEach(colorTokens) { token in
                ColorTokenRow(token: token)
            }
        }
    }
}

private struct ColorTokenRow: View {
    let token: ColorToken

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("color.\(token.id)")
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textPrimary)

            HStack(spacing: 8) {
                ColorSwatch(label: "Light", color: token.light, hex: token.lightHex)
                ColorSwatch(label: "Dark", color: token.dark, hex: token.darkHex)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfacePrimary, in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct ColorSwatch: View {
    let label: String
    let color: Color
    let hex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            RoundedRectangle(cornerRadius: 8)
                .fill(color)
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(OpenCodeColors.borderSubtle, lineWidth: 1)
                }
                .frame(height: 44)
            Text(label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
            Text(hex)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private func hex(_ rgb: UInt32, alpha: Double = 1) -> Color {
    Color(
        red: Double((rgb >> 16) & 0xFF) / 255,
        green: Double((rgb >> 8) & 0xFF) / 255,
        blue: Double(rgb & 0xFF) / 255,
        opacity: alpha
    )
}

#Preview {
    ColorGalleryView()
        .padding()
        .openCodeTheme()
}
