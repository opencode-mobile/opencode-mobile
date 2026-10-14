import AVFoundation
import Foundation
import Speech

/// On-device composer dictation via SpeechAnalyzer. Does not use `SFSpeechRecognizer`.
@available(iOS 26, *)
@MainActor
final class IOS26ComposerDictationSession: ComposerDictationSession {
    private var runID = UUID()
    private var listening = false
    private var sessionActive = false
    private var tapInstalled = false
    private var audioGeneration = 0
    private let audioEngine = AVAudioEngine()
    private let inputBridge = DictationInputBridge()
    private var analyzer: SpeechAnalyzer?
    private var analysisTask: Task<Void, Never>?
    private var resultsTask: Task<Void, Never>?
    private var onPartial: (@MainActor (String, String) -> Void)?
    private var onLevel: (@MainActor (Double) -> Void)?
    private var onError: (@MainActor (String) -> Void)?
    private var committed = ""

    func probe() async -> DictationSupport {
        await IOS26DictationCatalog.probe()
    }

    func start(
        onPartial: @escaping @MainActor (String, String) -> Void,
        onLevel: @escaping @MainActor (Double) -> Void,
        onError: @escaping @MainActor (String) -> Void
    ) {
        stop()
        // stop() schedules session deactivation on its generation. Bump again so that
        // cleanup cannot deactivate the session this start is about to activate.
        audioGeneration += 1
        let runID = UUID()
        self.runID = runID
        self.onPartial = onPartial
        self.onLevel = onLevel
        self.onError = onError
        self.committed = ""
        listening = true
        inputBridge.setLevelHandler(onLevel)
        Task { @MainActor in
            await self.run(runID: runID)
        }
    }

    func stop() {
        let analyzer = self.analyzer
        let shouldDeactivate = sessionActive
        audioGeneration += 1
        let generation = audioGeneration
        listening = false
        runID = UUID()
        onPartial = nil
        onLevel = nil
        onError = nil
        inputBridge.setLevelHandler(nil)
        inputBridge.finish()
        resultsTask?.cancel()
        resultsTask = nil
        analysisTask?.cancel()
        analysisTask = nil
        self.analyzer = nil
        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        sessionActive = false
        guard analyzer != nil || shouldDeactivate else { return }
        Task {
            await analyzer?.cancelAndFinishNow()
            guard generation == self.audioGeneration, shouldDeactivate else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    func downloadModel() async {
        await IOS26DictationCatalog.download()
    }

    private func run(runID: UUID) async {
        guard self.runID == runID else { return }
        let granted = await Self.requestRecordPermission()
        guard self.runID == runID else { return }
        guard granted else {
            fail("Microphone access is off", runID: runID)
            return
        }
        guard let engine = await IOS26DictationCatalog.makeEngine() else {
            fail("Dictation stopped", runID: runID)
            return
        }
        guard self.runID == runID else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement)
            try session.setActive(true)
            sessionActive = true
        } catch {
            fail(error.localizedDescription, runID: runID)
            return
        }
        guard self.runID == runID else { return }

        let inputNode = audioEngine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            fail("Dictation stopped", runID: runID)
            return
        }
        var targetFormat = await SpeechAnalyzer.bestAvailableAudioFormat(
            compatibleWith: [engine.module],
            considering: inputFormat
        )
        if targetFormat == nil {
            targetFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [engine.module])
        }
        guard let targetFormat else {
            fail("Dictation stopped", runID: runID)
            return
        }
        guard self.runID == runID else { return }

        let converterBox: DictationAudioConverter?
        if inputFormat != targetFormat {
            guard let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
                fail("Dictation stopped", runID: runID)
                return
            }
            converterBox = DictationAudioConverter(converter: converter, format: targetFormat)
        } else {
            converterBox = nil
        }

        let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
        inputBridge.setContinuation(continuation)
        let bridge = inputBridge
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { buffer, _ in
            let level = DictationAudioLevel.rms(buffer)
            bridge.emitLevel(level)
            let owned: AVAudioPCMBuffer?
            if let converterBox {
                owned = converterBox.convert(buffer)
            } else {
                owned = DictationAudioLevel.copy(buffer)
            }
            guard let owned else { return }
            bridge.yield(AnalyzerInput(buffer: owned))
        }
        tapInstalled = true

        let analyzer = SpeechAnalyzer(modules: [engine.module])
        self.analyzer = analyzer
        do {
            try await analyzer.prepareToAnalyze(in: targetFormat)
        } catch {
            fail(error.localizedDescription, runID: runID)
            return
        }
        guard self.runID == runID else { return }

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            fail(error.localizedDescription, runID: runID)
            return
        }

        analysisTask = Task { @MainActor in
            do {
                try await analyzer.start(inputSequence: stream)
            } catch {
                guard !Task.isCancelled else { return }
                self.fail(error.localizedDescription, runID: runID)
            }
        }
        resultsTask = Task { @MainActor in
            do {
                try await engine.consume { text, isFinal in
                    Task { @MainActor [weak self] in
                        self?.handleResult(text: text, isFinal: isFinal, runID: runID)
                    }
                }
            } catch {
                guard !Task.isCancelled else { return }
                self.fail(error.localizedDescription, runID: runID)
            }
        }
    }

    private func handleResult(text: String, isFinal: Bool, runID: UUID) {
        guard self.runID == runID, listening else { return }
        if isFinal {
            if !text.isEmpty {
                committed += text
            }
            forward(committed: committed, volatile: "", runID: runID)
        } else {
            forward(committed: committed, volatile: text, runID: runID)
        }
    }

    private func forward(committed: String, volatile: String, runID: UUID) {
        guard self.runID == runID else { return }
        onPartial?(committed, volatile)
    }

    private func fail(_ message: String, runID: UUID) {
        guard self.runID == runID, listening else { return }
        let report = onError
        stop()
        report?(message)
    }

    private static func requestRecordPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}

