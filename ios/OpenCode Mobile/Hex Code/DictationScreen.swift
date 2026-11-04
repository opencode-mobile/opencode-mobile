import VoiceEngineKit
import SwiftUI

/// First working piece of Hex Code: hold to talk, let go, and Hex's engine turns the take into text on the phone.
/// Each take shows how long the text took to appear, to measure the engine on real devices.
struct DictationScreen: View {
    @State private var engine = VoiceEngine()
    @State private var takes: [Take] = []
    @State private var phase: Phase = .idle
    @State private var recordingStarted = Date()
    @State private var errorText: String?

    private enum Phase: Equatable { case idle, listening, transcribing }

    private struct Take: Identifiable {
        let id = UUID()
        let text: String
        let seconds: TimeInterval
        let latency: TimeInterval
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        ForEach(takes) { take in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(take.text)
                                    .font(.system(size: 22, weight: .medium))
                                    .tracking(-0.2)
                                Text("\(take.seconds == 0 ? "sample file" : "\(format(take.seconds)) take") · text in \(String(format: "%.2f", take.latency)) s")
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(Palette.faint)
                            }
                            .id(take.id)
                        }
                        liveRow.id("live")
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 18)
                }
                .onChange(of: takes.count) { proxy.scrollTo("live", anchor: .bottom) }
            }
            footer
        }
        .background(Palette.background)
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
        .task {
            await engine.prepare()
            #if DEBUG
            await transcribeLaunchSample()
            await runLabClips()
            #endif
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            HexCodeMark().frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text("Dictation").font(.system(size: 20, weight: .semibold))
                modelStatus.font(.system(size: 12.5)).foregroundStyle(Palette.dim).lineLimit(1)
            }
            Spacer()
            if !takes.isEmpty {
                Button("Clear") { takes.removeAll() }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Palette.dim)
            }
        }
        .padding(.horizontal, 22)
        .frame(height: 64)
    }

    @ViewBuilder private var modelStatus: some View {
        switch engine.modelState {
        case .notLoaded:
            Text("\(engine.modelName) · on this phone")
        case let .preparing(fraction, detail):
            Text("Getting speech ready · \(Int(fraction * 100))% · \(detail)")
        case .ready:
            Text("\(engine.modelName) · on this phone")
        case let .failed(message):
            HStack(spacing: 6) {
                Text(message).foregroundStyle(Palette.amber).lineLimit(1)
                Button("Retry") { Task { await engine.prepare() } }.foregroundStyle(.white)
            }
        }
    }

    @ViewBuilder private var liveRow: some View {
        switch phase {
        case .listening:
            VStack(alignment: .leading, spacing: 10) {
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    Text("Listening… \(format(Date().timeIntervalSince(recordingStarted)))")
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(Palette.faint)
                }
                LevelMeter(engine: engine).frame(height: 28)
            }
        case .transcribing:
            Text("Transcribing on this phone…")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(Palette.faint)
        case .idle:
            if let errorText {
                Text(errorText).font(.system(size: 15)).foregroundStyle(Palette.amber)
            } else if takes.isEmpty {
                Text(engine.modelState == .ready ? "Hold the button and talk. Text appears when you let go." : "The speech model downloads once, then everything stays on the phone.")
                    .font(.system(size: 15))
                    .foregroundStyle(Palette.dim)
            }
        }
    }

    private var footer: some View {
        let ready = engine.modelState == .ready && phase != .transcribing
        return Capsule()
            .fill(phase == .listening ? Palette.panel : .white)
            .overlay(Capsule().stroke(.white.opacity(phase == .listening ? 0.2 : 0), lineWidth: 1))
            .overlay {
                Label(phase == .listening ? "Let go to transcribe" : "Hold to talk", systemImage: "mic")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(phase == .listening ? .white : Palette.background)
            }
            .frame(height: 56)
            .padding(.horizontal, 22)
            .padding(.bottom, 10)
            .opacity(ready ? 1 : 0.35)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in if ready, phase == .idle { begin() } }
                    .onEnded { _ in if phase == .listening { finish() } }
            )
            .accessibilityAddTraits(.isButton)
    }

    private func begin() {
        errorText = nil
        phase = .listening
        recordingStarted = Date()
        Task {
            do { try await engine.startRecording() } catch {
                phase = .idle
                errorText = error.localizedDescription
            }
        }
    }

    private func finish() {
        let seconds = Date().timeIntervalSince(recordingStarted)
        phase = .transcribing
        let released = Date()
        Task {
            do {
                let text = try await engine.stopAndTranscribe()
                takes.append(Take(text: text, seconds: seconds, latency: Date().timeIntervalSince(released)))
            } catch {
                errorText = error.localizedDescription
            }
            phase = .idle
        }
    }

    #if DEBUG
    /// `-HexCodeSample <file>` runs a recorded WAV through the engine twice, as if it were a take (simulators have no
    /// voice). The first run includes the model's warm-up. Results also go to Documents/HexCodeSampleResult.txt, so
    /// they can be read back from a device.
    private func transcribeLaunchSample() async {
        guard engine.modelState == .ready, let path = UserDefaults.standard.string(forKey: "HexCodeSample") else { return }
        let url = path.hasPrefix("/") ? URL(fileURLWithPath: path) : URL.documentsDirectory.appending(path: path)
        var report: [String] = []
        for run in 1 ... 2 {
            phase = .transcribing
            let started = Date()
            do {
                let text = try await engine.transcribe(fileAt: url)
                let latency = Date().timeIntervalSince(started)
                takes.append(Take(text: text, seconds: 0, latency: latency))
                report.append(String(format: "run %d: %.2f s · %@", run, latency, text))
            } catch {
                errorText = error.localizedDescription
                report.append("run \(run): failed · \(error.localizedDescription)")
            }
        }
        phase = .idle
        try? report.joined(separator: "\n").write(to: URL.documentsDirectory.appending(path: "HexCodeSampleResult.txt"), atomically: true, encoding: .utf8)
    }

    /// `-HexCodeLab <folder>` runs every clip of a lab clip set (a folder in Documents with `clips.json` and WAVs, as on
    /// Android) through the engine after one warm-up run, and writes Documents/lab/<folder>.jsonl in the Android lab's
    /// result format, so both platforms are scored by the same script.
    private func runLabClips() async {
        guard engine.modelState == .ready, let set = UserDefaults.standard.string(forKey: "HexCodeLab") else { return }
        struct Clip: Decodable { let id, file, reference: String; let seconds: Double }
        struct Clips: Decodable { let clips: [Clip] }
        let folder = URL.documentsDirectory.appending(path: set)
        do {
            let clips = try JSONDecoder().decode(Clips.self, from: Data(contentsOf: folder.appending(path: "clips.json"))).clips
            guard let first = clips.first else { return }
            phase = .transcribing
            let runId = Int(Date().timeIntervalSince1970 * 1000)
            var started = Date()
            _ = try await engine.transcribe(fileAt: folder.appending(path: first.file))
            let firstMs = Date().timeIntervalSince(started) * 1000
            var lines: [String] = []
            for (i, clip) in clips.enumerated() {
                errorText = "Lab clip \(i + 1) of \(clips.count)"
                started = Date()
                let text = (try? await engine.transcribe(fileAt: folder.appending(path: clip.file))) ?? ""
                let result: [String: Any] = [
                    "runId": runId, "device": "Apple iPhone 16e", "modelId": "parakeet-v2-coreml", "modelTitle": "Parakeet V2 · CoreML",
                    "engine": "fluidaudio", "options": "neural engine", "clipId": set == "technical" ? clip.id : "\(set)/\(clip.id)",
                    "audioSeconds": clip.seconds, "waitMs": Date().timeIntervalSince(started) * 1000, "loadMs": 0, "firstMs": firstMs,
                    "reference": clip.reference, "text": text, "referenceWords": 0, "wordErrors": 0, "memoryMb": 0,
                    "thermal": ProcessInfo.processInfo.thermalState.rawValue,
                ]
                lines.append(String(decoding: try JSONSerialization.data(withJSONObject: result), as: UTF8.self))
            }
            let out = URL.documentsDirectory.appending(path: "lab")
            try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
            try (lines.joined(separator: "\n") + "\n").write(to: out.appending(path: "\(set).jsonl"), atomically: true, encoding: .utf8)
            errorText = "Lab run finished · \(clips.count) clips"
        } catch {
            errorText = "Lab run failed · \(error.localizedDescription)"
        }
        phase = .idle
    }
    #endif

    private func format(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded(.down))
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// Thin bars driven by the take's loudness.
private struct LevelMeter: View {
    let engine: VoiceEngine
    @State private var history: [CGFloat] = Array(repeating: 0, count: 40)

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
            Canvas { gc, size in
                let w = size.width / CGFloat(history.count)
                for (i, v) in history.enumerated() {
                    let h = max(3, v * size.height)
                    let rect = CGRect(x: CGFloat(i) * w + w * 0.3, y: (size.height - h) / 2, width: w * 0.4, height: h)
                    gc.fill(Path(roundedRect: rect, cornerRadius: w * 0.2), with: .color(.white.opacity(0.85)))
                }
            }
            .onChange(of: context.date) {
                history.removeFirst()
                history.append(CGFloat(engine.level))
            }
        }
    }
}

private enum Palette {
    static let background = Color(red: 0.043, green: 0.047, blue: 0.055)
    static let panel = Color(red: 0.082, green: 0.086, blue: 0.094)
    static let dim = Color(red: 0.54, green: 0.56, blue: 0.60)
    static let faint = Color(red: 0.38, green: 0.40, blue: 0.43)
    static let amber = Color(red: 0.95, green: 0.60, blue: 0.29)
}
