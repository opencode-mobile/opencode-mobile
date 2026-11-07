import Foundation
import OpenCodeKit

/// Where a draft goes: today's session (`TodaySession`), or a fake in tests.
@MainActor
protocol MessageSending: AnyObject {
    /// The agent is working (or waiting on you): a message can go after this turn or now.
    var agentBusy: Bool { get }
    /// Sends a message with its images (none when the model can't see them); returns its inbox id.
    func send(_ text: String, images: [OutgoingImage], delivery: Delivery) async throws -> String
    /// Takes back a message still waiting to send.
    func cancelWaiting(_ itemID: String) async throws
    /// Returns once the event stream is up.
    func waitForConnection() async
    /// Today's session has a goal: the draft's menu offers Replace rather than Set.
    var hasGoal: Bool { get }
    /// Set as today's goal (with the draft's images); `.done` on `204`.
    func setGoal(_ text: String, images: [OutgoingImage], fromDraft: Bool) async -> GoalOutcome
    /// Ask on the side: answered from the session, not sent to the agent.
    func askOnTheSide(_ question: String)
}

extension MessageSending {
    var hasGoal: Bool { false }
    func setGoal(_ text: String, images: [OutgoingImage], fromDraft: Bool) async -> GoalOutcome { .failed }
    func askOnTheSide(_ question: String) {}
}

extension TodaySession: MessageSending {
    var hasGoal: Bool { goal != nil }
}

/// The After turn / Now switch on a draft while the agent works.
enum SendMode: Hashable, Sendable {
    /// `queue`: after this turn (the default, and what every send goes back to).
    case afterTurn
    /// `steer`, held on the phone for 3 s with Undo, starting `[Correction] `.
    case now
}

/// A Now held on the phone before it goes.
struct HeldMessage: Equatable {
    let id = UUID()
    let text: String
    let draft: DictationDraft
}

/// An image going with a message: the prepared JPEG and the name the server shows back.
nonisolated struct OutgoingImage: Equatable, Sendable {
    let name: String
    let image: PreparedImage

    static func == (lhs: OutgoingImage, rhs: OutgoingImage) -> Bool {
        lhs.name == rhs.name && lhs.image === rhs.image
    }

    /// The draft's prepared images, named "Photo 1.jpg", "Screenshot 1.jpg"…, each kind counted
    /// on its own, in draft order.
    static func from(_ images: [DraftImage]) -> [OutgoingImage] {
        var counts: [DraftImage.Kind: Int] = [:]
        return images.compactMap { image in
            guard let prepared = image.prepared else { return nil }
            let number = (counts[image.kind] ?? 0) + 1
            counts[image.kind] = number
            return OutgoingImage(name: "\(image.kind.namePrefix) \(number).jpg", image: prepared)
        }
    }
}

nonisolated extension DraftImage.Kind {
    /// Not translated: the name the agent sees.
    var namePrefix: String {
        switch self {
        case .photo: "Photo"
        case .screenshot: "Screenshot"
        }
    }
}

/// Whether today's model can see images (app plan, "Attach" › Model can't see images): from the
/// model's `capabilities.input` in `GET /api/model`. A model not in the list, or a list not read
/// yet, counts as yes.
struct ImageInput: Equatable, Sendable {
    var canSee: Bool
    /// The model's name, for "<Model> can't see images".
    var modelName: String

    static let unknown = ImageInput(canSee: true, modelName: "")

    static func of(_ choice: ModelChoice, in options: [ModelSelectionOption], savedName: String?) -> ImageInput {
        let option = options.first { $0.providerID == choice.providerID && $0.modelID == choice.modelID }
        return ImageInput(
            canSee: option?.supportsImageInput ?? true,
            modelName: savedName ?? option?.modelLabel ?? choice.modelID
        )
    }
}