@available(iOS 26, *)
private enum IOS26DictationCatalog {
    static func probe() async -> DictationSupport {
        let permission = AVAudioApplication.shared.recordPermission
        if SpeechTranscriber.isAvailable,
           let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale.current) {
            let installed = await SpeechTranscriber.installedLocales
            return support(locale: locale, installed: contains(locale, installed), permission: permission)
        }
        if let locale = await DictationTranscriber.supportedLocale(equivalentTo: Locale.current) {
            let installed = await DictationTranscriber.installedLocales
            return support(locale: locale, installed: contains(locale, installed), permission: permission)
        }
        return .hidden
    }

    static func download() async {
        guard let engine = await makeEngine() else { return }
        do {
            guard let request = try await AssetInventory.assetInstallationRequest(supporting: [engine.module]) else {
                return
            }
            try await request.downloadAndInstall()
        } catch {
            return
        }
    }

    static func makeEngine() async -> DictationEngine? {
        if SpeechTranscriber.isAvailable,
           let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale.current) {
            var preset = SpeechTranscriber.Preset.progressiveTranscription
            preset.reportingOptions.insert(.volatileResults)
            return .speech(SpeechTranscriber(locale: locale, preset: preset))
        }
        if let locale = await DictationTranscriber.supportedLocale(equivalentTo: Locale.current) {
            var preset = DictationTranscriber.Preset.progressiveLongDictation
            preset.reportingOptions.insert(.volatileResults)
            return .dictation(DictationTranscriber(locale: locale, preset: preset))
        }
        return nil
    }

    private static func support(
        locale: Locale,
        installed: Bool,
        permission: AVAudioApplication.recordPermission
    ) -> DictationSupport {
        if !installed { return .modelNotInstalled }
        if permission == .denied { return .permissionDenied }
        return .ready(languageLabel: languageLabel(for: locale), onDevice: true)
    }

    private static func contains(_ locale: Locale, _ installed: [Locale]) -> Bool {
        let target = locale.identifier(.bcp47)
        return installed.contains { $0.identifier(.bcp47) == target }
    }

    private static func languageLabel(for locale: Locale) -> String {
        if let code = locale.language.languageCode?.identifier,
           let name = Locale.current.localizedString(forLanguageCode: code),
           !name.isEmpty {
            return name
        }
        return locale.localizedString(forIdentifier: locale.identifier) ?? locale.identifier
    }
}

@available(iOS 26, *)
private enum DictationEngine {
    case speech(SpeechTranscriber)
    case dictation(DictationTranscriber)

    var module: any SpeechModule {
        switch self {
        case let .speech(transcriber):
            transcriber
        case let .dictation(transcriber):
            transcriber
        }
    }

    func consume(_ onResult: @escaping (String, Bool) -> Void) async throws {
        switch self {
        case let .speech(transcriber):
            for try await result in transcriber.results {
                onResult(String(result.text.characters), result.isFinal)
            }
        case let .dictation(transcriber):
            for try await result in transcriber.results {
                onResult(String(result.text.characters), result.isFinal)
            }
        }
    }
}

