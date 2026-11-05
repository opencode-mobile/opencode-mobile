import OpenCodeKit
import SwiftUI
import VoiceEngineKit

/// Where first run lands. Not hooked up to the agent yet: the top bar's state slot reads Idle,
/// and the talk button fills a draft that stays on the phone.
struct HomeView: View {
    let home: HomeModel
    let voice: VoiceEngine
    let onSettings: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HomeTopBar(onSettings: onSettings)
            GeometryReader { geometry in
                VStack(alignment: .leading, spacing: 14) {
                    // The draft hangs from a fixed line about a quarter down, so nothing jumps between states.
                    Spacer().frame(height: geometry.size.height * 0.22)
                    if home.isRecording || !home.draft.isEmpty {
                        DraftView(home: home)
                    } else {
                        emptyHint
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let toast = home.toast {
                HomeToastView(toast: toast, onUndo: { home.undoDelete() })
                    .padding(.bottom, 10)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            TalkButton(home: home, voice: voice)
                .padding(.bottom, 6)
        }
        .padding(.horizontal, HexLayout.edge)
        .background(OpenCodeColors.backgroundBase)
        .animation(.easeOut(duration: 0.2), value: home.toast)
        .animation(.easeOut(duration: 0.2), value: home.draft.isEmpty && !home.isRecording)
    }

    private var emptyHint: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringResource("home.empty.title", defaultValue: "Say what you want done"))
                .font(.system(size: 22, weight: .semibold))
                .tracking(-0.4)
                .foregroundStyle(OpenCodeColors.textStrong)
            Text(LocalizedStringResource(
                "home.empty.body",
                defaultValue: "Hold the button and talk. Text appears when you pause, and stays here: sending comes next."
            ))
            .font(.system(size: 15))
            .foregroundStyle(OpenCodeColors.textWeak)
        }
        .transition(.opacity)
    }
}

/// The agent's state on the left, ⚙ on the right. The goal button joins in a later milestone.
struct HomeTopBar: View {
    let onSettings: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Circle()
                    .stroke(OpenCodeColors.textWeaker, lineWidth: 1.5)
                    .frame(width: 11, height: 11)
                Text(LocalizedStringResource("home.state.idle", defaultValue: "Idle"))
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            Spacer()
            Button(action: onSettings) {
                OpenCodeIconImage(icon: .settings, size: 17)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .frame(width: 44, height: 44)
                    .background(OpenCodeColors.surfaceRaisedBase, in: Circle())
                    .overlay(Circle().stroke(OpenCodeColors.borderWeakBase, lineWidth: 1))
            }
            .buttonStyle(HexPressStyle())
            .accessibilityLabel(Text(LocalizedStringResource("home.settings", defaultValue: "Settings")))
        }
        .frame(height: 44)
        .padding(.top, 2)
    }
}

/// The draft: a label, the chunks (as running text, or one row each once it is long), and the actions.
struct DraftView: View {
    let home: HomeModel

