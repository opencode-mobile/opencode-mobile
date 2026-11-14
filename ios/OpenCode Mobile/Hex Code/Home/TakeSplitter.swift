import Foundation
import os
import VoiceEngineKit

/// Splits one take at its pauses while it records (app plan, "Locked takes"). The recorder hands it each buffer of
/// 16 kHz mono audio on the audio thread; it gathers 4096-sample blocks and runs them through the VAD on a task of
/// its own, never on the audio thread or the main thread. Each cut (`PauseCutter`) becomes a `TakePart`, handed to
/// `deliver` before the next block is looked at. Only the audio since the last cut is kept in memory.
nonisolated final class TakeSplitter: @unchecked Sendable {
    private let lock = NSLock()
    /// Audio from `base` on, under `lock`.
    private var samples: [Float] = []
    private var base = 0
    /// Samples not yet sent as a block, under `lock`.
    private var gathered = 0
    private var cancelled = false

    private let blocks: AsyncStream<[Float]>
    private let feed: AsyncStream<[Float]>.Continuation
    private var worker: Task<TakeSplit, Never>?

    private static let log = Logger(subsystem: "dev.opencode.hexcode", category: "dictation")

    /// `deliver` gets each part with the splitter that found it.
    init(finder: PauseFinder, deliver: @escaping @Sendable (TakeSplitter, TakePart) async -> Void) {
        (blocks, feed) = AsyncStream.makeStream(of: [Float].self, bufferingPolicy: .unbounded)
        let blocks = blocks
        worker = Task.detached(priority: .userInitiated) { [weak self] in
            var cutter = PauseCutter()
            var parts = 0
            var working = true
            for await block in blocks {
                guard working, let self else { continue }
                do {
                    let result = try await finder.add(block)
                    guard let range = cutter.add(count: block.count, probability: result.probability, pauseStart: result.pauseStart) else { continue }
                    let now = ProcessInfo.processInfo.systemUptime
                    let part = TakePart(
                        range: range,
                        samples: self.take(range),
                        foundAt: now,
                        sinceSpeech: result.pauseStart.map { Double(cutter.position - $0) / Double(PauseCutter.sampleRate) } ?? 0,
                        atPause: result.pauseStart != nil
                    )
                    parts += 1
                    await deliver(self, part)
                } catch {
                    // The rest of the take stays one part: everything after the last cut.
                    Self.log.error("parts: the VAD failed, so the rest of the take is one part: \(String(describing: error), privacy: .public)")
                    working = false
                }
            }
            guard let self, !self.isCancelled else { return TakeSplit(parts: parts, tail: nil) }
            let end = self.end
            let rest = working ? cutter.tail : cutter.lastCut ..< end
            let tail = rest.flatMap { $0.isEmpty ? nil : TakePart(range: $0, samples: self.take($0)) }
            return TakeSplit(parts: parts, tail: tail)
        }
    }

    /// On the audio thread: the next converted samples.
    func append(_ pointer: UnsafePointer<Float>, count: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard !cancelled else { return }
        samples.append(contentsOf: UnsafeBufferPointer(start: pointer, count: count))
        gathered += count
        while gathered >= PauseFinder.blockSize {
            let start = samples.count - gathered
            feed.yield(Array(samples[start ..< start + PauseFinder.blockSize]))
            gathered -= PauseFinder.blockSize
        }
    }

    /// The take ended (the recorder has stopped): the last, shorter block goes to the VAD, and once every block has
    /// been looked at (and every part delivered), the audio after the last cut comes back.
    func finish() async -> TakeSplit {
        flush()
        return await worker?.value ?? TakeSplit(parts: 0, tail: nil)
    }

    private func flush() {
        lock.lock()
        if gathered > 0 {
            feed.yield(Array(samples[(samples.count - gathered)...]))
            gathered = 0
        }
        lock.unlock()
        feed.finish()
    }

    /// The take was dropped: no more parts.
    func cancel() {
        lock.lock()
        cancelled = true
        samples = []
        lock.unlock()
        feed.finish()
        worker?.cancel()
    }

    private var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    private var end: Int {
        lock.lock()
        defer { lock.unlock() }
        return base + samples.count
    }

    /// The audio in `range`; what lies before its end is let go.
    private func take(_ range: Range<Int>) -> [Float] {
        lock.lock()
        defer { lock.unlock() }
        let lower = max(range.lowerBound, base) - base
        let upper = min(range.upperBound - base, samples.count)
        guard lower < upper else { return [] }
        let part = Array(samples[lower ..< upper])
        samples.removeFirst(upper)
        base += upper
        return part
    }
}