/// Tap-thread bridge. The audio callback must not touch the main-actor session.
@available(iOS 26, *)
private final class DictationInputBridge: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncStream<AnalyzerInput>.Continuation?
    private var onLevel: (@MainActor (Double) -> Void)?
    private var lastLevelAt: CFAbsoluteTime = 0

    func setContinuation(_ continuation: AsyncStream<AnalyzerInput>.Continuation) {
        lock.lock()
        self.continuation = continuation
        lock.unlock()
    }

    func setLevelHandler(_ handler: (@MainActor (Double) -> Void)?) {
        lock.lock()
        onLevel = handler
        lastLevelAt = 0
        lock.unlock()
    }

    func yield(_ input: AnalyzerInput) {
        lock.lock()
        let continuation = continuation
        lock.unlock()
        continuation?.yield(input)
    }

    func emitLevel(_ level: Double) {
        let now = CFAbsoluteTimeGetCurrent()
        lock.lock()
        let shouldSend = now - lastLevelAt >= 0.05
        if shouldSend { lastLevelAt = now }
        let handler = onLevel
        lock.unlock()
        guard shouldSend, let handler else { return }
        Task { @MainActor in
            handler(level)
        }
    }

    func finish() {
        lock.lock()
        let continuation = continuation
        self.continuation = nil
        lock.unlock()
        continuation?.finish()
    }
}

private final class DictationAudioConverter: @unchecked Sendable {
    private let converter: AVAudioConverter
    private let format: AVAudioFormat
    private let lock = NSLock()

    init(converter: AVAudioConverter, format: AVAudioFormat) {
        self.converter = converter
        self.format = format
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        lock.lock()
        defer { lock.unlock() }
        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: max(capacity, 1)) else {
            return nil
        }
        var error: NSError?
        var didProvide = false
        let status = converter.convert(to: output, error: &error) { _, outStatus in
            if didProvide {
                outStatus.pointee = .noDataNow
                return nil
            }
            didProvide = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard error == nil, status != .error, output.frameLength > 0 else { return nil }
        return output
    }
}

private enum DictationAudioLevel {
    static func rms(_ buffer: AVAudioPCMBuffer) -> Double {
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return 0 }
        var sum: Float = 0
        var count = 0
        if let channels = buffer.floatChannelData {
            accumulate(channels, channelCount: Int(buffer.format.channelCount), frames: frames, scale: 1, into: &sum, count: &count)
        } else if let channels = buffer.int16ChannelData {
            accumulate(
                channels,
                channelCount: Int(buffer.format.channelCount),
                frames: frames,
                scale: 1 / Float(Int16.max),
                into: &sum,
                count: &count
            )
        }
        guard count > 0 else { return 0 }
        let rms = Double(sqrt(sum / Float(count)))
        guard rms > 0 else { return 0 }
        // Loudness is logarithmic: map about -50 dB (room) to -20 dB (close speech) onto 0...1.
        let decibels = 20 * log10(rms)
        return min(1, max(0, (decibels + 50) / 30))
    }

    static func copy(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
            return nil
        }
        copy.frameLength = buffer.frameLength
        let frames = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        if let source = buffer.floatChannelData, let destination = copy.floatChannelData {
            for channel in 0..<channels {
                destination[channel].update(from: source[channel], count: frames)
            }
        } else if let source = buffer.int16ChannelData, let destination = copy.int16ChannelData {
            for channel in 0..<channels {
                destination[channel].update(from: source[channel], count: frames)
            }
        }
        return copy
    }

    private static func accumulate<T: BinaryInteger>(
        _ channels: UnsafePointer<UnsafeMutablePointer<T>>,
        channelCount: Int,
        frames: Int,
        scale: Float,
        into sum: inout Float,
        count: inout Int
    ) {
        for channel in 0..<channelCount {
            let samples = channels[channel]
            for index in 0..<frames {
                let sample = Float(samples[index]) * scale
                sum += sample * sample
                count += 1
            }
        }
    }

    private static func accumulate(
        _ channels: UnsafePointer<UnsafeMutablePointer<Float>>,
        channelCount: Int,
        frames: Int,
        scale: Float,
        into sum: inout Float,
        count: inout Int
    ) {
        for channel in 0..<channelCount {
            let samples = channels[channel]
            for index in 0..<frames {
                let sample = samples[index] * scale
                sum += sample * sample
                count += 1
            }
        }
    }
}