    private var draft: DictationDraft { home.draft }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            label
                .frame(height: 18)
            if draft.isLong {
                ChunkListView(home: home)
            } else {
                DraftText(home: home)
            }
            actions
                .opacity(home.isRecording ? 0 : 1)
                .allowsHitTesting(!home.isRecording)
        }
        .animation(.easeOut(duration: 0.18), value: draft)
    }

    @ViewBuilder
    private var label: some View {
        if home.isRecording {
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                HStack(spacing: 6) {
                    Circle().fill(OpenCodeColors.error).frame(width: 7, height: 7)
                    Text(verbatim: recordingLabel)
                    if home.locked {
                        OpenCodeIconImage(icon: .lock, size: 12)
                    }
                }
            }
            .modifier(LabelStyle())
        } else if draft.isTranscribing {
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini)
                Text(LocalizedStringResource("draft.transcribing", defaultValue: "Transcribing on this phone…"))
            }
            .modifier(LabelStyle())
        } else {
            Text(verbatim: summary).modifier(LabelStyle())
        }
    }

    private var recordingLabel: String {
        let elapsed = home.takeStartedAt.map { Date().timeIntervalSince($0) } ?? 0
        let clock = DraftClock.format(elapsed)
        if draft.isRerecording, let index = draft.recordingIndex {
            return String(localized: "draft.rerecording", defaultValue: "Re-recording chunk \(index + 1) · \(clock)")
        }
        return String(localized: "draft.recording", defaultValue: "Recording · \(clock)")
    }

    private var summary: String {
        let chunks = draft.chunks.count == 1
            ? String(localized: "draft.one_chunk", defaultValue: "1 chunk")
            : String(localized: "draft.chunks", defaultValue: "\(draft.chunks.count) chunks")
        var parts = [String(localized: "draft.title", defaultValue: "Draft"), chunks, DraftClock.format(draft.totalDuration)]
        if draft.isLong { parts.append(String(localized: "draft.words", defaultValue: "\(draft.wordCount) words")) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var actions: some View {
        HStack(spacing: 8) {
            if let index = draft.selectedIndex {
                Text(LocalizedStringResource("draft.chunk_label", defaultValue: "Chunk \(index + 1)"))
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(OpenCodeColors.textWeak)
                Spacer()
                DraftButton(icon: .delete, title: LocalizedStringResource("draft.delete", defaultValue: "Delete"), tint: OpenCodeColors.error) {
                    home.deleteSelectedChunk()
                }
                DraftButton(icon: .mic, title: LocalizedStringResource("draft.rerecord", defaultValue: "Re-record")) {
                    home.rerecordSelected()
                }
                DraftButton(icon: .close, title: nil) { home.deselectChunk() }
                    .accessibilityLabel(Text(LocalizedStringResource("draft.deselect", defaultValue: "Deselect")))
            } else {
                DraftButton(icon: .close, title: nil) { home.clearDraft() }
                    .accessibilityLabel(Text(LocalizedStringResource("draft.clear", defaultValue: "Clear the draft")))
                Spacer()
            }
        }
        .frame(height: 36)
    }

    private struct LabelStyle: ViewModifier {
        func body(content: Content) -> some View {
            content
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .lineLimit(1)
        }
    }
}

/// Small bordered action under the draft.
struct DraftButton: View {
    let icon: OpenCodeIcon
    let title: LocalizedStringResource?
    var tint: Color = OpenCodeColors.textStrong
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                OpenCodeIconImage(icon: icon, size: 14)
                if let title { Text(title).font(.system(size: 13.5, weight: .medium)) }
            }
            .foregroundStyle(tint)
            .padding(.horizontal, title == nil ? 0 : 12)
            .frame(minWidth: 36, minHeight: 36)
            .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(OpenCodeColors.borderWeakBase, lineWidth: 1))
        }
        .buttonStyle(HexPressStyle())
    }
}

/// Short drafts read as running text; each chunk is a tappable span.
struct DraftText: View {
    let home: HomeModel

    private static let fontSize: CGFloat = 21
    private static let lineHeight: CGFloat = 29

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(attributed)
                .font(.system(size: Self.fontSize, weight: .medium))
                .tracking(-0.2)
                .lineSpacing(Self.lineHeight - Self.fontSize - 4)
                .environment(\.openURL, OpenURLAction { url in
                    if let id = url.host().flatMap(UUID.init(uuidString:)) { home.toggleChunk(id) }
                    return .handled
                })
                .tint(OpenCodeColors.textStrong)
            if home.isRecording, home.draft.chunks.count == 1, !home.draft.isRerecording {
                Text(LocalizedStringResource("draft.first_take_hint", defaultValue: "Text appears when you pause"))
                    .font(.system(size: 13))
                    .foregroundStyle(OpenCodeColors.textWeaker)
            }
        }
        // Three lines of room, so the actions don't jump as text arrives.
        .frame(maxWidth: .infinity, minHeight: Self.lineHeight * 3, alignment: .topLeading)
    }

    private var attributed: AttributedString {
        let draft = home.draft
        let selected = draft.selectedID
        var out = AttributedString()
        for (index, chunk) in draft.chunks.enumerated() {
            if index > 0 { out += AttributedString(" ") }
            var span: AttributedString
            switch chunk.state {
            case .listening:
                span = AttributedString(String(localized: "draft.listening", defaultValue: "Listening…"))
                span.foregroundColor = OpenCodeColors.textWeaker
            case .transcribing:
                span = AttributedString(String(localized: "draft.transcribing_chunk", defaultValue: "Transcribing…"))
                span.foregroundColor = OpenCodeColors.textWeaker
            case let .text(text):
                span = AttributedString(text)
                span.foregroundColor = selected == nil || selected == chunk.id ? OpenCodeColors.textStrong : OpenCodeColors.textWeaker
                if !home.isRecording { span.link = URL(string: "chunk://\(chunk.id.uuidString)") }
                if selected == chunk.id { span.backgroundColor = OpenCodeColors.surfaceBaseHover }
            case let .failed(message):
                span = AttributedString(message)
                span.foregroundColor = OpenCodeColors.error
                if !home.isRecording { span.link = URL(string: "chunk://\(chunk.id.uuidString)") }
                if selected == chunk.id { span.backgroundColor = OpenCodeColors.surfaceBaseHover }
            }
            out += span
        }
        return out
    }
}

