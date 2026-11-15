import SwiftUI
import VoiceEngineKit

/// What the speech line above the dock and the draft's label line say about the speech model (app plan, "Speech
/// line and dial"). The draft's label adds the time left to a download; the speech line leaves it out.
enum SpeechLine: Equatable, Sendable {
    /// The download hasn't started.
    case notOnPhone
    /// "120 of 640 MB", with the time left once the speed is known: whole minutes rounded up, 0 for "under a
    /// minute left". `totalMB` is nil before the first bytes on a fresh install.
    case downloading(doneMB: Int, totalMB: Int?, minutesLeft: Int?)
    /// The connection dropped: the next try in this many seconds.
    case retrying(inSeconds: Int)
    /// The connection dropped, and it is trying again now.
    case tryingAgain
    /// Loading and warming up read the same.
    case loading
    case ready
    case failed

    static func of(_ status: SpeechStatus, now: Date) -> SpeechLine {
        switch status {
        case .notOnPhone: .notOnPhone
        case let .downloading(download): of(download, now: now)
        case .loading, .warmingUp: .loading
        case .ready: .ready
        case .failed: .failed
        }
    }

    private static func of(_ download: ModelDownload, now: Date) -> SpeechLine {
        switch download.connection {
        case let .waiting(retryAt):
            let seconds = Int(retryAt.timeIntervalSince(now).rounded(.up))
            return seconds > 0 ? .retrying(inSeconds: seconds) : .tryingAgain
        case .retrying:
            return .tryingAgain
        case .flowing:
            return .downloading(
                doneMB: megabytes(download.done),
                totalMB: download.total.map(megabytes),
                minutesLeft: download.secondsLeft.map(minutesLeft)
            )
        }
    }

    /// Megabytes as the model list counts them (1,000,000 bytes), rounded down, as on Android. The one rule for every
    /// MB figure the speech line, the draft label and the details sheet show.
    static func megabytes(_ bytes: Int64) -> Int {
        Int(max(0, bytes) / 1_000_000)
    }

    /// Whole minutes, rounded up; 0 for under a minute.
    static func minutesLeft(_ seconds: Int) -> Int {
        seconds < 60 ? 0 : (seconds + 59) / 60
    }

    /// The words. `timeLeft` adds "· 2 min left" to a download (the draft's label).
    func text(timeLeft: Bool) -> String {
        switch self {
        case .notOnPhone:
            String(localized: "speech.line.not_on_phone", defaultValue: "Speech model not on this phone yet")
        case let .downloading(done, total?, minutes):
            switch timeLeft ? minutes : nil {
            case 0?:
                String(localized: "speech.line.downloading_soon", defaultValue: "Speech model · \(done) of \(total) MB · under a minute left")
            case let minutes?:
                String(localized: "speech.line.downloading_left", defaultValue: "Speech model · \(done) of \(total) MB · \(minutes) min left")
            case nil:
                String(localized: "speech.line.downloading", defaultValue: "Speech model · \(done) of \(total) MB")
            }
        case .downloading(_, nil, _):
            String(localized: "speech.line.downloading_unknown", defaultValue: "Speech model · downloading…")
        case let .retrying(seconds):
            String(localized: "speech.line.retrying", defaultValue: "Speech model · retrying in \(seconds) s")
        case .tryingAgain:
            String(localized: "speech.line.trying", defaultValue: "Speech model · trying again…")
        case .loading:
            String(localized: "home.speech.loading", defaultValue: "Speech model loading…")
        case .ready:
            String(localized: "speech.line.ready", defaultValue: "Speech ready")
        case .failed:
            String(localized: "home.speech.failed", defaultValue: "Speech model didn’t load")
        }
    }
}

/// What the speech line above the dock shows (app plan, "Speech line and dial" › Speech line), as home's state.
enum SpeechLineShow: Equatable, Sendable {
    /// Nothing: the model was ready before the line showed, or the line has had its moment.
    case hidden
    /// The model is getting ready, or failed.
    case gettingReady
    /// "Speech ready" with the green dial, for `SpeechLineMemory.readyFor`, then gone.
    case ready
}

/// The speech line's memory for the whole process: whether it showed the model getting ready, and whether it has
/// gone. Once gone it doesn't come back in this process, whichever home is on screen (as Android's
/// `SpeechLineMemory`).
@MainActor
final class SpeechLineMemory {
    /// How long "Speech ready" shows before the line goes.
    static let readyFor: Duration = .milliseconds(1600)
    /// The process's one memory.
    static let process = SpeechLineMemory()

    private var sawGettingReady = false
    private var gone = false

    /// The line has had its moment (or had nothing to say): it won't show again in this process.
    var isGone: Bool { gone }

    /// What the line shows next for `status`; `.ready`, said once per process, is the cue to hide it after `readyFor`.
    func next(_ status: SpeechStatus) -> SpeechLineShow {
        if gone { return .hidden }
        guard status == .ready else {
            sawGettingReady = true
            return .gettingReady
        }
        // Gone from now on, so a home made during the 1.6 s (or after it) doesn't say it again.
        gone = true
        // Ready before the line ever showed: nothing to say.
        return sawGettingReady ? .ready : .hidden
    }
}

extension HexSpeechDial.Look {
    /// The dial for the model's status. `lastFraction`: the share downloaded when it stopped; 1 when the model was on
    /// the phone (a failed load).
    init(_ status: SpeechStatus, lastFraction: Double = 1) {
        switch status {
        case .notOnPhone: self = .stalled(0)
        case let .downloading(download):
            self = download.connection == .flowing ? .downloading(download.fraction) : .stalled(download.fraction)
        case .loading: self = .loading
        case .warmingUp: self = .warmingUp
        case .ready: self = .ready
        case .failed: self = .failed(lastFraction)
        }
    }
}

/// The speech line (app plan, "Speech line and dial" › Speech line): a quiet row above the dock, in the waiting
/// line's slot and look, with the dial at 16 pt, the words and a chevron. Tapping it opens the details sheet.
struct SpeechLineView: View {
    let speech: any SpeechTranscribing
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous)
        let status = speech.status
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Button(action: action) {
                HStack(spacing: 8) {
                    HexSpeechDial(look: HexSpeechDial.Look(status, lastFraction: speech.lastDownload?.fraction ?? 1), size: .line)
                    Text(verbatim: SpeechLine.of(status, now: context.date).text(timeLeft: false))
                        .hexFont(HexTextStyle.small.weight(.regular))
                        .foregroundStyle(HexColor.text2)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    if status != .ready {
                        HexIconView(.chevR, size: HexIconSize.small)
                            .foregroundStyle(HexColor.text3)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(HexColor.panel, in: shape)
                .overlay(shape.strokeBorder(HexColor.line, lineWidth: 1))
                .contentShape(shape)
            }
            .buttonStyle(HexPressStyle())
            .accessibilityHint(Text(LocalizedStringResource("speech.line.hint", defaultValue: "Shows the speech model’s details")))
        }
    }
}
