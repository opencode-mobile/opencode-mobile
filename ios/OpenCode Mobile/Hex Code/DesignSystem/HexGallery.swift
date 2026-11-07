import SwiftUI

/// Every piece of Hex's design system in its states: the colours, type steps and icons, and each
/// component in `DesignSystem/Components/`. Settings' Developer section pushes it, in every build;
/// `-HexCodeGallery YES` opens it on its own (debug builds). The Light / Dark switch applies to this
/// page only. Developer-only, so its text is English and written here.
struct HexGallery: View {
    /// Nil when the gallery is the whole app (the launch argument): no back button.
    var onBack: (() -> Void)?

    @Environment(\.colorScheme) private var appScheme
    /// Nil follows the app.
    @State private var scheme: ColorScheme?
    @State private var showsSheet = false
    @State private var toasts = HexToaster()

    var body: some View {
        let shown = scheme ?? appScheme
        VStack(spacing: 0) {
            HexTopBar(verbatim: "Component gallery", onBack: onBack)
            HexSegmented(
                [ColorScheme.light, .dark],
                selection: Binding(get: { shown }, set: { scheme = $0 })
            ) { $0 == .dark ? "Dark" : "Light" }
                .padding(.horizontal, HexSpacing.screen)
                .padding(.bottom, 6)
            ScrollView {
                HexGalleryContent(showsSheet: $showsSheet, toasts: toasts)
            }
        }
        .hexScreenBackground()
        .hexToastHost(toasts)
        // Inside the colour scheme override, so the sheet and its background take it too.
        .hexSheet(isPresented: $showsSheet) {
            HexGallerySheet(onClose: { showsSheet = false })
        }
        .environment(\.colorScheme, shown)
    }
}

private struct HexGalleryContent: View {
    @Binding var showsSheet: Bool
    let toasts: HexToaster

    @State private var level = "high"
    @State private var query = ""
    @State private var path = "~/Projects"
    @State private var name = ""
    @State private var selectedChunk: UUID?