/// Long drafts: one row per chunk with its length, at most eight rows, earlier and later folded.
struct ChunkListView: View {
    let home: HomeModel

    var body: some View {
        let draft = home.draft
        let range = draft.visibleRange(showEarlier: home.showEarlier)
        let listed = draft.isListening && !draft.isRerecording ? draft.chunks.count - 1 : draft.chunks.count
        VStack(alignment: .leading, spacing: 1) {
            if range.lowerBound > 0 {
                foldRow(
                    String(localized: "draft.earlier", defaultValue: "↑ \(range.lowerBound) earlier · \(DraftClock.format(duration(0 ..< range.lowerBound)))")
                ) { home.showEarlier = true }
            }
            ForEach(Array(range), id: \.self) { index in
                row(index)
            }
            if listed > range.upperBound {
                foldRow(
                    String(localized: "draft.later", defaultValue: "↓ \(listed - range.upperBound) later · \(DraftClock.format(duration(range.upperBound ..< listed)))")
                ) { home.showEarlier = false }
            }
            if draft.isListening, !draft.isRerecording, let last = draft.chunks.last {
                row(draft.chunks.count - 1, live: true, chunk: last)
            }
        }
        .padding(.horizontal, -6)
    }

    private func duration(_ range: Range<Int>) -> TimeInterval {
        home.draft.chunks[range].reduce(0) { $0 + $1.duration }
    }

    private func foldRow(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title)
                .font(.system(size: 12))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .frame(maxWidth: .infinity)
                .padding(4)
        }
        .buttonStyle(.plain)
    }

    private func row(_ index: Int, live: Bool = false, chunk: DraftChunk? = nil) -> some View {
        let chunk = chunk ?? home.draft.chunks[index]
        let selected = home.draft.selectedID == chunk.id
        let dimmed = home.isRecording || (home.draft.selectedID != nil && !selected)
        return Button {
            home.toggleChunk(chunk.id)
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Group {
                    if live {
                        Circle().fill(OpenCodeColors.error).frame(width: 6, height: 6)
                    } else {
                        Text(verbatim: "\(index + 1)")
                    }
                }
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(OpenCodeColors.textWeaker)
                .frame(width: 16, alignment: .trailing)
                Text(verbatim: rowText(chunk))
                    .font(.system(size: 14.5, weight: live ? .medium : .regular))
                    .foregroundStyle(dimmed || chunk.text == nil ? OpenCodeColors.textWeaker : OpenCodeColors.textStrong)
                    .lineLimit(selected ? 4 : 1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(verbatim: live ? "" : DraftClock.format(chunk.duration))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(OpenCodeColors.textWeaker)
            }
            .padding(6)
            .background(selected ? OpenCodeColors.surfaceBaseHover : .clear, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(home.isRecording || chunk.text == nil && !isFailed(chunk))
    }

    private func isFailed(_ chunk: DraftChunk) -> Bool {
        if case .failed = chunk.state { return true }
        return false
    }

    private func rowText(_ chunk: DraftChunk) -> String {
        switch chunk.state {
        case .listening: String(localized: "draft.listening", defaultValue: "Listening…")
        case .transcribing: String(localized: "draft.transcribing_chunk", defaultValue: "Transcribing…")
        case let .text(text): text
        case let .failed(message): message
        }
    }
}

enum DraftClock {
    /// 0:07, 1:24
    static func format(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

/// The note at the bottom, with Undo when it can be undone.
struct HomeToastView: View {
    let toast: HomeToast
    let onUndo: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: toast.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(OpenCodeColors.textStrong)
                if let subtitle = toast.subtitle {
                    Text(verbatim: subtitle)
                        .font(.system(size: 12.5))
                        .foregroundStyle(OpenCodeColors.textWeak)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if toast.action == .undoDelete {
                Button(action: onUndo) {
                    HStack(spacing: 5) {
                        OpenCodeIconImage(icon: .undo, size: 13)
                        Text(LocalizedStringResource("common.undo", defaultValue: "Undo"))
                            .font(.system(size: 13.5, weight: .semibold))
                    }
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .padding(.horizontal, 12)
                    .frame(height: 32)
                    .background(OpenCodeColors.surfaceBaseHover, in: Capsule())
                }
                .buttonStyle(HexPressStyle())
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(OpenCodeColors.borderWeakBase, lineWidth: 1))
    }
}
