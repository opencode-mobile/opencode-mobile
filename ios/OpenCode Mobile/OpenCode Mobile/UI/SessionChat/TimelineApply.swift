import Foundation

/// Derived apply kinds for Session Chat hosts.
/// Canonical change surface remains `TimelineFrame` ID sets; this packet is classify-only.
enum TimelineApplyKind: String, Sendable {
    case structure
    case content
    case growBottom
    case decorate
    case required
}

/// Host-facing classify result. Scroll policy helpers match Phase 1.
struct TimelineApply: Equatable, Sendable {
    let kinds: Set<TimelineApplyKind>
    let frame: TimelineFrame

    var layoutAffectingRowIDs: Set<String> { frame.layoutAffectingRowIDs }

    var structureRowIDs: Set<String> {
        frame.insertedRowIDs
            .union(frame.removedRowIDs)
            .union(frame.layoutChangedRowIDs.subtracting(frame.decorateRowIDs))
    }

    var contentRowIDs: Set<String> {
        frame.changedRowIDs
            .subtracting(frame.layoutChangedRowIDs)
            .subtracting(frame.grewBottomRowIDs)
    }

    static func classify(_ frame: TimelineFrame) -> TimelineApply {
        var kinds = Set<TimelineApplyKind>()
        if frame.isRequired {
            kinds.insert(.required)
        }
        let structureIDs = frame.insertedRowIDs
            .union(frame.removedRowIDs)
            .union(frame.layoutChangedRowIDs.subtracting(frame.decorateRowIDs))
        if !structureIDs.isEmpty {
            kinds.insert(.structure)
        }
        if !frame.decorateRowIDs.isEmpty {
            kinds.insert(.decorate)
        }
        if !frame.grewBottomRowIDs.isEmpty {
            kinds.insert(.growBottom)
        }
        let contentIDs = frame.changedRowIDs
            .subtracting(frame.layoutChangedRowIDs)
            .subtracting(frame.grewBottomRowIDs)
        if !contentIDs.isEmpty {
            kinds.insert(.content)
        }
        return TimelineApply(kinds: kinds, frame: frame)
    }

    static func kindsLabel(_ kinds: Set<TimelineApplyKind>) -> String {
        kinds.map(\.rawValue).sorted().joined(separator: ",")
    }
}
