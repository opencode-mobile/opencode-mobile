import OpenCodeKit
import SwiftUI
import VoiceEngineKit

/// Where Start lands until today's session arrives (M2): what first run set up, and the
/// speech model's download.
struct MainPlaceholderView: View {
    let setup: HexSetup
    let voice: VoiceEngine
    let onStartOver: () -> Void

    @State private var showsDictation = false
    @State private var confirmsStartOver = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(LocalizedStringResource("main.idle", defaultValue: "Idle"))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(OpenCodeColors.textWeak)
                .frame(height: 44)
                .padding(.top, 4)
            Spacer()
            Text(LocalizedStringResource("main.placeholder.title", defaultValue: "Today’s session starts here"))
                .font(.system(size: 26, weight: .semibold))
                .tracking(-0.6)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(LocalizedStringResource(
                "main.placeholder.body",
                defaultValue: "The session screen comes next. For now, this is what first run set up."
            ))
            .font(.system(size: 15))
            .foregroundStyle(OpenCodeColors.textWeak)
            .padding(.top, 8)
            VStack(spacing: 0) {
                detail(LocalizedStringResource("main.project", defaultValue: "Project"), setup.project.name, setup.project.path)
                detail(LocalizedStringResource("main.model", defaultValue: "Model"), modelLabel, setup.model.providerID)
                detail(
                    LocalizedStringResource("main.server", defaultValue: "Server"),
                    ServerHost.name(setup.profile.baseURL),
                    setup.serverVersion.map { "OpenCode \($0)" }
                )
                detail(LocalizedStringResource("main.speech", defaultValue: "Speech"), voice.modelName, speechStatus)
            }
            .padding(.top, 20)
            Spacer()
            HexPrimaryButton(
                title: LocalizedStringResource("main.try_dictation", defaultValue: "Try dictation"),
                enabled: voice.modelState == .ready,
                action: { showsDictation = true }
            )
            Button {
                confirmsStartOver = true
            } label: {
                Text(LocalizedStringResource("main.start_over", defaultValue: "Pair again"))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .padding(.bottom, 4)
        }
        .padding(.horizontal, HexLayout.edge)
        .background(OpenCodeColors.backgroundBase)
        .sheet(isPresented: $showsDictation) { DictationScreen() }
        .confirmationDialog(
            Text(LocalizedStringResource("main.start_over.title", defaultValue: "Pair again?")),
            isPresented: $confirmsStartOver,
            titleVisibility: .visible
        ) {
            Button(role: .destructive, action: onStartOver) {
                Text(LocalizedStringResource("main.start_over.confirm", defaultValue: "Forget this computer"))
            }
        } message: {
            Text(LocalizedStringResource(
                "main.start_over.message",
                defaultValue: "Hex Code forgets the server, project and model on this phone. Sessions stay on your computer."
            ))
        }
    }

    private var modelLabel: String {
        let name = TitleCaseIdentifier.displayName(for: setup.model.modelID)
        guard let variant = setup.model.variant else { return name }
        return "\(name) · \(TitleCaseIdentifier.displayName(for: variant))"
    }

    private var speechStatus: String {
        switch voice.modelState {
        case .notLoaded:
            String(localized: "main.speech.waiting", defaultValue: "Waiting to download")
        case let .preparing(fraction, _):
            String(localized: "main.speech.preparing", defaultValue: "Getting speech ready · \(Int((fraction * 100).rounded()))%")
        case .ready:
            String(localized: "main.speech.ready", defaultValue: "Ready · runs on this phone")
        case let .failed(message):
            message
        }
    }

    private func detail(_ label: LocalizedStringResource, _ value: String, _ caption: String?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .frame(width: 72, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: value)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(OpenCodeColors.textStrong)
                if let caption {
                    Text(verbatim: caption)
                        .font(.system(size: 12.5))
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 10)
        .overlay(alignment: .bottom) { Divider().overlay(OpenCodeColors.borderWeakBase) }
    }
}
