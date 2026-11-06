import Foundation

/// One take in the draft. A take is transcribed once it ends, so it moves
/// Listening → (Waiting for speech, while the model loads) → Transcribing → text, and Send (later)
/// waits for every chunk to have text.
struct DraftChunk: Identifiable, Equatable, Sendable {
    enum State: Equatable, Sendable {
        case listening
        /// Released before the speech model was ready; its audio waits.
        case waiting
        case transcribing
        case text(String)
        case failed(String)
    }

    let id: UUID
    var state: State
    /// Length of the take, set when it ends.
    var duration: TimeInterval = 0
    /// While a chunk is re-recorded, its earlier text, kept until the new take lands.
    var previous: (text: String, duration: TimeInterval)?

    var text: String? {
        if case let .text(text) = state { return text }
        return nil
    }

    static func == (lhs: DraftChunk, rhs: DraftChunk) -> Bool {
        lhs.id == rhs.id && lhs.state == rhs.state && lhs.duration == rhs.duration
            && lhs.previous?.text == rhs.previous?.text && lhs.previous?.duration == rhs.previous?.duration
    }
}

/// A screenshot or photo going with the draft (app plan, "Attach"): preparing, then ready.
nonisolated struct DraftImage: Identifiable, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case photo
        case screenshot
    }

    let id: UUID
    let kind: Kind
    /// Nil while it is prepared.
    var prepared: PreparedImage?

    init(id: UUID = UUID(), kind: Kind, prepared: PreparedImage? = nil) {
        self.id = id
        self.kind = kind
        self.prepared = prepared
    }

    var isPreparing: Bool { prepared == nil }

    static func == (lhs: DraftImage, rhs: DraftImage) -> Bool {
        lhs.id == rhs.id && lhs.kind == rhs.kind && lhs.prepared === rhs.prepared
    }
}

/// The dictation draft on the home screen: takes become chunks; a chunk can be selected to
/// delete or re-record it; Clear empties it. Images can go with it. Nothing here sends anything.
struct DictationDraft: Equatable, Sendable {
    /// More chunks than this always shows one row per chunk (app plan, "Long drafts").
    static let longChunks = 3
    /// More text than this adds the word count to the draft's label.
    static let longCharacters = 220
    /// Rows the long list shows at most.
    static let maxRows = 8
    /// Images per message at most (app plan, "Attach" › Limit).
    static let maxImages = 4

    private(set) var chunks: [DraftChunk] = []
    /// The chunk tapped for Delete or Re-record.
    private(set) var selectedID: UUID?
    /// The take being recorded now, and whether it replaces a chunk.
    private(set) var recordingID: UUID?
    private(set) var isRerecording = false
    /// Screenshots and photos, in the order they were picked.
    private(set) var images: [DraftImage] = []

    var isEmpty: Bool { chunks.isEmpty && images.isEmpty }
    var isListening: Bool { recordingID != nil }
    var isTranscribing: Bool { chunks.contains { $0.state == .transcribing } }
    var isWaiting: Bool { chunks.contains { $0.state == .waiting } }
    var hasFailed: Bool { chunks.contains { if case .failed = $0.state { true } else { false } } }
    /// An image is still being prepared: Send reads "Preparing".
    var isPreparing: Bool { images.contains(where: \.isPreparing) }
    /// Every chunk has text and every image is prepared: what Send will wait for.
    var isComplete: Bool { !isEmpty && chunks.allSatisfy { $0.text != nil } && !isPreparing }
    /// Images that can still be added.
    var remainingImages: Int { max(0, Self.maxImages - images.count) }
    var selectedIndex: Int? { selectedID.flatMap(index(of:)) }
    var recordingIndex: Int? { recordingID.flatMap(index(of:)) }

    var totalDuration: TimeInterval { chunks.reduce(0) { $0 + $1.duration } }
    var text: String { chunks.compactMap(\.text).joined(separator: " ") }
    var wordCount: Int { text.split(whereSeparator: \.isWhitespace).count }

    /// Long enough for the word count in the label. Rows are `DraftLayout`'s call.
    var isLong: Bool {
        chunks.count > Self.longChunks || chunks.compactMap(\.text).joined(separator: " ").count > Self.longCharacters
    }

    func index(of id: UUID) -> Int? { chunks.firstIndex { $0.id == id } }

