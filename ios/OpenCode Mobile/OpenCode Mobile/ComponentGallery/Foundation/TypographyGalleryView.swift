import SwiftUI

private struct TypeRole: Identifiable {
    let id: String
    let sample: String
    let font: Font
    let isCode: Bool
}

private let typeRoles = [
    TypeRole(id: "type.display", sample: "Build anything", font: OpenCodeTypography.display, isCode: false),
    TypeRole(id: "type.title", sample: "Session Review", font: OpenCodeTypography.title, isCode: false),
    TypeRole(id: "type.heading", sample: "Permission Request", font: OpenCodeTypography.heading, isCode: false),
    TypeRole(id: "type.body", sample: "Read session messages and send prompts from mobile.", font: OpenCodeTypography.body, isCode: false),
    TypeRole(id: "type.bodySecondary", sample: "Foreground updates refresh while the app is open.", font: OpenCodeTypography.bodySecondary, isCode: false),
    TypeRole(id: "type.caption", sample: "Last updated just now", font: OpenCodeTypography.caption, isCode: false),
    TypeRole(id: "type.code", sample: "POST /session/{id}/message", font: OpenCodeTypography.code, isCode: true),
    TypeRole(id: "type.button", sample: "Approve", font: OpenCodeTypography.button, isCode: false),
]

struct TypographyGalleryView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Type Roles")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)

            ForEach(typeRoles) { role in
                TypeRoleSample(role: role)
            }
        }
    }
}

private struct TypeRoleSample: View {
    let role: TypeRole

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(role.id)
                .font(OpenCodeTypography.button)
                .foregroundStyle(OpenCodeColors.textBase)

            Text(role.sample)
                .font(role.font)
                .foregroundStyle(OpenCodeColors.textStrong)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(role.isCode ? 10 : 0)
                .background(role.isCode ? OpenCodeColors.surfaceRaisedBase : Color.clear, in: RoundedRectangle(cornerRadius: 8))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 12))
    }
}

#Preview {
    TypographyGalleryView()
        .padding()
        .openCodeTheme()
}