    private let chunks: [HexChunkItem] = [
        HexChunkItem(id: UUID(), text: "Rename the login screen's helper to session client,"),
        HexChunkItem(id: UUID(), text: "then run the auth tests"),
        HexChunkItem(id: UUID(), text: "Transcribing…", kind: .placeholder),
    ]
    private let failedChunks: [HexChunkItem] = [
        HexChunkItem(id: UUID(), text: "Open the settings screen"),
        HexChunkItem(id: UUID(), text: "Couldn't transcribe this take", kind: .failed),
        HexChunkItem(id: UUID(), text: "Listening…", kind: .placeholder),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            colours
            type
            icons
            topBars
            buttons
            marks
            rows
            fields
            draft
            thumbnails
            talkButton
            holdHints
            notices
            progress
            inlineCode
            linkTiles
            surfaces
            options
            codeBlocks
            stopHold
            requestSheets
            details
            menus
            goals
            askMessages
        }
        .padding(.bottom, 40)
    }

    // MARK: Tokens

    private var colours: some View {
        section("Colours") {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10, alignment: .top), count: 4), spacing: 12) {
                ForEach(Self.palette.indices, id: \.self) { index in
                    let (name, color) = Self.palette[index]
                    VStack(spacing: 4) {
                        RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous)
                            .fill(color)
                            .frame(height: 36)
                            .overlay(
                                RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous)
                                    .strokeBorder(HexColor.line2, lineWidth: 1)
                            )
                        caption(name)
                    }
                }
            }
        }
    }

    private static let palette: [(String, Color)] = [
        ("background", HexColor.background), ("panel", HexColor.panel), ("panel2", HexColor.panel2),
        ("panel3", HexColor.panel3), ("line", HexColor.line), ("line2", HexColor.line2), ("hover", HexColor.hover),
        ("text1", HexColor.text1), ("text2", HexColor.text2), ("text3", HexColor.text3), ("text4", HexColor.text4),
        ("green", HexColor.green), ("amber", HexColor.amber), ("amberSoft", HexColor.amberSoft), ("amberLine", HexColor.amberLine), ("red", HexColor.red),
        ("redSoft", HexColor.redSoft), ("indigo", HexColor.indigo), ("solid", HexColor.solid), ("onSolid", HexColor.onSolid),
        ("scrim", HexColor.scrim), ("stopBg", HexColor.stopBackground), ("shadow", HexColor.shadow),
        ("tint(4.5)", HexColor.tint(4.5)), ("tint(15)", HexColor.tint(15)),
    ]

    private var type: some View {
        section("Type") {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Self.typeScale.indices, id: \.self) { index in
                    let (name, style) = Self.typeScale[index]
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        caption(name)
                            .frame(width: 86, alignment: .leading)
                        Text(verbatim: "Run the tests 0:07")
                            .hexFont(style)
                            .foregroundStyle(HexColor.text1)
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                    }
                }
            }
        }
    }

    private static let typeScale: [(String, HexTextStyle)] = [
        ("display", .display), ("nowLarge", .nowLarge), ("nowMedium", .nowMedium), ("nowSmall", .nowSmall),
        ("nowFit", .nowFit), ("title", .title), ("pageTitle", .pageTitle), ("draft", .draft),
        ("headline", .headline), ("body", .body), ("bodyStrong", .bodyStrong), ("row", .row),
        ("button", .button), ("callout", .callout), ("small", .small), ("caption", .caption),
        ("footnote", .footnote), ("sectionLabel", .sectionLabel), ("badge", .badge), ("mono", .mono),
        ("monoSmall", .monoSmall), ("code", .code),
    ]

    private var icons: some View {
        section("Icons") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 52), spacing: 8)], spacing: 12) {
                ForEach(HexIcon.allCases, id: \.self) { icon in
                    VStack(spacing: 5) {
                        HexIconView(icon, size: HexIconSize.large)
                            .foregroundStyle(HexColor.text1)
                        caption(icon.rawValue)
                    }
                }
            }
            HStack(spacing: 16) {
                ForEach([HexIconSize.small, HexIconSize.regular, HexIconSize.large], id: \.self) { size in
                    HexIconView(.mic, size: size).foregroundStyle(HexColor.text2)
                }
                caption("small · regular · large")
            }
            HStack(spacing: 16) {
                ForEach(HexTodoIcon.allCases, id: \.self) { icon in
                    VStack(spacing: 5) {
                        HexTodoIconView(icon)
                        caption(icon.rawValue)
                    }
                }
            }
        }
    }

    // MARK: Components

    private var topBars: some View {
        VStack(alignment: .leading, spacing: 0) {
            HexSectionLabel(verbatim: "Top bar")
            Group {
                HexTopBar(state: HexTopBarState(tone: .idle, label: "Ready")) {
                    HexRoundButton(.target, label: "Goal") {}
                    HexRoundButton(.gear, label: "Settings") {}
                }
                HexTopBar(state: HexTopBarState(tone: .working, label: "Working", timer: "4:12")) {
                    HexRoundButton(.target, label: "Goal", on: true) {}
                    HexRoundButton(.gear, label: "Settings") {}
                }
                HexTopBar(state: HexTopBarState(tone: .needsYou, label: "Needs input"), onTitle: {}) {}
                HexTopBar(state: HexTopBarState(tone: .reconnecting, label: "Reconnecting…")) {}
                HexTopBar(state: nil) {
                    HexRoundButton(.gear, label: "Settings") {}
                }
            }
            .padding(.horizontal, HexSpacing.screenHome)
            HexTopBar(verbatim: "Pick a model", subtitle: "Spry Seal · 12 models from OpenCode", onBack: {})
            HexTopBar(verbatim: "Reconnecting", subtitle: "Spry Seal · offline", dot: .amber, onBack: {}) {
                HexButton(icon: .refresh) {}
            }
            HexTopBar(verbatim: "No subtitle", onBack: nil)
        }
    }

    private var buttons: some View {
        section("Buttons") {
            HexPrimaryButton(verbatim: "Start") {}
            HexPrimaryButton(verbatim: "Testing…", loading: true) {}
            HexPrimaryButton(verbatim: "Pick a folder", enabled: false) {}
            HStack(spacing: 8) {
                HexButton("Delete", icon: .trash, style: .danger) {}
                HexButton("Re-record", icon: .mic) {}
                HexButton(icon: .x) {}
                HexButton("Send", icon: .send, style: .primary) {}
            }
            HStack(spacing: 8) {
                HexButton("Off", icon: .mic, enabled: false) {}
                HexButton("Off", style: .primary, enabled: false) {}
                HexButton(icon: .send, size: .field) {}
                HexButton(icon: .send, size: .field, enabled: false) {}
            }
            HStack(spacing: 8) {
                HexButton("Try again", icon: .refresh, size: .regular) {}
                HexButton("Try again", icon: .refresh, size: .regular, loading: true) {}
            }
            HStack(spacing: 8) {
                HexButton("Cancel", size: .regular) {}
                HexButton("Allow", size: .regular, style: .primary) {}
                HexButton("Deny", size: .regular, style: .danger) {}
            }
            HexButton("Forget this computer", size: .regular, style: .danger, fullWidth: true) {}
            HStack(spacing: 8) {
                HexRoundButton(.chevL, label: "Back") {}
                HexRoundButton(.target, label: "Goal", on: true) {}
                HexIconButton(.clip, label: "Attach") {}
                HexIconButton(.clip, label: "Attach", enabled: false) {}
                HexIconButton(.stop, label: "Stop", tone: .stop) {}
                HexIconButton(.stop, label: "Stop", tone: .stop, enabled: false) {}
                HexIconButton(.check, label: "Done", tone: .strong) {}
            }
            caption("Press styles: HexPressStyle (buttons), HexRowPressStyle (rows and icon buttons)")
        }
    }

    private var marks: some View {
        section("Dots, spinners, badges, status lines") {
            HStack(spacing: 12) {
                HexDot()
                HexDot(tone: .green)
                HexDot(tone: .amber)
                HexDot(tone: .red)
                HexDot(tone: .syncing)
                HexDot(tone: .green, size: 10)
                HexSpinner()
                HexSpinner(color: HexColor.green, period: 1.6)
                HexSpinner(color: HexColor.amber)
                HexSpinner(size: HexIconSize.large)
            }
            HStack(spacing: 8) {
                HexBadge("experiment")
                HexBadge("in use")
                HexBadge(text: Text(verbatim: "git"), mono: true)
            }
            HexStatusLine(.hint, verbatim: "A one-line test prompt checks it works")
            HexStatusLine(.waiting, verbatim: "Asking Claude Sonnet 5.5 to reply…")
            HexStatusLine(.ok, verbatim: "Works · replied in 1.2 s")
            HexStatusLine(.warning, verbatim: "The provider has no API key in OpenCode. Add one, then test again.")
        }
    }

    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            HexSectionLabel(verbatim: "Rows")
            HexRow(icon: .cpu, verbatim: "Model", value: "Claude Sonnet 5.5") {}
            HexRow(icon: .folder, verbatim: "Project", subtitle: "/Users/example/Projects/hexcode", value: "hexcode") {}
            HexRow(icon: .info, verbatim: "Version", value: "0.1.0 (debug)", trailing: .none) {}
            HexRow(icon: .sun, verbatim: "Checked", trailing: .check) {}
            HexRow(icon: .refresh, verbatim: "With a caption", trailing: .caption("2h ago")) {}
            HexRow(verbatim: "No icon", subtitle: "A subtitle under it") {}
            HexPickerRow(icon: .folder, title: "hexcode", badge: Text(verbatim: "git"), monoBadge: true, subtitle: "4 folders", trailing: .chevron) {}
            HexPickerRow(icon: .folder, title: "notes", subtitle: "/Users/example/notes", trailing: .caption("2h ago")) {}
            HexPickerRow(icon: .folder, title: "Loading its subtitle", reservesSubtitle: true, trailing: .chevron) {}
            HexPickerRow(variant: .model, title: "Claude Sonnet 5.5", selected: true, badge: Text(verbatim: "default"), monoBadge: true, trailing: .check) {}
            HexPickerRow(variant: .model, title: "GPT-5.5", subtitle: "openai", trailing: .none) {}
        }
    }

    private var fields: some View {
        section("Fields") {
            HexSearchField("Search models", text: $query)
            HexTextField("Name", text: $name)
            HStack(spacing: 8) {
                HexTextField("Folder path", text: $path, monospaced: true)
                HexButton(icon: .send, size: .field) {}
            }
            HexSegmented(["default", "low", "high"], selection: $level) { $0.capitalized }
            HexSegmentedPlaceholder("This model has no reasoning levels")
        }
    }

    private var draft: some View {
        section("Draft") {
            HexChunk(items: chunks, selectedID: selectedChunk) { id in
                selectedChunk = selectedChunk == id ? nil : id
            }
            caption("Tap a chunk to select it; the others dim")
            HexChunk(items: failedChunks, onTap: { _ in })
            HexChunk(items: chunks, interactive: false, style: .body, onTap: { _ in })
            caption("Failed take · while recording (not tappable) · body style")
            VStack(spacing: 2) {
                HexChunkRow(number: "1", text: chunks[0].text, duration: "0:07") {}
                HexChunkRow(number: "2", text: chunks[0].text + " " + chunks[1].text, duration: "0:04", selected: true) {}
                HexChunkRow(number: "3", text: chunks[0].text, duration: "0:07", dimmed: true) {}
                HexChunkRow(number: nil, text: "Listening…", duration: "0:02", live: true) {}
            }
        }
        .padding(.horizontal, HexSpacing.screenHome - HexSpacing.screen)
    }

    private var thumbnails: some View {
        section("Thumbnails") {
            HStack(spacing: 8) {
                HexThumbnail(image: Self.sampleShot, onRemove: {})
                HexThumbnail(image: nil, onRemove: {})
                HexThumbnail(image: Self.samplePhoto, dimmed: true, onRemove: {})
                Text(verbatim: "3 images · go with your next message")
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text3)
                    .lineLimit(2)
                    .padding(.leading, 4)
            }
            .padding(.top, 5)
            caption("Ready · preparing · dimmed (the model can't see images), each with ✕")
            HStack(spacing: 6) {
                HexThumbnail(image: Self.sampleShot, size: .small)
                HexThumbnail(image: Self.samplePhoto, size: .small)
                HexThumbnail(image: nil, size: .small)
            }
            caption("Small (24 pt), in Waiting to send")
        }
    }

    /// Stand-ins for a screenshot and a photo.
    private static let sampleShot = sampleImage(top: HexColor.indigo, bottom: HexColor.green)
    private static let samplePhoto = sampleImage(top: HexColor.amber, bottom: HexColor.red)

    private static func sampleImage(top: Color, bottom: Color) -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 120, height: 120)).image { context in
            let colors = [UIColor(top).cgColor, UIColor(bottom).cgColor] as CFArray
            let gradient = CGGradient(colorsSpace: nil, colors: colors, locations: [0, 1])!
            context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 60, y: 120), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            UIColor.white.withAlphaComponent(0.7).setFill()
            UIRectFill(CGRect(x: 14, y: 18, width: 56, height: 9))
            UIColor.white.withAlphaComponent(0.85).setFill()
            UIBezierPath(roundedRect: CGRect(x: 28, y: 92, width: 64, height: 11), cornerRadius: 5.5).fill()
        }
    }

    private var talkButton: some View {
        section("Talk button and meter") {
            HexMeter(level: { HexMeterLevel.height(dBFS: Double.random(in: -42 ... -20)) })
                .frame(height: HexTalkButton.meterHeight)
            HexMeter(level: { HexMeterLevel.height(dBFS: -60) })
                .frame(height: HexTalkButton.meterHeight)
            caption("Speech (−42 to −20 dBFS) · silence (dots)")
            talk(HexTalkButton.Phase(label: "Hold to talk"))
            talk(HexTalkButton.Phase(label: "Hold to keep talking", quiet: true))
            talk(HexTalkButton.Phase(label: "", recording: true, pressing: true))
            talk(HexTalkButton.Phase(label: "", recording: true, pressing: true, offset: 43))
            talk(HexTalkButton.Phase(label: "", recording: true, pressing: true, offset: 70, arm: .lock))
            talk(HexTalkButton.Phase(label: "", recording: true, pressing: true, offset: -54))
            talk(HexTalkButton.Phase(label: "", recording: true, locked: true, startedAt: Date().addingTimeInterval(-14)))
            caption("Idle · quiet · held · dragging to lock · armed · dragging to drop · locked")
        }
        .padding(.horizontal, HexSpacing.screenHome - HexSpacing.screen)
    }

    private var holdHints: some View {
        section("Hold hint") {
            VStack(spacing: 14) {
                HexHoldHintLine(offset: 0, arm: nil)
                HexHoldHintLine(offset: 40, arm: nil)
                HexHoldHintLine(offset: -40, arm: nil)
                HexHoldHintLine(offset: 70, arm: .lock)
                HexHoldHintLine(offset: -70, arm: .drop)
            }
            .frame(maxWidth: .infinity)
            caption("Resting · leaning lock · leaning drop · armed lock · armed drop")
        }
    }

    private var notices: some View {
        section("Toast") {
            ForEach(Self.toastSamples.indices, id: \.self) { index in
                HexToast(item: Self.toastSamples[index])
            }
            caption("sun · info · stop · steer · undo with an action · title only · long detail")
            HStack(spacing: 8) {
                HexButton("Show at the top", size: .regular) {
                    toasts.show(Self.toastSamples[0])
                }
                HexButton("With Undo", size: .regular) {
                    toasts.show(Self.toastSamples[4]) { toasts.show(HexToastItem(tone: .steer, title: "Undone")) }
                }
            }
        }
    }

    private static let toastSamples: [HexToastItem] = [
        HexToastItem(tone: .sun, title: "New day, new session", detail: "Yesterday’s is kept on your computer.", duration: 3.6),
        HexToastItem(tone: .info, title: "Not in this version yet", detail: "It comes with the session screen."),
        HexToastItem(tone: .stop, title: "Stopped", detail: "Queued prompts stay parked. Talk to start again."),
        HexToastItem(tone: .steer, title: "Build copied", detail: "Hex Code 0.1.0 (debug) fdd8a45"),
        HexToastItem(
            tone: .undo,
            title: "Chunk deleted",
            detail: "“Rename the login screen’s helper to session…”",
            action: .init(title: "Undo", icon: .undo),
            duration: 3.5
        ),
        HexToastItem(tone: .steer, title: "Developer settings on"),
        HexToastItem(
            tone: .info,
            title: "A notice with a long detail line",
            detail: "The detail stays on one line and ends in an ellipsis when it runs out of room."
        ),
    ]

    private var progress: some View {
        section("Progress") {
            progressSample([.open, .open, .open, .open], "1 of 4 · Read the auth tests")
            progressSample([.done, .done, .inProgress, .open], "3 of 4 · Token refresh")
            progressSample([.done, .inProgress, .open, .open, .open, .open, .open, .open, .open, .open], "2 of 10 · A long item that runs past the edge of the row")
            progressSample([.done, .done, .done, .done], "All 4 done")
            progressSample([.inProgress], "1 of 1 · One item")
            caption("None started · one in progress · ten items, a long item · all done · one item. The label and › light while pressed")
        }
    }

    private var inlineCode: some View {
        section("Inline code") {
            ForEach([HexTextStyle.display, .nowSmall, .body, .caption], id: \.size) { style in
                (Text(verbatim: "Run") + HexInlineCode.text("bun test auth", size: style.size) + Text(verbatim: "in")
                    + HexInlineCode.text("packages/desktop/icons", size: style.size) + Text(verbatim: "."))
                    .hexFont(style)
                    .foregroundStyle(HexColor.text1)
                    .hexInlineCode()
            }
            caption("display · nowSmall · body · caption. Monospace at 0.82, in a box that hugs the glyphs; code moves to the next line whole")
        }
    }

    private var linkTiles: some View {
        section("Link tiles") {
            HexLinkTile(icon: .globe, label: "Refresh docs", detail: "developer.mozilla.org", detailIcon: .out) {}
            HexLinkTile(icon: .monitor, label: "Login preview", detail: "192.168.1.2:5173", detailIcon: .monitor) {}
            HexLinkTile(icon: .globe, label: "A label long enough to be cut short", detail: "github.com", detailIcon: .out) {}
            caption("Web · a page on your computer · a long label")
            HexTileRow {
                HexLinkTile(icon: .monitor, label: "Login preview", detail: "192.168.1.2:5173", detailIcon: .monitor) {}
                HexLinkTile(icon: .globe, label: "Refresh docs", detail: "developer.mozilla.org", detailIcon: .out) {}
                HexLinkTile(icon: .globe, label: "Pull request", detail: "github.com", detailIcon: .out) {}
            }
            caption("A row with more than fits: it scrolls sideways and fades at the right edge while there is more")
            HexTileRow {
                HexLinkTile(icon: .globe, label: "Refresh docs", detail: "developer.mozilla.org", detailIcon: .out) {}
            }
            caption("A row that fits: no fade")
        }
    }

    private func progressSample(_ segments: [HexProgressSegment], _ label: String) -> some View {
        HexProgress(segments: segments, label: Text(verbatim: label), accessibilityText: label)
    }

    private var surfaces: some View {
        section("Panel and sheet") {
            HexPanel {
                Text(verbatim: "A panel")
                    .hexFont(.callout)
                    .foregroundStyle(HexColor.text1)
            }
            ZStack {
                LinearGradient(colors: [HexColor.green, HexColor.indigo], startPoint: .leading, endPoint: .trailing)
                HexPanel(translucent: true) {
                    Text(verbatim: "A translucent panel, as over the camera")
                        .hexFont(.callout)
                        .foregroundStyle(HexColor.text1)
                }
                .padding(12)
            }
            .clipShape(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
            HexGallerySheet(onClose: {})
                .frame(height: 210)
                .background(
                    UnevenRoundedRectangle(topLeadingRadius: HexRadius.sheet, topTrailingRadius: HexRadius.sheet, style: .continuous)
                        .fill(HexColor.panel)
                )
                .overlay(
                    UnevenRoundedRectangle(topLeadingRadius: HexRadius.sheet, topTrailingRadius: HexRadius.sheet, style: .continuous)
                        .strokeBorder(HexColor.line, lineWidth: 1)
                )
            HexButton("Open as a sheet", size: .regular) { showsSheet = true }
        }
    }

    // MARK: M4

    private var options: some View {
        section("Options") {
            HexOption(title: "30 days", detail: "Today's behaviour") {}
            HexOption(title: "7 days", detail: "Safer on shared computers", selected: true) {}
            HexOption(title: "Apple", mark: .checkbox) {}
            HexOption(title: "Kiwi", mark: .checkbox, selected: true) {}
            HexOption(title: "Say your own answer") {}
            HexOption(title: "Say your own answer", live: .listening("Listening… · release to finish")) {}
            HexOption(title: "Say your own answer", live: .transcribing("Transcribing…")) {}
            HexOption(title: "Say your own answer", detail: "Green, but only a dark one", selected: true) {}
            HexOption(title: "Say what to do instead", mark: .none) {}
            HexOption(title: "1 day", enabled: false) {}
            caption("Unselected · selected · multi unselected · multi selected · own answer empty · Listening · Transcribing · filled · no mark · disabled")
        }
    }

    private var codeBlocks: some View {
        section("Code") {
            HexCode(lines: [HexCodeLine("bun run db:reset"), HexCodeLine("bun test auth --watch=false --reporter=verbose --timeout=30000")])
            HexCode(lines: [
                HexCodeLine("src/auth/session.ts"),
                HexCodeLine("-const maxAge = days(30)", kind: .removed),
                HexCodeLine("+const maxAge = days(7)", kind: .added),
            ])
            HexCode(lines: (1 ... 16).map { HexCodeLine("+line \($0)", kind: .added) }, lineLimit: 12)
            caption("Commands (wrapping, never cut) · a diff · capped at 12 lines")
        }
    }

    private var stopHold: some View {
        section("Stop hold") {
            HStack(spacing: 18) {
                HexIconButton(.stop, label: "Stop", tone: .stop, heldSince: Date().addingTimeInterval(-1.4), holdDuration: 100_000)
                HexIconButton(.stop, label: "Stop", tone: .stop, heldSince: Date(), holdDuration: 3)
                HexIconButton(.stop, label: "Stop", tone: .stop)
            }
            caption("Held, ring part filled · held now, filling over 3 s · resting")
            ForEach([0.2, 1.2, 2.4], id: \.self) { elapsed in
                HexStopWarning(since: Date(), title: { "Stopping the agent in \($0)" }, detail: "Let go to keep it running", frozenAt: elapsed)
            }
            HexStopWarning(since: Date(), title: { "Stopping the agent in \($0)" }, detail: "Let go to keep it running", frozenAt: 0.1, reducedMotion: true)
            caption("3 · 2 · 1 · Reduce Motion (no pop)")
            ZStack {
                HexColor.background
                HexStopEdge(cornerRadius: 28)
                Text(verbatim: "The screen edge, pulsing")
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text3)
            }
            .frame(height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        }
    }

    private var requestSheets: some View {
        section("Request sheets") {
            sheetSample(amber: true) {
                HexSheet(request: .init(title: Text(verbatim: "Permission requested"), count: "1 of 2", hideLabel: Text(verbatim: "Hide"), onHide: {}), amberEdge: true) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(verbatim: "Run a command").hexFont(.body).foregroundStyle(HexColor.text2)
                        HexCode(lines: [HexCodeLine("ls -la")])
                    }
                }
            }
            sheetSample(amber: false) {
                HexSheet(request: .init(title: Text(verbatim: "Login length"), count: nil, hideLabel: Text(verbatim: "Hide"), onHide: {})) {
                    Text(verbatim: "How long should a login last?").hexFont(.body).foregroundStyle(HexColor.text1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            caption("Request head with a count and the amber edge · without")
        }
    }

    private func sheetSample(amber: Bool, @ViewBuilder content: () -> some View) -> some View {
        content()
            .frame(height: amber ? 150 : 110)
            .background(
                UnevenRoundedRectangle(topLeadingRadius: HexRadius.sheet, topTrailingRadius: HexRadius.sheet, style: .continuous)
                    .fill(HexColor.panel)
            )
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: HexRadius.sheet, topTrailingRadius: HexRadius.sheet, style: .continuous))
    }

    private var details: some View {
        section("Details") {
            HexDetailsSection(title: Text(verbatim: "Needs you")) {
                HexDetailRow(leading: .icon(.alert, HexColor.amber), title: Text(verbatim: "Approval needed"), detail: Text(verbatim: "bun run db:reset")) {
                    HexDetailChevron()
                }
            }
            HexDetailsSection(title: Text(verbatim: "Running now"), count: "2") {
                HexDetailRow(leading: .dot(.green), title: Text(verbatim: "Find every caller"), detail: Text(verbatim: "Subagent · general")) {
                    HexDetailTime(text: "1:14")
                }
                HexDetailRow(leading: .dot(.neutral), title: Text(verbatim: "bun run dev --port 5173 --host 0.0.0.0"), code: true, detail: Text(verbatim: "Shell command")) {
                    HexDetailTime(text: "5:12")
                }
                HexDetailRow(title: Text(verbatim: "+1 more"), done: true)
            }
            HexDetailsSection(title: Text(verbatim: "To-do list"), suffix: Text(verbatim: "· from the agent"), count: "1/3") {
                HexDetailRow(leading: .todo(.todoDone), title: Text(verbatim: "Remove old session helper"), wraps: true, done: true)
                HexDetailRow(leading: .todo(.todoDoing), title: Text(verbatim: "Token refresh"), wraps: true)
                HexDetailRow(leading: .todo(.todoPending), title: Text(verbatim: "Auth tests"), wraps: true)
            }
            HexDetailsSection(title: Text(verbatim: "Waiting to send"), count: "1") {
                HexDetailRow(leading: .icon(.send, HexColor.text3), title: Text(verbatim: "Then run the auth tests"), detail: Text(verbatim: "Queue · after this turn")) {
                    HexButton("Cancel") {}
                }
            }
            HexDetailsSection(title: Text(verbatim: "Recent")) {
                HexDetailRow(leading: .icon(.pencil, HexColor.text3), title: Text(verbatim: "src/auth/session.ts"), code: true, detail: Text(verbatim: "Edited · +12 −3")) {
                    HexDetailTime(text: "4m")
                }
                HexDetailRow(leading: .icon(.term, HexColor.text3), title: Text(verbatim: "bun test auth"), code: true, detail: Text(verbatim: "Failed · exit 1")) {
                    HexDetailTime(text: "2m")
                }
                HexDetailRow(leading: .icon(.spark, HexColor.text3), title: Text(verbatim: "Find every caller"), detail: Text(verbatim: "Subagent · general")) {
                    HexDetailTime(text: "now")
                }
            }
            caption("Section heads with a suffix and a count · icon, dot and to-do rows · time, button and › · done variant")
        }
    }

    // MARK: M5

    private var menus: some View {
        section("Menu") {
            HStack {
                Spacer(minLength: 0)
                HexMenu(items: [
                    HexMenuItem(id: "ask", icon: .ask, title: "Ask on the side", detail: "Answered from the session, not sent to the agent") {},
                    HexMenuItem(id: "goal", icon: .target, iconColor: HexColor.green, title: "Set as today’s goal", detail: "The agent keeps going until it’s done") {},
                ])
                .frame(width: 300)
            }
            HStack {
                Spacer(minLength: 0)
                HexMenu(items: [
                    HexMenuItem(id: "ask", icon: .ask, title: "Ask on the side", detail: "Answered from the session, not sent to the agent", enabled: false) {},
                    HexMenuItem(id: "goal", icon: .target, iconColor: HexColor.green, title: "Replace today’s goal", detail: "The agent keeps going until it’s done", enabled: false) {},
                ])
                .frame(width: 300)
            }
            caption("Open · items disabled while a chunk is still transcribing")
        }
    }

    private var goals: some View {
        section("Goal") {
            HStack(spacing: 10) {
                HexRoundButton(.target, label: "Goal") {}
                HexRoundButton(.target, label: "Goal", on: true) {}
            }
            caption("Round button · on (the goal is active)")
            VStack(spacing: 0) {
                HexGoalLine(objective: nil) {}
                HexGoalLine(objective: "Make login work offline and add tests for the token refresh", suffix: "starting", lit: true) {}
                HexGoalLine(objective: "Make login work offline and add tests for the token refresh", suffix: "round 3", lit: true) {}
                HexGoalLine(objective: "Make login work offline", suffix: "waiting", lit: true) {}
                HexGoalLine(objective: "Make login work offline", suffix: "paused") {}
                HexGoalLine(objective: "Make login work offline", suffix: "done", lit: true) {}
            }
            caption("Empty · starting · round 3 · waiting · paused · done")
            HexGoalBox(label: "/goal", content: .empty("No goal yet."))
            HexGoalBox(label: "/goal", status: "active · round 3 of 50", content: .text("Make login work offline and add tests for the token refresh"))
            HexGoalBox(label: "/goal", status: "paused", content: .text("Make login work offline"), reason: "Stopped by the user")
            HexGoalBox(label: "/goal", status: "done", content: .text("Create one.txt, two.txt and three.txt"), reason: "Verified all three files exist with the right contents.")
            HexGoalBox(label: "new goal", status: "On this phone", content: .listening("Listening…"))
            HexGoalBox(label: "new goal", status: "On this phone", content: .transcribing("Transcribing…"))
            HexGoalBox(label: "today’s goal", status: "On this phone", content: .text("Ship the release notes"))
            caption("Empty · active · paused with its reason · done with the evidence · Listening · Transcribing · a take's text")
        }
    }

    private var askMessages: some View {
        section("Ask message") {
            HexAskMessage(question: "Why did the auth tests fail?", age: "now", state: .asking)
            HexAskMessage(question: "Why did the auth tests fail?", age: "2m", state: .answered("One test still uses the old `session` helper; the agent is moving it to the new API client."))
            HexAskMessage(
                question: "Why did the auth tests fail?",
                age: "2m",
                state: .answered("One test still uses the old `session` helper; the agent is moving it to the new API client, then reruns the suite."),
                isOpen: true
            )
            HexAskMessage(question: "Check the tests", age: "now", state: .problem(title: "No answer", detail: "Ask it another way, or send it as a message."))
            HexAskMessage(question: "Anything new?", age: "now", state: .problem(title: "Couldn’t answer", detail: "Try again."))
            caption("Asking · answered, folded · open · empty · failed")
            HexDetailsSection(title: Text(verbatim: "Asked on the side"), suffix: Text(verbatim: "· only you see these"), count: "2") {
                HexDetailRow(leading: .icon(.ask, HexColor.text3), title: Text(verbatim: "Why did the auth tests fail?"), detail: Text(verbatim: "One test still uses the old session helper.")) {
                    HexDetailChevron()
                }
                HexDetailRow(leading: .icon(.ask, HexColor.text3), title: Text(verbatim: "What's in mathutils.py?"), detail: Text(verbatim: "add, sub, mul and clamp.")) {
                    HexDetailChevron()
                }
            }
        }
    }

    // MARK: Helpers

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HexSectionLabel(verbatim: title)
            VStack(alignment: .leading, spacing: 10) {
                content()
            }
            .padding(.horizontal, HexSpacing.screen)
        }
    }

    private func caption(_ text: String) -> some View {
        Text(verbatim: text)
            .hexFont(.monoSmall)
            .foregroundStyle(HexColor.text3)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
    }

    private func talk(_ phase: HexTalkButton.Phase) -> some View {
        HexTalkButton(
            phase: phase,
            level: { Double.random(in: 0 ... 0.5) },
            accessibilityText: phase.label,
            onPressBegan: {},
            onPressMoved: { _ in },
            onPressEnded: {}
        )
        // Its press starts on touch down, which would stop the page scrolling; the samples are fixed states.
        .allowsHitTesting(false)
    }
}

/// A `HexSheet` with a title and an action on each side.
private struct HexGallerySheet: View {
    let onClose: () -> Void

    var body: some View {
        HexSheet(
            title: Text(verbatim: "Reasoning"),
            leading: { HexButton("Cancel", action: onClose) },
            trailing: { HexButton("Done", style: .primary, action: onClose) },
            content: {
                VStack(alignment: .leading, spacing: 10) {
                    Text(verbatim: "A sheet on panel, radius 20 at the top, with a grab bar.")
                        .hexFont(.body)
                        .foregroundStyle(HexColor.text2)
                    HexStatusLine(.ok, verbatim: "Saved on this phone")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        )
    }
}

#Preview("Light") { HexGallery(onBack: {}).hexTheme(.light) }
#Preview("Dark") { HexGallery(onBack: {}).hexTheme(.dark) }
