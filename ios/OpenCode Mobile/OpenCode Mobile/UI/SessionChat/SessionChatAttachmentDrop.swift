import SwiftUI
import UIKit

/// How a pasted or dropped attachment reached the composer, for naming and analytics (#870).
enum SessionChatAttachmentSource: String {
    case paste
    case drop
}

/// The drag-over card through one drag: shown while attachable items are over the chat, cleared
/// when the drag leaves, drops or ends. A drag that carries only text shows no card and is left to
/// the system (the prompt field still takes it as text).
struct SessionChatAttachmentDropTracker {
    private(set) var summary: AttachmentDropSummary?

    /// The drag moved. `inside` is false while it is outside the chat or drops are off.
    mutating func update(inside: Bool, items: () -> [AttachmentDropItem], context: () -> AttachmentDropContext) {
        guard inside else {
            summary = nil
            return
        }
        if summary == nil { summary = AttachmentDropRouting.summary(items(), context: context()) }
    }

    /// What dropping here would do: `.cancel` when the drag is not the chat's to take.
    var operation: UIDropOperation {
        guard let summary else { return .cancel }
        return summary.rejected ? .forbidden : .copy
    }

    /// The drag left, was dropped, or ended anywhere, including after the chat declined it (#951).
    /// Returns whether a drop should attach.
    @discardableResult
    mutating func finish() -> Bool {
        let accepted = summary.map { !$0.rejected } ?? false
        summary = nil
        return accepted
    }
}

extension View {
    /// Makes Session Chat a drop target for images and files (#870). While attachable items are
    /// over the chat, `summary` drives the drag-over card.
    ///
    /// Drops go through UIKit: SwiftUI's drop delegate never reports the end of a drag it declined,
    /// so the "Can't attach this" card stayed after the drag (#951).
    func sessionChatAttachmentDrop(
        isEnabled: Bool,
        context: @escaping () -> AttachmentDropContext,
        summary: Binding<AttachmentDropSummary?>,
        onDrop: @escaping ([NSItemProvider]) -> Void
    ) -> some View {
        background(
            SessionChatAttachmentDropTarget(
                isEnabled: isEnabled,
                context: context,
                summary: summary,
                onDrop: onDrop
            )
        )
    }
}

/// An empty view behind the chat that marks its bounds and puts the drop interaction on the view
/// that hosts it. A drag looks for a drop interaction from the view under it upwards, so only an
/// ancestor of the timeline and composer sees drags over them.
private struct SessionChatAttachmentDropTarget: UIViewRepresentable {
    let isEnabled: Bool
    let context: () -> AttachmentDropContext
    @Binding var summary: AttachmentDropSummary?
    let onDrop: ([NSItemProvider]) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> AnchorView {
        let view = AnchorView()
        view.isUserInteractionEnabled = false
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ view: AnchorView, context: Context) {
        let coordinator = context.coordinator
        coordinator.isEnabled = isEnabled
        coordinator.context = self.context
        coordinator.onDrop = onDrop
        let summary = $summary
        coordinator.setSummary = { if summary.wrappedValue != $0 { summary.wrappedValue = $0 } }
    }

    static func dismantleUIView(_ view: AnchorView, coordinator: Coordinator) {
        coordinator.uninstall()
    }

    final class AnchorView: UIView {
        weak var coordinator: Coordinator?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            coordinator?.install(anchor: self)
        }
    }

    final class Coordinator: NSObject, UIDropInteractionDelegate {
        var isEnabled = false
        var context: () -> AttachmentDropContext = { AttachmentDropContext() }
        var onDrop: ([NSItemProvider]) -> Void = { _ in }
        var setSummary: (AttachmentDropSummary?) -> Void = { _ in }
        private var tracker = SessionChatAttachmentDropTracker()
        private weak var anchor: UIView?
        private lazy var interaction = UIDropInteraction(delegate: self)

        func install(anchor: UIView) {
            self.anchor = anchor
            interaction.view?.removeInteraction(interaction)
            // SwiftUI wraps the anchor in a host view of its own size; the view above that hosts the chat.
            guard anchor.window != nil, let host = anchor.superview?.superview else { return }
            host.addInteraction(interaction)
        }

        func uninstall() {
            interaction.view?.removeInteraction(interaction)
            finish()
        }

        @discardableResult
        func finish() -> Bool {
            let accepted = tracker.finish()
            setSummary(nil)
            return accepted
        }

        func dropInteraction(_ interaction: UIDropInteraction, canHandle session: UIDropSession) -> Bool {
            isEnabled && !Self.items(session).isEmpty
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidUpdate session: UIDropSession) -> UIDropProposal {
            // The hosting view can be wider than the chat (a split view sidebar); only the chat drops.
            let inside = anchor.map { $0.bounds.contains(session.location(in: $0)) } ?? false
            tracker.update(inside: isEnabled && inside, items: { Self.items(session) }, context: context)
            setSummary(tracker.summary)
            return UIDropProposal(operation: tracker.operation)
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidExit session: UIDropSession) {
            finish()
        }

        func dropInteraction(_ interaction: UIDropInteraction, performDrop session: UIDropSession) {
            let providers = session.items.map(\.itemProvider)
            if finish() { onDrop(providers) }
        }

        func dropInteraction(_ interaction: UIDropInteraction, sessionDidEnd session: UIDropSession) {
            finish()
        }

        private static func items(_ session: UIDropSession) -> [AttachmentDropItem] {
            session.items.compactMap { item in
                AttachmentItemProviders.kind(of: item.itemProvider).map {
                    AttachmentDropItem(kind: $0, filename: item.itemProvider.suggestedName)
                }
            }
        }
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
