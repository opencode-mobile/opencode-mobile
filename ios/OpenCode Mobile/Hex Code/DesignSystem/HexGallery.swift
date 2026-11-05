#if DEBUG
import SwiftUI

/// Every Hex component in its states, for checking light and dark on a simulator.
/// Opened with `-HexCodeGallery YES` (debug builds only).
struct HexGallery: View {
    @State private var level = "high"
    @State private var query = ""
    @State private var path = "~/Projects"
    @State private var selectedChunk: UUID?

    private let chunks: [HexChunkItem] = [
        HexChunkItem(id: UUID(), text: "Rename the login screen's helper to session client,"),
        HexChunkItem(id: UUID(), text: "then run the auth tests"),
        HexChunkItem(id: UUID(), text: "Transcribing…", kind: .placeholder),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HexTopBar(state: HexTopBarState(tone: .working, label: "Working", timer: "4:12")) {
                    HexRoundButton(.target, label: "Goal", on: true) {}
                    HexRoundButton(.gear, label: "Settings") {}
                }
                .padding(.horizontal, HexSpacing.screenHome)
                HexTopBar(state: HexTopBarState(tone: .needsYou, label: "Needs input")) {}
                    .padding(.horizontal, HexSpacing.screenHome)
                HexTopBar("Pick a model", subtitle: "Spry Seal · 12 models from OpenCode", onBack: {})

                HexSectionLabel(verbatim: "Buttons")
                VStack(alignment: .leading, spacing: 10) {
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
                        HexButton("Cancel", size: .regular) {}
                        HexButton("Allow", size: .regular, style: .primary) {}
                        HexIconButton(.clip, label: "Attach") {}
                        HexIconButton(.stop, label: "Stop", tone: .stop) {}
                        HexIconButton(.stop, label: "Stop", tone: .stop, enabled: false) {}
                    }
                    HStack(spacing: 12) {
                        HexDot()
                        HexDot(tone: .green)
                        HexDot(tone: .amber)
                        HexDot(tone: .red)
                        HexDot(tone: .syncing)
                        HexSpinner()
                        HexBadge("experiment")
                        HexBadge(text: Text(verbatim: "git"), mono: true)
                    }
                }
                .padding(.horizontal, HexSpacing.screen)

                HexSectionLabel(verbatim: "Rows")
                HexRow(icon: .cpu, "Model", value: "Claude Sonnet 5.5") {}
                HexRow(icon: .folder, "Project", subtitle: "/Users/example/Projects/hexcode", value: "hexcode") {}
                HexPickerRow(icon: .folder, title: "hexcode", badge: Text(verbatim: "git"), monoBadge: true, subtitle: "4 folders", trailing: .chevron) {}
                HexPickerRow(icon: .folder, title: "notes", subtitle: "/Users/example/notes", trailing: .caption("2h ago")) {}
                HexPickerRow(variant: .model, title: "Claude Sonnet 5.5", selected: true, badge: Text(verbatim: "default"), monoBadge: true, trailing: .check) {}
                HexPickerRow(variant: .model, title: "GPT-5.5", trailing: .none) {}

                HexSectionLabel(verbatim: "Fields")
                VStack(alignment: .leading, spacing: 10) {
                    HexSearchField("Search models", text: $query)
                    HStack(spacing: 8) {
                        HexTextField("Folder path", text: $path, monospaced: true)
                        HexButton(icon: .send, size: .field) {}
                    }
                    HexSegmented(["default", "low", "high"], selection: $level) { $0.capitalized }
                    HexSegmentedPlaceholder("This model has no reasoning levels")
                    HexStatusLine(.hint, "A one-line test prompt checks it works")
                    HexStatusLine(.waiting, "Asking Claude Sonnet 5.5 to reply…")
                    HexStatusLine(.ok, "Works · replied in 1.2 s")
                    HexStatusLine(.warning, "The provider has no API key in OpenCode. Add one, then test again.")
                }
                .padding(.horizontal, HexSpacing.screen)

                HexSectionLabel(verbatim: "Draft")
                VStack(alignment: .leading, spacing: 10) {
                    HexChunk(items: chunks, selectedID: selectedChunk) { id in
                        selectedChunk = selectedChunk == id ? nil : id
                    }
                    HexChunkRow(number: "1", text: chunks[0].text, duration: "0:07") {}
                    HexChunkRow(number: "2", text: chunks[1].text, duration: "0:04", selected: true) {}
                    HexChunkRow(number: "3", text: chunks[0].text, duration: "0:07", dimmed: true) {}
                    HexPanel {
                        Text(verbatim: "A panel")
                            .hexFont(.callout)
                            .foregroundStyle(HexColor.text1)
                    }
                    talk(HexTalkButton.Phase(label: "Hold to talk"))
                    talk(HexTalkButton.Phase(label: "Hold to keep talking", quiet: true))
                    talk(HexTalkButton.Phase(label: "", recording: true, pressing: true, drag: 0.6))
                    talk(HexTalkButton.Phase(label: "", recording: true, pressing: true, drag: -0.8))
                    talk(HexTalkButton.Phase(label: "", recording: true, locked: true))
                }
                .padding(.horizontal, HexSpacing.screenHome)
                .padding(.bottom, 40)
            }
        }
        .hexScreenBackground()
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
    }
}

#Preview("Light") { HexGallery().hexTheme(.light) }
#Preview("Dark") { HexGallery().hexTheme(.dark) }
#endif
