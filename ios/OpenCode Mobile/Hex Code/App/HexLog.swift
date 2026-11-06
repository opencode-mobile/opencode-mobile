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
/// the microphone running and the first audio buffer. Logged at info level, and printed in debug
/// builds (the press probe reads them from the console).
@MainActor
enum PressTiming {
    private static var touchDown: TimeInterval?
    private static var drawn = false
    private static var heard = false

    static func began() {
        touchDown = ProcessInfo.processInfo.systemUptime
        drawn = false
        heard = false
    }

    static func recordingLookDrawn() {
        guard let touchDown, !drawn else { return }
        drawn = true
        report("recording look drawn", since: touchDown)
    }

    static func microphoneRunning() {
        guard let touchDown else { return }
        report("microphone running", since: touchDown)
    }

    static func firstAudio() {
        guard let touchDown, !heard else { return }
        heard = true
        report("first audio", since: touchDown)
    }

    private static func report(_ what: String, since start: TimeInterval) {
        let ms = hexMilliseconds(from: start, to: ProcessInfo.processInfo.systemUptime)
        HexLog.dictation.info("press: \(what, privacy: .public) after \(ms) ms")
        #if DEBUG
        print("HEXPROBE press: \(what) after \(ms) ms")
        #endif
    }
}
