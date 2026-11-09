import Foundation

/// v2 goal plugin wire shapes (`Goal.State`, `rpc.opencode.goal.changed`) to product types.
nonisolated enum SessionGoalAdapter {
    static let rpcID = "opencode.goal"
    static let changedEvent = "rpc.opencode.goal.changed"

    enum DecodeError: Error, Equatable {
        /// The body is not `{"output": Goal.State | null}`.
        case invalidOutput
    }

    /// `POST /api/rpc/opencode.goal/get` answers `{"output": Goal.State | null}`.
    static func goal(fromRPCOutput data: Data) throws -> SessionGoal? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw DecodeError.invalidOutput
        }
        guard let output = root["output"], !(output is NSNull) else { return nil }
        guard let object = output as? [String: Any], let goal = goal(from: object.compactMapValues(AnySendable.from)) else {
            throw DecodeError.invalidOutput
        }
        return goal
    }

    /// One `Goal.State`; nil when a required field is missing or the status is unknown.
    static func goal(from state: [String: AnySendable]) -> SessionGoal? {
        guard let id = nonEmpty(state["id"]?.stringValue),
              let objective = state["objective"]?.stringValue,
              let status = state["status"]?.stringValue.flatMap(SessionGoalStatus.init(rawValue:)),
              let rounds = state["rounds"]?.int64Value,
              let maxRounds = state["maxRounds"]?.int64Value
        else { return nil }
        return SessionGoal(
            id: id,
            objective: objective,
            status: status,
            reason: nonEmpty(state["reason"]?.stringValue),
            rounds: Int(rounds),
            maxRounds: Int(maxRounds),
            stalls: state["stalls"]?.int64Value.map { Int($0) },
            updatedAt: state["updatedAt"]?.int64Value ?? 0
        )
    }

    /// The change a `rpc.opencode.goal.changed` event carries (`data: {sessionID, goal | null}`);
    /// nil for any other event or a malformed one.
    static func change(from event: OpencodeEvent) -> SessionGoalChange? {
        guard event.type == changedEvent else { return nil }
        let properties = event.rawProperties ?? [:]
        guard let sessionID = nonEmpty(properties["sessionID"]?.stringValue) ?? nonEmpty(event.sessionID) else {
            return nil
        }
        switch properties["goal"] {
        case nil, .null?:
            return SessionGoalChange(sessionID: sessionID, goal: nil)
        case let .dictionary(state)?:
            guard let goal = goal(from: state) else { return nil }
            return SessionGoalChange(sessionID: sessionID, goal: goal)
        default:
            return nil
        }
    }

    /// The server's message for a failed goal command, e.g. "No active goal to pause.".
    static func commandErrorMessage(_ error: Error) -> String? {
        guard case let .http(_, body)? = error as? CommandError else { return nil }
        return ServerErrorMessage.userFacing(body)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
