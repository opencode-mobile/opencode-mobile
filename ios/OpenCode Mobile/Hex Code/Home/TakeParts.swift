import Foundation

/// A stretch of a locked take, cut at a pause while it records (app plan, "Locked takes").
nonisolated struct TakePart: Sendable, Equatable {
    /// Where it lies in the take, in 16 kHz samples from the start.
    let range: Range<Int>
    let samples: [Float]
    /// When the cut was found (system uptime), and how long the speech before it had ended by then.
    var foundAt: TimeInterval = 0
    var sinceSpeech: TimeInterval = 0
    /// Cut at a pause; false for a part cut past 12 s (at a dip, or its quietest block by 15 s), or the audio after the last cut.
    var atPause = false
}

/// How a take recorded with the pause finder on ended: how many parts it gave while recording, and the audio after
/// the last cut, nil when no speech was heard there.
nonisolated struct TakeSplit: Sendable, Equatable {
    let parts: Int
    let tail: TakePart?
}

/// Decides where a take is cut, from the VAD's blocks (app plan, "Locked takes"; spike S3, §5). Pure: it only sees
/// each block's length, probability and FluidAudio's pause event.
///
/// - A pause (FluidAudio's speech end, four silent 256 ms blocks) cuts in its middle: halfway between the end of
///   speech and the end of the block that completed the pause. With 4096-sample blocks that is the start of the
///   pause's third block. Parts touch; nothing is dropped.
/// - A part longer than 12 s ends at the first block below 0.9, cut in the middle of that block. If none comes by
///   15 s (run-on speech, where the VAD can stay at 1.00), it ends in the middle of the quietest block between 12 and
///   15 s (the earliest, on a tie).
/// - A cut is only made when speech was heard since the last one (some block at or above 0.5 overlaps the part);
///   otherwise the audio stays with the next part.
nonisolated struct PauseCutter: Equatable, Sendable {
    static let sampleRate = 16_000
    /// A part longer than this ends at the next dip.
    static let maxPart = 12 * sampleRate
    /// A part that reaches this without a dip ends at its quietest block after `maxPart`.
    static let hardMaxPart = 15 * sampleRate
    /// Below this, a block of a part over `maxPart` is a dip to cut at.
    static let dip: Float = 0.9
    /// From this, a block counts as speech heard (Silero's usual threshold; lower than the VAD's 0.85 for starting
    /// speech, so quiet words are never thrown away as silence).
    static let speech: Float = 0.5

    /// Where the current part starts.
    private(set) var lastCut = 0
    /// Samples seen so far.
    private(set) var position = 0
    /// The blocks since the last cut, with their probability.
    private var blocks: [(range: Range<Int>, probability: Float)] = []

    static func == (lhs: PauseCutter, rhs: PauseCutter) -> Bool {
        lhs.lastCut == rhs.lastCut && lhs.position == rhs.position && lhs.blocks.map(\.range) == rhs.blocks.map(\.range)
            && lhs.blocks.map(\.probability) == rhs.blocks.map(\.probability)
    }

    /// The next block. Returns the part it ends, if it ends one.
    mutating func add(count: Int, probability: Float, pauseStart: Int?) -> Range<Int>? {
        let block = position ..< position + count
        position += count
        blocks.append((block, probability))
        let cut: Int
        if let pauseStart {
            cut = (pauseStart + position) / 2
        } else if position - lastCut > Self.maxPart, probability < Self.dip {
            cut = block.lowerBound + count / 2
        } else if position - lastCut >= Self.hardMaxPart, let quietest = quietestLateBlock {
            cut = quietest
        } else {
            return nil
        }
        guard cut > lastCut else { return nil }
        let part = lastCut ..< cut
        guard heard(in: part) else { return nil }
        lastCut = cut
        blocks.removeAll { $0.range.upperBound <= cut }
        return part
    }

    /// After release: the audio since the last cut, or nil when no speech was heard there.
    var tail: Range<Int>? {
        let rest = lastCut ..< position
        return heard(in: rest) ? rest : nil
    }

    private func heard(in range: Range<Int>) -> Bool {
        !range.isEmpty && blocks.contains { $0.probability >= Self.speech && $0.range.overlaps(range) }
    }

    /// The middle of the quietest block whose middle lies between 12 and 15 s into the part (the earliest on a tie).
    private var quietestLateBlock: Int? {
        let late = (lastCut + Self.maxPart) ... (lastCut + Self.hardMaxPart)
        var best: (middle: Int, probability: Float)?
        for block in blocks {
            let middle = (block.range.lowerBound + block.range.upperBound) / 2
            guard late.contains(middle) else { continue }
            if best == nil || block.probability < best!.probability { best = (middle, block.probability) }
        }
        return best?.middle
    }
}

/// One locked take's parts in the draft: transcribed one at a time, in order; their texts joined with a space as
/// the engine wrote them. The take stays one chunk; this is what its text so far is made of.
nonisolated struct TakeParts: Equatable, Sendable {
    struct Part: Equatable, Sendable {
        var audio: TakePart
        var text: String?
        var failed = false
    }

    private(set) var parts: [Part] = []
    /// Parts are only transcribed once the take is locked: a held take is transcribed whole after release.
    private(set) var locked = false
    /// Still recording: more parts may come.
    private(set) var recording = true

    mutating func add(_ part: TakePart) {
        parts.append(Part(audio: part))
    }

    mutating func lock() { locked = true }

    /// Released: the audio after the last cut is the last part, if it holds speech.
    mutating func end(tail: TakePart?) {
        if let tail { parts.append(Part(audio: tail)) }
        recording = false
    }

    /// The next part to transcribe, in order: the first one without text that hasn't failed.
    var next: Int? {
        guard locked else { return nil }
        return parts.firstIndex { $0.text == nil && !$0.failed }
    }

    mutating func finish(_ index: Int, text: String) {
        parts[index].text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        parts[index].failed = false
    }

    mutating func fail(_ index: Int) { parts[index].failed = true }

    /// Every part that hasn't got its text yet fails (the model didn't load).
    mutating func failPending() {
        for index in parts.indices where parts[index].text == nil { parts[index].failed = true }
    }

    /// Try again: failed parts are transcribed again.
    mutating func retry() {
        for index in parts.indices where parts[index].failed { parts[index].failed = false }
    }

    var hasFailed: Bool { parts.contains(where: \.failed) }
    /// Released and every part has text (or failed).
    var isSettled: Bool { !recording && next == nil }
    /// The text so far: finished parts in order, joined with one space. A part still waiting keeps its place, so
    /// later text only shows once the parts before it have theirs.
    var text: String {
        parts.prefix { $0.text != nil }.compactMap(\.text).filter { !$0.isEmpty }.joined(separator: " ")
    }
}
