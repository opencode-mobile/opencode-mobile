import Foundation
import os

/// Hex Code's own log, under its bundle id. `log stream --predicate 'subsystem == "dev.opencode.hexcode"'`
/// (or Console with the phone connected) shows it.
enum HexLog {
    static let subsystem = "dev.opencode.hexcode"
    /// The talk button, the microphone and transcription.
    static let dictation = Logger(subsystem: subsystem, category: "dictation")
}

/// Milliseconds between two `CACurrentMediaTime`-style uptimes.
func hexMilliseconds(from start: TimeInterval, to end: TimeInterval) -> Int {
    Int(((end - start) * 1000).rounded())
}

/// Times one press of the talk button: touch down, the first frame drawn with the recording look,
/// and the microphone running. Logged at info level, for checks on a phone.
@MainActor
enum PressTiming {
    private static var touchDown: TimeInterval?
    private static var drawn = false

    static func began() {
        touchDown = ProcessInfo.processInfo.systemUptime
        drawn = false
    }

    static func recordingLookDrawn() {
        guard let touchDown, !drawn else { return }
        drawn = true
        HexLog.dictation.info("press: recording look drawn after \(hexMilliseconds(from: touchDown, to: ProcessInfo.processInfo.systemUptime)) ms")
    }

    static func microphoneRunning() {
        guard let touchDown else { return }
        HexLog.dictation.info("press: microphone running after \(hexMilliseconds(from: touchDown, to: ProcessInfo.processInfo.systemUptime)) ms")
    }
}
