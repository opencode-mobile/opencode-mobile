import Foundation
import os
import UIKit

/// Hex Code's own log, under its bundle id. `log stream --predicate 'subsystem == "dev.opencode.hexcode"'`
/// (or Console with the phone connected) shows it.
enum HexLog {
    static let subsystem = "dev.opencode.hexcode"
    /// The talk button, the microphone and transcription.
    static let dictation = Logger(subsystem: subsystem, category: "dictation")
    /// The status summary's requests, and the ones not made because nothing is new.
    static let summary = Logger(subsystem: subsystem, category: "summary")
    /// The server checks at setup: the address in use and the sign-in's renewal. Never the token.
    static let server = Logger(subsystem: subsystem, category: "server")
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

/// Cold-start timing (app plan, "Cold start"): milliseconds from the process starting (its
/// `p_starttime`, close to the icon tap) to each mark. Logged at info level in the `startup`
/// category, and printed in debug builds (`devicectl device process launch --console` shows them).
@MainActor
enum StartupClock {
    private static let logger = Logger(subsystem: HexLog.subsystem, category: "startup")
    private static var marked: Set<String> = []

    /// When the process started, as a Unix time.
    nonisolated static let processStart: TimeInterval = {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0 else { return Date().timeIntervalSince1970 }
        let start = info.kp_proc.p_starttime
        return TimeInterval(start.tv_sec) + TimeInterval(start.tv_usec) / 1_000_000
    }()

    /// Milliseconds since the process started.
    static var elapsed: Int { Int(((Date().timeIntervalSince1970 - processStart) * 1000).rounded()) }

    /// Logs `what` once per process.
    static func mark(_ what: String) {
        guard marked.insert(what).inserted else { return }
        let ms = elapsed
        logger.info("startup: \(what, privacy: .public) at \(ms) ms")
        guard printsMarks else { return }
        print("HEXPROBE startup: \(what) at \(ms) ms")
        fflush(stdout)
    }

    /// Debug builds print every mark; `-HexCodeStartupProbe YES` prints them in a release build too,
    /// for the cold-start probe.
    private static let printsMarks: Bool = {
        #if DEBUG
        return true
        #else
        return UserDefaults.standard.bool(forKey: "HexCodeStartupProbe")
        #endif
    }()
}

/// Runs once, on the next screen refresh: by then the frame being built now is on screen.
@MainActor
final class NextFrame: NSObject {
    private static var waiting: Set<NextFrame> = []
    private var link: CADisplayLink?
    private let action: () -> Void

    private init(_ action: @escaping () -> Void) {
        self.action = action
    }

    static func after(_ action: @escaping () -> Void) {
        let next = NextFrame(action)
        let link = CADisplayLink(target: next, selector: #selector(tick))
        next.link = link
        waiting.insert(next)
        link.add(to: .main, forMode: .common)
    }

    /// Returns on the next screen refresh.
    static func wait() async {
        await withCheckedContinuation { continuation in after { continuation.resume() } }
    }

    @objc private func tick() {
        link?.invalidate()
        link = nil
        Self.waiting.remove(self)
        action()
    }
}
