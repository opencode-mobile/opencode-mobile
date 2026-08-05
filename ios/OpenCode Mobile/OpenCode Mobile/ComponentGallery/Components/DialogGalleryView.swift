import SwiftUI

struct DialogGalleryView: View {
    @State private var isPresented = false
    @State private var showsSubtitle = true
    @State private var showsTextField = false
    @State private var optionsEnabled = true
    @State private var destructivePrimary = true
    @State private var draftTitle = "Importing icons"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Dialog")
                    .font(OpenCodeTypography.button)
                    .foregroundStyle(OpenCodeColors.textStrong)

                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "Subtitle",
                        selected: showsSubtitle,
                        action: { showsSubtitle.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Text field",
                        selected: showsTextField,
                        action: { showsTextField.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Options enabled",
                        selected: optionsEnabled,
                        action: { optionsEnabled.toggle() }
                    )
                    OpenCodeChoiceBadge(
                        label: "Destructive",
                        selected: destructivePrimary && !showsTextField,
                        action: { destructivePrimary.toggle() }
                    )
                }
            }

            OpenCodeButton(
                variant: .fullWidthText(title: "Show dialog"),
                action: { isPresented = true }
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeDialog(
            isPresented: $isPresented,
            title: showsTextField ? "Rename session" : "Clear all data?",
            subtitle: subtitle,
            primaryOption: OpenCodeDialogOption(
                title: showsTextField ? "Save" : "Clear",
                enabled: optionsEnabled,
                destructive: destructivePrimary && !showsTextField,
                action: { isPresented = false }
            ),
            secondaryOption: OpenCodeDialogOption(
                title: "Cancel",
                enabled: optionsEnabled,
                action: { isPresented = false }
            ),
            textField: showsTextField
                ? OpenCodeDialogTextField(
                    text: $draftTitle,
                    hint: "Title",
                    enabled: optionsEnabled
                )
                : nil
        )
    }

    private var subtitle: String? {
        guard showsSubtitle else { return nil }
        return showsTextField
            ? "Choose a short title for this session."
            : "This cannot be undone."
    }
}

#Preview {
    DialogGalleryView()
        .padding(16)
        .openCodeTheme()
}
