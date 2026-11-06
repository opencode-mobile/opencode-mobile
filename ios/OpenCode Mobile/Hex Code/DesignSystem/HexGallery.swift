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
        ("monoSmall", .monoSmall),
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
