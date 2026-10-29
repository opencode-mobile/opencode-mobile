import Foundation

/// Closed preparation intent for `MessageContentPreparer` / `DisplayMessagePipeline`.
/// Replaces boolean flag combinations.
nonisolated enum MessagePreparationMode: String, Sendable {
    /// Live streaming prepare. No size skip; the pipeline colors fences after the structure frame.
    case streaming
    /// Usable cold history without starting missing markdown parses.
    case historyPlainFirst
    /// Newest-first missing markdown for history enrichment.
    case historyEnrichment
    /// Reuse last streaming document when identity and source text match.
    case finalPlaceholder
    /// Authoritative finalized markdown, including cache-hit and small history.
    case final

    /// Whether a successful prepare may fully replace the observation prepared cache.
    var replacesPreparedCache: Bool {
        self != .historyPlainFirst
    }
}
