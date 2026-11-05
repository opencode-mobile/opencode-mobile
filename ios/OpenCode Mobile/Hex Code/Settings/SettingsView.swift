import OpenCodeKit
import SwiftUI
import VoiceEngineKit

/// Settings: a plain list for now. Project, model and appearance work; the rest are placeholders.
/// Version ends App; ten quick taps on it show the Developer section after it.
struct SettingsView: View {
    let main: HexMainModel
    let voice: VoiceEngine
    @Binding var appearance: HexAppearance
    let onForgetServer: () -> Void

    @State private var showsServer = false
    @State private var showsSpeechLab = false

    var body: some View {
        VStack(spacing: 0) {
            HexTopBar(
                LocalizedStringResource("settings.title", defaultValue: "Settings"),
                subtitle: serverSubtitle,
                onBack: { main.back() }
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HexSectionLabel(LocalizedStringResource("settings.session", defaultValue: "Session"))
                    HexRow(
                        icon: .folder,
                        LocalizedStringResource("settings.project", defaultValue: "Project"),
                        subtitle: main.setup.project.path,
                        value: main.setup.project.name
                    ) { Task { await main.openProject() } }
                    HexRow(
                        icon: .refresh,
                        LocalizedStringResource("settings.new_session", defaultValue: "Start a new session"),
                        subtitle: String(localized: "settings.new_session.sub", defaultValue: "Today’s session stays on your computer")
                    ) { main.showPlaceholder() }

                    HexSectionLabel(LocalizedStringResource("settings.agent", defaultValue: "Agent"))
                    HexRow(icon: .cpu, LocalizedStringResource("settings.model", defaultValue: "Model"), value: modelName) {
                        Task { await main.openModel() }
                    }
                    HexRow(icon: .spark, LocalizedStringResource("settings.reasoning", defaultValue: "Reasoning"), value: reasoning) {
                        Task { await main.openModel() }
                    }

                    HexSectionLabel(LocalizedStringResource("settings.dictation", defaultValue: "Dictation · on this phone"))
                    HexRow(
                        icon: .mic,
                        LocalizedStringResource("settings.speech_model", defaultValue: "Speech model"),
                        subtitle: String(localized: "settings.speech_model.sub", defaultValue: "Runs on this phone"),
                        value: voice.modelName
                    ) { main.showPlaceholder() }
                    HexRow(
                        icon: .book,
                        LocalizedStringResource("settings.language", defaultValue: "Language"),
                        value: String(localized: "settings.language.english", defaultValue: "English")
                    ) { main.showPlaceholder() }
                    HexRow(
                        icon: .pencil,
                        LocalizedStringResource("settings.text_style", defaultValue: "Text style"),
                        value: String(localized: "settings.text_style.plain", defaultValue: "Plain")
                    ) { main.showPlaceholder() }

                    HexSectionLabel(LocalizedStringResource("settings.app", defaultValue: "App"))
                    HexRow(icon: .sun, LocalizedStringResource("settings.appearance", defaultValue: "Appearance"), value: appearance.label) {
                        appearance = appearance.next
                    }
                    HexRow(
                        icon: .server,
                        LocalizedStringResource("settings.server", defaultValue: "Server"),
                        subtitle: serverDetail,
                        value: main.serverLabel
                    ) { showsServer = true }
                    HexRow(
                        icon: .info,
                        LocalizedStringResource("settings.version", defaultValue: "Version"),
                        value: main.build.versionLabel,
                        trailing: .none
                    ) { main.tapVersion() }

                    if main.developer.isUnlocked {
                        developerSection
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .hexScreenBackground()
        .hexSheet(isPresented: $showsServer) {
            ServerDetailsView(setup: main.setup, onForget: {
                showsServer = false
                onForgetServer()
            })
        }
        .fullScreenCover(isPresented: $showsSpeechLab) {
            DictationScreen(engine: voice, onClose: { showsSpeechLab = false })
        }
    }

    /// For whoever builds and tests the app. Developer-only, so English and written here, like
    /// OpenCode Mobile's Developer Tools.
    @ViewBuilder
    private var developerSection: some View {
        HexSectionLabel(verbatim: "Developer")
        HexRow(icon: .card, verbatim: "Component gallery", subtitle: "Every Hex component, light and dark") {
            main.openGallery()
        }
        HexRow(icon: .mic, verbatim: "Speech lab", subtitle: "Dictation on its own, with timings") {
            showsSpeechLab = true
        }
        HexRow(
            icon: .term,
            verbatim: "Build",
            subtitle: main.build.commit.map { "Commit \($0)" } ?? "No commit in this build",
            value: main.build.versionLabel,
            trailing: .none
        ) { main.copyBuild() }
        HexRow(
            icon: .play,
            verbatim: "Sample session",
            subtitle: "Home shows a fake session instead of today's",
            value: main.samplePreset?.rawValue ?? "Off",
            trailing: .none
        ) { main.cycleSampleSession() }
        #if DEBUG
        HexRow(icon: .refresh, verbatim: "Drop event stream", subtitle: "Down for 10 s, then it reconnects and reads the state again", trailing: .none) {
            main.realSession.dropStream()
        }
        #endif
        HexRow(icon: .lock, verbatim: "Hide developer settings", trailing: .none) {
            main.developer.hide()
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
}

/// Home, with Settings and the pages it opens pushed from the side on one navigation stack.
/// The pages draw their own top bars, so the system bar is hidden and the edge swipe is kept.
struct HexMainView: View {
    @Bindable var main: HexMainModel
    let voice: VoiceEngine
    @Binding var appearance: HexAppearance
    let onForgetServer: () -> Void

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack(path: $main.path) {
            HomeView(home: main.home, session: main.session, voice: voice, onSettings: { main.openSettings() })
                .hexPage()
                .navigationDestination(for: HexMainModel.Route.self) { route in
                    page(route).hexPage()
                }
        }
        .hexToastHost(main.toasts)
        .task { await main.realSession.open() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: Task { await main.realSession.enteredForeground() }
            case .background: main.realSession.enteredBackground()
            default: break
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
        case .gallery:
            HexGallery(onBack: { main.back() })
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
        HexSheet {
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: setup.serverName)
                    .hexFont(.pageTitle)
                    .foregroundStyle(HexColor.text1)
                    .padding(.top, 8)
                if let detail {
                    Text(verbatim: detail)
                        .hexFont(HexTextStyle.small.weight(.regular))
                        .foregroundStyle(HexColor.text2)
                        .padding(.top, 4)
                }
                HexSectionLabel(LocalizedStringResource("server.addresses", defaultValue: "Addresses"))
                    .padding(.horizontal, -HexSpacing.screen)
                ForEach(addresses, id: \.self) { address in
                    HStack {
                        Text(verbatim: address)
                            .hexFont(HexTextStyle.small.weight(.regular).monospaced())
                            .foregroundStyle(HexColor.text1)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .textSelection(.enabled)
                        Spacer(minLength: 8)
                        if address == inUse {
                            HexBadge(LocalizedStringResource("server.in_use", defaultValue: "in use"))
                        }
                    }
                    .padding(.vertical, 6)
                }
                Spacer()
                HexButton(
                    LocalizedStringResource("settings.forget.button", defaultValue: "Forget this computer"),
                    size: .regular,
                    style: .danger,
                    fullWidth: true
                ) { confirmsForget = true }
                    .padding(.bottom, 8)
            }
            .padding(.horizontal, 2)
        }
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
