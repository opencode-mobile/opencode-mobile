import OpenCodeKit
import SwiftUI
import VoiceEngineKit

/// Settings: a plain list for now. Project, model and appearance work; the rest are placeholders.
/// Version ends App and does nothing when tapped; the Developer section always follows it.
struct SettingsView: View {
    @Bindable var main: HexMainModel
    let voice: VoiceEngine
    @Binding var appearance: HexAppearance
    let onForgetServer: () -> Void

    @State private var showsServer = false
    /// Pair again was tapped in Server details: the scan cover opens once the sheet has gone.
    @State private var pairsAgain = false
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
                    ) { main.confirmNewSession() }

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
                    .placeholder()
                    HexRow(
                        icon: .book,
                        LocalizedStringResource("settings.language", defaultValue: "Language"),
                        value: String(localized: "settings.language.english", defaultValue: "English")
                    ) { main.showPlaceholder() }
                    .placeholder()
                    HexRow(
                        icon: .pencil,
                        LocalizedStringResource("settings.text_style", defaultValue: "Text style"),
                        value: String(localized: "settings.text_style.plain", defaultValue: "Plain")
                    ) { main.showPlaceholder() }
                    .placeholder()

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
                    )

                    developerSection
                }
                .padding(.bottom, 24)
            }
        }
        .hexScreenBackground()
        .hexSheet(isPresented: $showsServer, onDismiss: {
            guard pairsAgain else { return }
            pairsAgain = false
            main.startPairAgain()
        }) {
            ServerDetailsView(
                setup: main.setup,
                onDone: { showsServer = false },
                onPairAgain: {
                    pairsAgain = true
                    showsServer = false
                },
                onForget: {
                    showsServer = false
                    onForgetServer()
                }
            )
        }
        .hexFittedSheet(isPresented: $main.showsNewSession) {
            NewSessionSheet(main: main)
        }
        .onChange(of: main.showsNewSession) { _, up in main.toasts.inSheet = up }
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

/// "Start a new session?": what stays, what starts empty, and what carries on in the old one.
struct NewSessionSheet: View {
    let main: HexMainModel

    var body: some View {
        HexConfirmSheet(
            title: Text(LocalizedStringResource("settings.new_session.title", defaultValue: "Start a new session?")),
            paragraphs: paragraphs,
            confirmTitle: Text(LocalizedStringResource("settings.new_session.start", defaultValue: "Start new session")),
            loading: main.isStartingSession,
            onCancel: { main.showsNewSession = false },
            onConfirm: { Task { await main.startNewSession() } }
        )
        .hexToastHost(main.toasts, sheet: true)
    }