    // MARK: Text

    /// Adds a chunk that already has its text (a tapped Next), after the others.
    mutating func addText(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        chunks.append(DraftChunk(id: UUID(), state: .text(trimmed)))
        selectedID = nil
    }

    // MARK: Takes

    /// Starts a take. With a chunk selected the take re-records it; otherwise it adds a new chunk.
    /// Returns the chunk the take belongs to, or `nil` while another take is running.
    @discardableResult
    mutating func beginTake() -> UUID? {
        guard recordingID == nil else { return nil }
        if let selected = selectedIndex {
            let chunk = chunks[selected]
            chunks[selected].previous = chunk.text.map { ($0, chunk.duration) } ?? chunk.previous
            chunks[selected].state = .listening
            recordingID = chunk.id
            isRerecording = true
        } else {
            let chunk = DraftChunk(id: UUID(), state: .listening)
            chunks.append(chunk)
            recordingID = chunk.id
            isRerecording = false
        }
        selectedID = nil
        return recordingID
    }

    /// Ends the take: the chunk waits for its text.
    mutating func endTake(duration: TimeInterval) {
        guard let index = recordingIndex else { return }
        chunks[index].state = .transcribing
        chunks[index].duration = duration
        recordingID = nil
        isRerecording = false
    }

    /// Drops the take: a new chunk disappears, a re-recorded one gets its earlier text back.
    mutating func dropTake() {
        guard let index = recordingIndex else { return }
        restoreOrRemove(at: index)
        recordingID = nil
        isRerecording = false
    }

    /// The take's text arrived. Silence (no text) removes a new chunk and keeps a re-recorded one's earlier text.
    mutating func finishTranscription(_ id: UUID, text: String) {
        guard let index = index(of: id), chunks[index].state == .transcribing else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            restoreOrRemove(at: index)
        } else {
            chunks[index].state = .text(trimmed)
            chunks[index].previous = nil
        }
    }

    mutating func failTranscription(_ id: UUID, message: String) {
        guard let index = index(of: id), [.transcribing, .waiting].contains(chunks[index].state) else { return }
        chunks[index].state = .failed(message)
    }

    /// The take waits for the speech model (from Transcribing, or a failed take tried again).
    mutating func markWaiting(_ id: UUID) {
        guard let index = index(of: id) else { return }
        switch chunks[index].state {
        case .transcribing, .failed: chunks[index].state = .waiting
        default: return
        }
        if selectedID == id { selectedID = nil }
    }

    /// The take's turn to be transcribed. False when it is no longer waiting for that.
    mutating func beginTranscribing(_ id: UUID) -> Bool {
        guard let index = index(of: id), [.transcribing, .waiting].contains(chunks[index].state) else { return false }
        chunks[index].state = .transcribing
        return true
    }

    // MARK: Chunks

    /// Taps a chunk; tapping it again deselects it. Only settled chunks can be picked, and not while recording.
    mutating func toggleSelection(_ id: UUID) {
        guard !isListening, let index = index(of: id) else { return }
        switch chunks[index].state {
        case .text, .failed:
            selectedID = selectedID == id ? nil : id
        case .listening, .waiting, .transcribing:
            return
        }
    }

    mutating func deselect() { selectedID = nil }

    /// Deletes the selected chunk and returns what Undo needs.
    mutating func deleteSelected() -> (index: Int, chunk: DraftChunk)? {
        guard !isListening, let index = selectedIndex else { return nil }
        let chunk = chunks.remove(at: index)
        selectedID = nil
        return (index, chunk)
    }

    mutating func restore(_ chunk: DraftChunk, at index: Int) {
        chunks.insert(chunk, at: min(max(0, index), chunks.count))
    }

    /// Puts a draft that didn't go back, ahead of anything said since. Its images come first;
    /// images added since that would go past the limit are dropped.
    mutating func restore(contentsOf saved: DictationDraft) {
        chunks = saved.chunks + chunks
        images = Array((saved.images + images).prefix(Self.maxImages))
        selectedID = nil
    }

    /// Empties the draft, images too. Not while a take is running.
    mutating func clear() {
        guard !isListening else { return }
        chunks = []
        images = []
        selectedID = nil
    }

    // MARK: Images

    /// Adds an image that is being prepared. False at the limit.
    @discardableResult
    mutating func addImage(_ image: DraftImage) -> Bool {
        guard images.count < Self.maxImages, !images.contains(where: { $0.id == image.id }) else { return false }
        images.append(image)
        return true
    }

    /// The image is prepared. False when it was removed meanwhile.
    @discardableResult
    mutating func finishImage(_ id: UUID, _ prepared: PreparedImage) -> Bool {
        guard let index = images.firstIndex(where: { $0.id == id }) else { return false }
        images[index].prepared = prepared
        return true
    }

    /// ✕ on a thumbnail, or an image that couldn't be read.
    mutating func removeImage(_ id: UUID) {
        images.removeAll { $0.id == id }
    }

    // MARK: Long list

    /// Which chunk rows the long list shows: the newest `maxRows` (one fewer while a new take
    /// records under them), moved so that the selected or re-recorded chunk is always in view.
    func visibleRange(showEarlier: Bool = false) -> Range<Int> {
        let count = chunks.count
        let slots = isListening && !isRerecording ? Self.maxRows - 1 : Self.maxRows
        // A new take's live row sits under the list, so it is not one of the rows counted here.
        let listed = isListening && !isRerecording ? count - 1 : count
        var start = showEarlier ? 0 : max(0, listed - slots)
        for focus in [selectedIndex, isRerecording ? recordingIndex : nil].compactMap({ $0 })
            where focus < start || focus >= start + slots {
            start = min(focus, max(0, listed - slots))
        }
        return start ..< min(listed, start + slots)
    }

    private mutating func restoreOrRemove(at index: Int) {
        if let previous = chunks[index].previous {
            chunks[index].state = .text(previous.text)
            chunks[index].duration = previous.duration
            chunks[index].previous = nil
        } else {
            chunks.remove(at: index)
        }
    }
}

