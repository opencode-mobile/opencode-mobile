import SwiftUI
import UniformTypeIdentifiers

/// How a pasted or dropped attachment reached the composer, for naming and analytics (#870).
enum SessionChatAttachmentSource: String {
    case paste
    case drop
}

/// Makes Session Chat a drop target for images and files (#870). While accepted items are over the
/// chat, `summary` drives the drag-over card; a drag that carries only text shows no card and is
/// left to the system (the prompt field still takes it as text).
struct SessionChatAttachmentDropDelegate: DropDelegate {
    let isEnabled: Bool
    let context: () -> AttachmentDropContext
    @Binding var summary: AttachmentDropSummary?
    let onDrop: ([NSItemProvider]) -> Void

    static func items(_ providers: [NSItemProvider]) -> [AttachmentDropItem] {
        providers.compactMap { provider in
            AttachmentItemProviders.kind(of: provider).map {
                AttachmentDropItem(kind: $0, filename: provider.suggestedName)
            }
        }
    }

    func validateDrop(info: DropInfo) -> Bool {
        isEnabled && !Self.items(info.itemProviders(for: [.item])).isEmpty
    }

    func dropEntered(info: DropInfo) {
        summary = AttachmentDropRouting.summary(Self.items(info.itemProviders(for: [.item])), context: context())
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        guard let summary else { return DropProposal(operation: .forbidden) }
        return DropProposal(operation: summary.rejected ? .forbidden : .copy)
    }

    func dropExited(info: DropInfo) {
        summary = nil
    }

    func performDrop(info: DropInfo) -> Bool {
        let accepted = summary.map { !$0.rejected } ?? false
        summary = nil
        guard accepted else { return false }
        onDrop(info.itemProviders(for: [.item]))
        return true
    }
}

extension AttachmentDropKind {
    var attachmentVariant: OpenCodeAttachmentVariant {
        switch self {
        case .image: .image
        case .pdf: .pdf
        case .text: .text
        case .file, .folder: .file
        }
    }
}
