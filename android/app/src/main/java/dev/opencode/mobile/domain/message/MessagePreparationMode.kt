package dev.opencode.mobile.domain.message

/**
 * Closed preparation intent for [MessageContentPreparer] / [DisplayMessagePipeline].
 * Replaces boolean flag combinations.
 */
enum class MessagePreparationMode {
    /** Live streaming prepare; plain text above the streaming size threshold. */
    Streaming,

    /** Usable cold history without starting missing markdown parses. */
    HistoryPlainFirst,

    /** Newest-first missing markdown for history enrichment. */
    HistoryEnrichment,

    /** Reuse last streaming document when identity and source text match. */
    FinalPlaceholder,

    /** Authoritative finalized markdown, including cache-hit and small history. */
    Final,

    ;

    /** Whether a successful prepare may fully replace the observation prepared cache. */
    val replacesPreparedCache: Boolean
        get() = this != HistoryPlainFirst
}
