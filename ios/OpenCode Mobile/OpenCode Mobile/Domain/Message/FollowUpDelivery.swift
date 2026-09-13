import Foundation

nonisolated enum FollowUpDelivery: String, Sendable, Equatable, Hashable, CaseIterable {
    case steer
    case queue

    var wireValue: String { rawValue }

    var switchedSnackbar: String {
        switch self {
        case .steer: "Switched to Steer"
        case .queue: "Switched to Queue"
        }
    }

    var toggled: FollowUpDelivery {
        switch self {
        case .steer: .queue
        case .queue: .steer
        }
    }

    static func fromWire(_ value: String?) -> FollowUpDelivery? {
        guard let value else { return nil }
        return FollowUpDelivery(rawValue: value.lowercased())
    }
}