/// What the draft's label line says (app plan, "The speech model" › Showing it): the take being
/// recorded; else, only while a take waits for speech, the model's progress; "Speech model didn't
/// load" when it failed and takes are left without text; else Transcribing; else the summary.
enum DraftStatusLine: Equatable, Sendable {
    case recording
    case speechDownloading(percent: Int)
    case speechLoading
    case speechFailed
    case transcribing
    case summary

    static func of(isRecording: Bool, draft: DictationDraft, speech: SpeechStatus) -> DraftStatusLine {
        if isRecording { return .recording }
        if draft.isWaiting {
            switch speech {
            case let .downloading(percent): return .speechDownloading(percent: percent)
            case .failed: return .speechFailed
            case .loading, .ready: return .speechLoading
            }
        }
        if speech == .failed, draft.hasFailed { return .speechFailed }
        if draft.isTranscribing { return .transcribing }
        return .summary
    }
}

/// The draft's type as it grows (app plan, "Long drafts"): `draft` (24) up to 150 characters,
/// then 21, 19 and 17 (`nowMedium`, `nowSmall`, `nowFit`, the draft's medium weight), each step
/// chosen by measuring the draft as drawn.
enum DraftSize: Int, CaseIterable, Comparable, Sendable {
    case regular
    case medium
    case small
    case fit

    static let regularCharacters = 150

    static func < (lhs: DraftSize, rhs: DraftSize) -> Bool { lhs.rawValue < rhs.rawValue }

    /// The sizes to try, largest first.
    static func candidates(forCharacters count: Int) -> [DraftSize] {
        let start: DraftSize = count <= regularCharacters ? .regular : .medium
        return allCases.filter { $0 >= start }
    }
}

/// How the draft shows (app plan, "Long drafts"): running text as long as it can, one row per
/// chunk only with more than 3 chunks, or with 2 or more whose text no longer fits at the smallest
/// size. A single chunk never becomes a list: it scrolls, kept at the newest words.
enum DraftLayout: Equatable, Sendable {
    case text
    case rows
    case scrolling

    /// What shows when the text doesn't fit at the smallest size (or before measuring, with
    /// more than 3 chunks).
    static func whenTooLong(chunkCount: Int) -> DraftLayout {
        chunkCount >= 2 ? .rows : .scrolling
    }

    static func of(chunkCount: Int, fitsAtSmallest: Bool) -> DraftLayout {
        if chunkCount > DictationDraft.longChunks { return .rows }
        return fitsAtSmallest ? .text : whenTooLong(chunkCount: chunkCount)
    }
}
