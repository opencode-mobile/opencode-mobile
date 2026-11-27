import Foundation

/// What a pasted or dropped item is, decided before its bytes are read (#870).
nonisolated enum AttachmentDropKind: String, Equatable, Sendable {
    case image
    case pdf
    case text
    case file
    case folder
}

/// A pasted or dropped item. A drag names its items only loosely, so name and size may be unknown.
nonisolated struct AttachmentDropItem: Equatable, Sendable {
    let kind: AttachmentDropKind
    var filename: String?
    var byteCount: Int64?
}

/// The selected model, the server and the draft, as far as routing a dropped item needs them.
nonisolated struct AttachmentDropContext: Sendable {
    var imagesSupported = true
    var pdfSupported = true
    var uploadAvailable = false
    var draft: [Attachment] = []
}

nonisolated enum AttachmentDropRoute: Equatable, Sendable {
    case accepted
    case skipped(reason: AttachmentRejection)
}

/// The drag-over card: what a drop would attach, or why nothing can be.
nonisolated struct AttachmentDropSummary: Equatable, Sendable {
    let title: LocalizedStringResource
    let subtitle: LocalizedStringResource
    /// Kinds of the first accepted items, at most `AttachmentDropRouting.maxPreviews`.
    let previews: [AttachmentDropKind]
    /// Nothing in the drag can be attached: the card shows its error style.
    let rejected: Bool
}

/// Routes pasted and dropped items through the #644 policy before their bytes are read, and words
/// the drag-over card. Shared with Android through `shared/attachment-drop/attachment-drop-cases.json`.
nonisolated enum AttachmentDropRouting {
    static let maxPreviews = 4

    static func route(_ item: AttachmentDropItem, context: AttachmentDropContext) -> AttachmentDropRoute {
        if item.kind == .folder { return .skipped(reason: .folder) }
        if let filename = item.filename, let byteCount = item.byteCount,
           AttachmentDuplicate.isDuplicate(filename: filename, byteCount: byteCount, in: context.draft) {
            return .skipped(reason: .duplicate)
        }
        let fits = item.byteCount.map { $0 <= AttachmentLimits.maxDecodedBytes } ?? true
        let inline = switch item.kind {
        case .image: context.imagesSupported
        case .pdf: context.pdfSupported
        case .text: true
        case .file, .folder: false
        }
        if (inline && fits) || context.uploadAvailable { return .accepted }
        if item.kind == .image, !context.imagesSupported { return .skipped(reason: .modelWithoutImages) }
        if item.kind == .pdf, !context.pdfSupported { return .skipped(reason: .modelWithoutPDF) }
        if !fits { return .skipped(reason: .tooLarge()) }
        return .skipped(reason: .unsupportedType)
    }

    /// `nil` when `items` is empty (a text-only drag shows no card). `countKnown` is false when the
    /// platform names only the kinds in a drag, not how many items it holds.
    static func summary(
        _ items: [AttachmentDropItem],
        context: AttachmentDropContext,
        countKnown: Bool = true
    ) -> AttachmentDropSummary? {
        guard !items.isEmpty else { return nil }
        let routed = items.map { ($0, route($0, context: context)) }
        let accepted = routed.filter { $0.1 == .accepted }.map(\.0)
        let firstReason = routed.lazy.compactMap { entry -> AttachmentRejection? in
            if case let .skipped(reason) = entry.1 { return reason }
            return nil
        }.first
        let skipped = routed.count - accepted.count
        guard !accepted.isEmpty else {
            return AttachmentDropSummary(
                title: countKnown && items.count == 1
                    ? LocalizedStringResource("attachment_drop.reject_one", defaultValue: "Can't attach this")
                    : LocalizedStringResource("attachment_drop.reject_many", defaultValue: "Can't attach these"),
                // Every item was skipped, so there is a reason.
                subtitle: (firstReason ?? .unreadable).text,
                previews: [],
                rejected: true
            )
        }
        let images = accepted.allSatisfy { $0.kind == .image }
        let count = accepted.count
        let title: LocalizedStringResource = switch (countKnown, images) {
        case (true, true):
            LocalizedStringResource("attachment_drop.attach_images_count", defaultValue: "Drop to attach \(count) images")
        case (true, false):
            LocalizedStringResource("attachment_drop.attach_files_count", defaultValue: "Drop to attach \(count) files")
        case (false, true):
            LocalizedStringResource("attachment_drop.attach_images", defaultValue: "Drop to attach images")
        case (false, false):
            LocalizedStringResource("attachment_drop.attach_files", defaultValue: "Drop to attach files")
        }
        let subtitle: LocalizedStringResource
        if let firstReason {
            let skippedText = countKnown
                ? String(localized: LocalizedStringResource(
                    "attachment_drop.skipped_count",
                    defaultValue: "\(skipped) will be skipped"
                ))
                : String(localized: "attachment_drop.some_skipped", defaultValue: "Some will be skipped")
            subtitle = LocalizedStringResource(
                "attachment_drop.skipped_detail",
                defaultValue: "\(skippedText) · \(String(localized: firstReason.text))"
            )
        } else {
            subtitle = LocalizedStringResource(
                "attachment_drop.adds_to_draft",
                defaultValue: "Adds them to the message you are writing"
            )
        }
        return AttachmentDropSummary(
            title: title,
            subtitle: subtitle,
            previews: accepted.prefix(maxPreviews).map(\.kind),
            rejected: false
        )
    }

    /// `Pasted image N.png`, one past the highest N already in the draft.
    static func pastedImageName(draftFilenames: [String]) -> String {
        let highest = draftFilenames.compactMap { name -> Int? in
            guard name.hasPrefix("Pasted image "), name.hasSuffix(".png") else { return nil }
            let number = name.dropFirst("Pasted image ".count).dropLast(".png".count)
            guard !number.isEmpty, number.allSatisfy(\.isASCII), number.allSatisfy(\.isNumber) else { return nil }
            return Int(number)
        }.max() ?? 0
        return "Pasted image \(highest + 1).png"
    }
}
