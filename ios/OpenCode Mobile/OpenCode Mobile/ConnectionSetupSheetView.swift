import SwiftUI

struct ConnectionSetupSheetView: View {
    @Bindable var viewModel: ConnectionSetupViewModel
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            connectionField(title: "Server URL", text: $viewModel.baseURL, secure: false)
            connectionField(title: "Username", text: $viewModel.username, secure: false)
            connectionField(title: "Password", text: $viewModel.password, secure: true)

            if let message = viewModel.testResultMessage {
                Text(message)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(
                        viewModel.state == .testSucceeded
                            ? OpenCodeColors.textPrimary
                            : OpenCodeColors.textSecondary
                    )
            }

            OpenCodeButton(variant: .fullWidthText(title: "Test connection")) {
                Task { await viewModel.testConnection() }
            }
            .disabled(viewModel.isTesting || viewModel.isSaving)

            OpenCodeButton(
                variant: .fullWidthText(title: "Save"),
                enabled: viewModel.canSave && !viewModel.isSaving
            ) {
                Task {
                    if await viewModel.saveConnection() {
                        isPresented = false
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .onChange(of: viewModel.baseURL) { _, _ in viewModel.onFieldChanged() }
        .onChange(of: viewModel.username) { _, _ in viewModel.onFieldChanged() }
        .onChange(of: viewModel.password) { _, _ in viewModel.onFieldChanged() }
    }

    private func connectionField(title: String, text: Binding<String>, secure: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textSecondary)
            Group {
                if secure {
                    SecureField(title, text: text)
                } else {
                    TextField(title, text: text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
            .font(OpenCodeTypography.body)
            .foregroundStyle(OpenCodeColors.textPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(OpenCodeColors.surfaceSecondary, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