    private var paragraphs: [Text] {
        let project = main.setup.project.name
        var lines = [Text(LocalizedStringResource(
            "settings.new_session.body",
            defaultValue: "Today’s session stays on your computer as it is. The new one starts empty in \(project), with the same model. Today’s goal isn’t carried over."
        ))]
        if main.newSessionBusy {
            lines.append(Text(LocalizedStringResource("settings.new_session.busy", defaultValue: "The agent is still working there and carries on.")))
        }
        let waiting = main.newSessionWaiting
        // Plural in the string catalog: "Its waiting message still sends there." for one.
        if waiting > 0 {
            lines.append(Text(LocalizedStringResource("settings.new_session.waiting", defaultValue: "Its \(waiting) waiting messages still send there.")))
        }
        return lines
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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Cold start: home in the first frame, its card area from what's saved on the phone.
    @State private var coldStart: ColdStart
    /// Home has faded in over the launch logo.
    @State private var shown = false

    init(main: HexMainModel, voice: VoiceEngine, appearance: Binding<HexAppearance>, onForgetServer: @escaping () -> Void) {
        self.main = main
        self.voice = voice
        _appearance = appearance
        self.onForgetServer = onForgetServer
        _coldStart = State(initialValue: ColdStart(savedCard: main.session.summary.summary != nil))
    }

    var body: some View {
        NavigationStack(path: $main.path) {
            HomeView(
                home: main.home,
                session: main.session,
                voice: voice,
                onSettings: { main.openSettings() },
                onPairAgain: { main.startPairAgain() },
                cardShown: coldStart.showsCard
            )
                .hexPage()
                .navigationDestination(for: HexMainModel.Route.self) { route in
                    page(route).hexPage()
                }
        }
        // Notices sit under the top bar: home's, or the open page's.
        .hexToastHost(main.toasts, top: main.path.isEmpty ? HexTopBarMetrics.homeToastTop : HexTopBarMetrics.pageToastTop)
        .fullScreenCover(item: $main.pairAgain) { model in
            PairAgainView(model: model, onCancel: { main.cancelPairAgain() })
        }
        // The talk button sits just above the home indicator, where iOS holds a touch back until it
        // knows it isn't a home swipe, so a hold reached the button late. While home is the page on
        // screen it takes bottom-edge touches first; the home swipe then needs a second swipe. Set
        // here, outside the navigation stack, so it reaches the window's root controller.
        .defersSystemGestures(on: main.path.isEmpty ? .bottom : [])
        // Home fades in over the launch logo in its first frame (instant with Reduce Motion): the
        // logo stays behind it, so it is never drawn on top of the content, and home's opaque
        // background hides it once shown.
        .opacity(shown ? 1 : 0)
        .background {
            HexColor.background.ignoresSafeArea()
                .overlay { LaunchLogo() }
        }
        .onAppear {
            withAnimation(HexMotion.animation(HexMotion.fade, reduced: reduceMotion)) { shown = true }
        }
        // Nothing needs the network before home's first frame is on screen.
        .task {
            await NextFrame.wait()
            StartupClock.mark("home shown")
            await main.start()
        }
        .onChange(of: main.session.sessionKnown, initial: true) { _, known in
            guard known, !coldStart.showsCard else { return }
            // No saved card for today's session: the card fades in once the session is known.
            var next = coldStart
            let fades = next.sessionKnown()
            withAnimation(fades ? HexMotion.animation(HexMotion.fade, reduced: reduceMotion) : nil) {
                coldStart = next
            }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                main.home.prepareRecorder()
                Task { await main.realSession.enteredForeground() }
            case .background:
                main.home.releaseRecorder()
                main.realSession.enteredBackground()
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
                    note: LocalizedStringResource("settings.project.note", defaultValue: "Picking one opens today’s session there."),
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

/// The paired server (app plan, "Server details"): its name, hostname, version and addresses, then
/// Pair again and Forget. The only place the app shows an address.
struct ServerDetailsView: View {
    let setup: HexSetup
    let onDone: () -> Void
    let onPairAgain: () -> Void
    let onForget: () -> Void

    @State private var confirmsForget = false

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("settings.server", defaultValue: "Server")),
            leading: { EmptyView() },
            trailing: { HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), action: onDone) }
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: setup.serverName)
                        .hexFont(.pageTitle)
                        .foregroundStyle(HexColor.text1)
                        .accessibilityAddTraits(.isHeader)
                    if let detail {
                        Text(verbatim: detail)
                            .hexFont(.caption)
                            .foregroundStyle(HexColor.text2)
                            .padding(.top, 4)
                    }
                    HexSectionLabel(LocalizedStringResource("server.addresses", defaultValue: "Addresses"))
                        .padding(.horizontal, -HexSpacing.screen)
                    ForEach(addresses, id: \.self) { address in
                        HStack {
                            Text(verbatim: address)
                                .hexFont(.mono)
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
                        .accessibilityElement(children: .combine)
                    }
                    VStack(spacing: 10) {
                        HexButton(
                            LocalizedStringResource("server.pair_again", defaultValue: "Pair again"),
                            icon: .scan,
                            size: .regular,
                            fullWidth: true,
                            action: onPairAgain
                        )
                        HexButton(
                            LocalizedStringResource("settings.forget.button", defaultValue: "Forget this computer"),
                            size: .regular,
                            style: .danger,
                            fullWidth: true
                        ) { confirmsForget = true }
                    }
                    .padding(.top, 24)
                    .padding(.bottom, 8)
                }
                .padding(.horizontal, 2)
                .padding(.top, 4)
            }
        }
        .confirmationDialog(
            Text(LocalizedStringResource("settings.forget.title", defaultValue: "Forget this computer?")),
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

    /// The one in use first.
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
