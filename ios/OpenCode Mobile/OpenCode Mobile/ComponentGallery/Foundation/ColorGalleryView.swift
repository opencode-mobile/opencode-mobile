import SwiftUI

private let tokenNames = [
    "background-base",
    "surface-base",
    "surface-base-hover",
    "surface-raised-base",
    "surface-raised-base-hover",
    "surface-interactive-weak",
    "surface-success-base",
    "surface-critical-base",
    "text-strong",
    "text-base",
    "text-weak",
    "text-weaker",
    "text-interactive-base",
    "border-weak-base",
    "border-weaker-base",
    "icon-base",
    "icon-weak-base",
    "icon-strong-base",
    "button-primary-base",
    "button-secondary-base",
    "interactive",
    "success",
    "warning",
    "error",
    "info",
    "diff-add",
    "diff-delete",
    "syntax-comment",
    "syntax-keyword",
    "syntax-string",
    "syntax-primitive",
    "syntax-property",
    "syntax-type",
    "syntax-variable",
    "syntax-operator",
    "syntax-punctuation",
    "syntax-object",
    "syntax-constant",
    "syntax-critical",
    "syntax-diff-delete",
    "syntax-diff-unknown",
]

struct ColorGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("OpenCode Desktop Tokens (\(ThemeManager.shared.currentTheme.name))")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)

            ForEach(tokenNames, id: \.self) { name in
                ColorTokenRow(tokenName: name)
            }
        }
    }
}

private struct ColorTokenRow: View {
    let tokenName: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tokenName)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textStrong)

            HStack(spacing: 8) {
                let lightHex = ThemeManager.shared.resolvedLight[tokenName]
                let darkHex = ThemeManager.shared.resolvedDark[tokenName]
                ColorSwatch(label: "Light", hex: lightHex)
                ColorSwatch(label: "Dark", hex: darkHex)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct ColorSwatch: View {
    let label: String
    let hex: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(hex: hex))
                .overlay {
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
                }
                .frame(height: 44)
            Text(label)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textBase)
            Text(hex)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textStrong)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    ColorGalleryView()
        .padding()
        .openCodeTheme()
}
