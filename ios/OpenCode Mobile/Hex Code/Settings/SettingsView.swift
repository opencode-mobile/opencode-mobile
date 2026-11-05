import OpenCodeKit
import SwiftUI
import VoiceEngineKit

/// Settings: a plain list for now. Project, model and appearance work; the rest are placeholders.
struct SettingsView: View {
    let main: HexMainModel
    let voice: VoiceEngine
    @Binding var appearance: HexAppearance
    let onForgetServer: () -> Void

    @State private var showsServer = false

    var body: some View {
        VStack(spacing: 0) {
            HexTopBar(
                title: LocalizedStringResource("settings.title", defaultValue: "Settings"),
                subtitle: serverSubtitle,
                onBack: { main.back() }
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HexSectionLabel(title: OpenCodeText(LocalizedStringResource("settings.session", defaultValue: "Session")))
                    row(.folder, LocalizedStringResource("settings.project", defaultValue: "Project"), value: main.setup.project.name, subtitle: main.setup.project.path) {
                        Task { await main.openProject() }
                    }
                    row(.restart, LocalizedStringResource("settings.new_session", defaultValue: "Start a new session"), subtitle: String(
                        localized: "settings.new_session.sub",
                        defaultValue: "Today’s session stays on your computer"
                    )) { main.showPlaceholder() }

                    HexSectionLabel(title: OpenCodeText(LocalizedStringResource("settings.agent", defaultValue: "Agent")))
                    row(.agent, LocalizedStringResource("settings.model", defaultValue: "Model"), value: modelName) { Task { await main.openModel() } }
                    row(.architecture, LocalizedStringResource("settings.reasoning", defaultValue: "Reasoning"), value: reasoning) { Task { await main.openModel() } }

                    HexSectionLabel(title: OpenCodeText(LocalizedStringResource("settings.dictation", defaultValue: "Dictation · on this phone")))
                    row(.mic, LocalizedStringResource("settings.speech_model", defaultValue: "Speech model"), value: voice.modelName, subtitle: String(
                        localized: "settings.speech_model.sub",
                        defaultValue: "Runs on this phone"
                    )) { main.showPlaceholder() }
                    row(.description, LocalizedStringResource("settings.language", defaultValue: "Language"), value: String(localized: "settings.language.english", defaultValue: "English")) {
                        main.showPlaceholder()
                    }
                    row(.edit, LocalizedStringResource("settings.text_style", defaultValue: "Text style"), value: String(localized: "settings.text_style.plain", defaultValue: "Plain")) {
                        main.showPlaceholder()
                    }

                    HexSectionLabel(title: OpenCodeText(LocalizedStringResource("settings.app", defaultValue: "App")))
                    row(.widgets, LocalizedStringResource("settings.appearance", defaultValue: "Appearance"), value: appearance.label) {
                        appearance = appearance.next
                    }
                    row(.cloud, LocalizedStringResource("settings.server", defaultValue: "Server"), value: main.serverLabel, subtitle: serverDetail) {
                        showsServer = true
                    }
                }
            }
            if let note = main.note {
                HomeToastView(toast: note, onUndo: {})
                    .padding(.horizontal, HexLayout.edge)
                    .padding(.bottom, 8)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .animation(.easeOut(duration: 0.2), value: main.note)
        .sheet(isPresented: $showsServer) {
            ServerDetailsView(setup: main.setup, onForget: {
                showsServer = false
                onForgetServer()
            })
            .presentationDetents([.medium, .large])
        }
    }

    /// The hostname and OpenCode version, under the server's name.
    private var serverDetail: String {
        [main.setup.serverHostname, main.setup.serverVersion.map { "OpenCode \($0)" }]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    private var serverSubtitle: String {
        guard let version = main.setup.serverVersion else { return main.serverLabel }
        return "\(main.serverLabel) · OpenCode \(version)"
    }

    private var modelName: String {
        main.setup.modelName ?? TitleCaseIdentifier.displayName(for: main.setup.model.modelID)
    }

    private var reasoning: String {
        main.setup.model.variant.map(TitleCaseIdentifier.displayName(for:))
            ?? String(localized: "settings.reasoning.default", defaultValue: "Default")
    }

    private func row(
        _ icon: OpenCodeIcon,
        _ title: LocalizedStringResource,
        value: String? = nil,
        subtitle: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                OpenCodeIconImage(icon: icon, size: 18)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(OpenCodeColors.textStrong)
                    if let subtitle {
                        Text(verbatim: subtitle)
                            .font(.system(size: 12.5))
                            .foregroundStyle(OpenCodeColors.textWeak)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                Spacer(minLength: 8)
                if let value {
                    Text(verbatim: value)
                        .font(.system(size: 14))
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                }
                OpenCodeIconImage(icon: .chevronRight, size: 14)
                    .foregroundStyle(OpenCodeColors.textWeaker)
            }
            .padding(.horizontal, HexLayout.edge)
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(HexRowStyle())
    }
}

/// Home, with Settings and the pages it opens pushed from the side on one navigation stack.
/// The pages draw their own top bars, so the system bar is hidden and the edge swipe is kept.
struct HexMainView: View {
    @Bindable var main: HexMainModel
    let voice: VoiceEngine
    @Binding var appearance: HexAppearance
    let onForgetServer: () -> Void

    var body: some View {
        NavigationStack(path: $main.path) {
            HomeView(home: main.home, voice: voice, onSettings: { main.openSettings() })
                .hexPage()
                .navigationDestination(for: HexMainModel.Route.self) { route in
                    page(route).hexPage()
                }
        }
    }

    @ViewBuilder
    private func page(_ route: HexMainModel.Route) -> some View {
        switch route {
        case .settings:
            SettingsView(main: main, voice: voice, appearance: $appearance, onForgetServer: onForgetServer)
        case .project:
            if let picker = main.projectPicker {
                ProjectPickerView(
                    picker: picker,
                    title: LocalizedStringResource("settings.project.title", defaultValue: "Change project"),
                    note: LocalizedStringResource("settings.project.note", defaultValue: "The next session starts there."),
                    serverLabel: main.serverLabel,
                    onBack: { main.back() },
                    onPick: { project in Task { await main.pickProject(project) } },
                    onBrowse: { Task { await main.browseFolders() } }
                )
            }
        case .browse:
            if let picker = main.projectPicker {
                FolderBrowserView(
                    picker: picker,
                    onPick: { project in Task { await main.pickProject(project) } },
                    onBack: { main.back() }
                )
                .onDisappear { picker.closeBrowser() }
            }
        case .model:
            if let page = main.modelPage {
                ModelPickerView(
                    page: page,
                    title: LocalizedStringResource("settings.model.title", defaultValue: "Model"),
                    serverLabel: main.serverLabel,
                    finishTitle: LocalizedStringResource("settings.model.save", defaultValue: "Save"),
                    finishing: main.isSaving,
                    finishError: main.saveError,
                    onBack: { main.back() },
                    onFinish: { Task { await main.saveModel() } }
                )
                .onDisappear { page.cancel() }
            }
        }
    }
}

private extension View {
    /// A pushed page that draws its own top bar.
    func hexPage() -> some View {
        toolbar(.hidden, for: .navigationBar)
            .background(SwipeBackGesture())
    }
}

/// The paired server: its name, hostname, version and addresses, and Forget. The only place the
/// app shows an address.
struct ServerDetailsView: View {
    let setup: HexSetup
    let onForget: () -> Void

    @State private var confirmsForget = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: setup.serverName)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(OpenCodeColors.textStrong)
                .padding(.top, 24)
            if let detail {
                Text(verbatim: detail)
                    .font(.system(size: 13))
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .padding(.top, 4)
            }
            HexSectionLabel(title: OpenCodeText(LocalizedStringResource("server.addresses", defaultValue: "Addresses")))
                .padding(.horizontal, -HexLayout.edge)
            ForEach(addresses, id: \.self) { address in
                HStack {
                    Text(verbatim: address)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(OpenCodeColors.textStrong)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .textSelection(.enabled)
                    Spacer(minLength: 8)
                    if address == inUse {
                        OpenCodeTag(OpenCodeText(LocalizedStringResource("server.in_use", defaultValue: "in use")), size: .small)
                    }
                }
                .padding(.vertical, 6)
            }
            Spacer()
            Button {
                confirmsForget = true
            } label: {
                Text(LocalizedStringResource("settings.forget.button", defaultValue: "Forget this computer"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.error)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .padding(.bottom, 8)
        }
        .padding(.horizontal, HexLayout.edge)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(OpenCodeColors.backgroundBase)
        .confirmationDialog(
            Text(LocalizedStringResource("settings.forget.title", defaultValue: "Pair again?")),
            isPresented: $confirmsForget,
            titleVisibility: .visible
        ) {
            Button(role: .destructive, action: onForget) {
                Text(LocalizedStringResource("settings.forget.confirm", defaultValue: "Forget this computer"))
            }
        } message: {
            Text(LocalizedStringResource(
                "settings.forget.message",
                defaultValue: "Hex Code forgets the server, project and model on this phone. Sessions stay on your computer."
            ))
        }
    }

    private var detail: String? {
        let parts = [setup.serverHostname, setup.serverVersion.map { "OpenCode \($0)" }].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private var inUse: String { trimmed(setup.profile.baseURL) }

    private var addresses: [String] {
        var seen = Set<String>()
        return ([setup.profile.baseURL] + setup.profile.serverURLs).map(trimmed).filter { seen.insert($0).inserted }
    }

    private func trimmed(_ address: String) -> String {
        var address = address
        while address.hasSuffix("/") { address.removeLast() }
        return address
    }
}
