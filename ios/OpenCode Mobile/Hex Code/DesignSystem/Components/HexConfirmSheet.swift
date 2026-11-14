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
                // Side by side; stacked, the action first, when they don't fit (large text).
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) {
                        cancel.fixedSize()
                        Spacer(minLength: 8)
                        confirm.fixedSize()
                    }
                    VStack(spacing: 8) {
                        confirm
                        cancel
                    }
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 2)
            .padding(.bottom, 16)
        }
        .interactiveDismissDisabled(loading)
    }

    private var cancel: some View {
        HexButton(text: Text(LocalizedStringResource("common.cancel", defaultValue: "Cancel")), size: .regular, fullWidth: true, action: onCancel)
            .disabled(loading)
    }

    private var confirm: some View {
        HexButton(text: confirmTitle, size: .regular, style: confirmStyle, loading: loading, fullWidth: true, action: onConfirm)
    }
}
