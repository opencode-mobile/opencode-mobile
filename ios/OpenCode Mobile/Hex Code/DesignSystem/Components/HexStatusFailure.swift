import SwiftUI

/// The status area's failure layout, shared by "Couldn't open today's session" and "Signed out":
/// a `display` title, an optional `body` `text2` detail (inline code as on the card), and a
/// regular `HexButton` with an icon and a loading state.
struct HexStatusFailure: View {
    let title: Text
    var detail: String?
    let buttonTitle: LocalizedStringResource
    let icon: HexIcon
    var loading = false
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 8) {
                title
                    .hexFont(.display)
                    .foregroundStyle(HexColor.text1)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if let detail {
                    SummaryText(text: detail, style: .body)
                        .foregroundStyle(HexColor.text2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            HexButton(buttonTitle, icon: icon, size: .regular, loading: loading, action: action)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
