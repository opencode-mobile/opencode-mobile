import Foundation

/// The progress callback of the prompt send running in this task, bound by the screen that sent it.
public enum PromptSendProgressReporting {
    @TaskLocal public static var handler: (@Sendable (Int64, Int64) -> Void)?
}
