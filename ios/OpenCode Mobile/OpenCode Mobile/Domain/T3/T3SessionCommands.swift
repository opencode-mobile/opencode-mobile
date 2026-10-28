import Foundation

/// Orchestration commands for thread lifecycle and settings that the session
/// repository dispatches. Shapes follow `orchestrationV2.ts`.
nonisolated enum T3SessionCommands {
    static func unarchiveCommand(commandID: String, threadID: String) -> [String: Any] {
        [
            "type": "thread.unarchive",
            "commandId": commandID,
            "threadId": threadID,
        ]
    }

    /// Re-activates a settled thread; the contract only allows the `user` reason from clients.
    static func unsettleCommand(commandID: String, threadID: String) -> [String: Any] {
        [
            "type": "thread.unsettle",
            "commandId": commandID,
            "threadId": threadID,
            "reason": "user",
        ]
    }

    /// `snoozedUntil` is an ISO-8601 timestamp.
    static func snoozeCommand(commandID: String, threadID: String, snoozedUntil: String) -> [String: Any] {
        [
            "type": "thread.snooze",
            "commandId": commandID,
            "threadId": threadID,
            "snoozedUntil": snoozedUntil,
        ]
    }

    /// Clears a snooze; the contract only allows the `user` reason from clients.
    static func unsnoozeCommand(commandID: String, threadID: String) -> [String: Any] {
        [
            "type": "thread.unsnooze",
            "commandId": commandID,
            "threadId": threadID,
            "reason": "user",
        ]
    }

    static func runtimeModeSetCommand(commandID: String, threadID: String, runtimeMode: String) -> [String: Any] {
        [
            "type": "thread.runtime-mode.set",
            "commandId": commandID,
            "threadId": threadID,
            "runtimeMode": runtimeMode,
        ]
    }

    static func modelSelectionSetCommand(commandID: String, threadID: String, selection: T3ModelSelection) -> [String: Any] {
        [
            "type": "thread.model-selection.set",
            "commandId": commandID,
            "threadId": threadID,
            "modelSelection": T3RpcCodec.modelSelectionPayload(selection),
        ]
    }
}
