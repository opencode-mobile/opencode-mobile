import SwiftUI

/// The fitted confirmation sheet (the prototype's `newsession` sheet): a centred title
/// (`bodyStrong`), `body` `text2` paragraphs, then Cancel on the left and one `primary` or `danger`
/// button on the right, which shows its loading state while the action runs. Show it with
/// `.hexFittedSheet(isPresented:)`.
struct HexConfirmSheet: View {
    let title: Text
    let paragraphs: [Text]
    let confirmTitle: Text
    var confirmStyle: HexButton.Style = .primary
    var loading = false
    let onCancel: () -> Void
    let onConfirm: () -> Void

    var body: some View {
        HexSheet(title: title, leading: { EmptyView() }, trailing: { EmptyView() }) {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(paragraphs.indices, id: \.self) { index in
                    paragraphs[index]
                        .hexFont(.body)
                        .foregroundStyle(HexColor.text2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: 8) {
                    HexButton(text: Text(LocalizedStringResource("common.cancel", defaultValue: "Cancel")), size: .regular, action: onCancel)
                        .disabled(loading)
                    Spacer(minLength: 8)
                    HexButton(text: confirmTitle, size: .regular, style: confirmStyle, loading: loading, action: onConfirm)
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 2)
            .padding(.bottom, 16)
        }
        .interactiveDismissDisabled(loading)
    }
}
