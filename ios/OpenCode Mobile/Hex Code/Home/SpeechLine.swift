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

    /// Megabytes as the model list counts them (1,000,000 bytes), rounded down, as on Android.
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

    /// The speech line shows while the model isn't ready, and for 1.6 s after it became ready ("Speech ready");
    /// then it is gone for the process.
    static func shows(_ status: SpeechStatus, readySince: TimeInterval?, now: TimeInterval) -> Bool {
        guard status == .ready else { return true }
        guard let readySince else { return false }
        return now - readySince < readyFor
    }

    static let readyFor: TimeInterval = 1.6
}

/// The speech model's dial (app plan, "Speech line and dial" › The dial): a ring with a small mic inside, drawn in a
/// 24-unit box. A `tint` 13 % track and an arc (2 units, round caps, from 12 o'clock): the share downloaded in
/// `text2`, or `text3` while the connection is down or nothing is on the phone yet; loading, a 28 % arc turning once
/// every 0.9 s; warming up, the full ring breathing (1.4 s); ready, the full ring in `green` with a check; failed,
/// `red` with "!". With Reduce Motion nothing turns or breathes: loading and warming up show the full ring, still.
struct SpeechDial: View {
    enum Look: Equatable {
        case downloading(Double)
        /// The connection is down, or the download hasn't started: grey.
        case stalled(Double)
        case loading
        case warmingUp
        case ready
        case failed(Double)

        /// `lastFraction`: the share downloaded when it stopped; 1 when the model was on the phone (a failed load).
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

    let look: Look
    /// 14 (the draft's label), 16 (the speech line) or 60 (the details sheet).
    var size: CGFloat = 16

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var turning = false
    @State private var breathing = false

    var body: some View {
        ZStack {
            Circle()
                .stroke(HexColor.tint(13), lineWidth: line)
            arc
        }
        // The ring's radius is 10 of the box's 24.
        .padding(size / 12)
        .frame(width: size, height: size)
        .overlay { glyph }
        .animation(.linear(duration: 0.3), value: progress)
        .accessibilityHidden(true)
    }

    /// The ring's stroke: 2 units of 24, a little finer at the sheet's size.
    private var line: CGFloat { size / 24 * (size >= 40 ? 1.6 : 2) }

    private var progress: Double {
        switch look {
        case let .downloading(fraction), let .stalled(fraction), let .failed(fraction): max(fraction, 0.02)
        case .loading, .warmingUp, .ready: 1
        }
    }

    private var color: Color {
        switch look {
        case .downloading, .loading, .warmingUp: HexColor.text2
        case .stalled: HexColor.text3
        case .ready: HexColor.green
        case .failed: HexColor.red
        }
    }

    @ViewBuilder
    private var arc: some View {
        switch look {
        case .loading where !reduceMotion:
            Circle()
                .trim(from: 0, to: 0.28)
                .stroke(color, style: StrokeStyle(lineWidth: line, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .rotationEffect(.degrees(turning ? 360 : 0))
                .onAppear {
                    turning = false
                    withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { turning = true }
                }
        case .warmingUp where !reduceMotion:
            Circle()
                .stroke(color, style: StrokeStyle(lineWidth: line, lineCap: .round))
                .opacity(breathing ? 1 : 0.45)
                .onAppear {
                    breathing = false
                    withAnimation(HexMotion.ease(0.7).repeatForever(autoreverses: true)) { breathing = true }
                }
        case .stalled(0):
            EmptyView()
        default:
            Circle()
                .trim(from: 0, to: progress)
                .stroke(color, style: StrokeStyle(lineWidth: line, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }

    private var glyph: some View {
        Canvas { context, canvas in
            let scale = canvas.width / 24
            context.scaleBy(x: scale, y: scale)
            switch look {
            case .ready:
                var check = Path()
                check.move(to: CGPoint(x: 7.6, y: 12.4))
                check.addLine(to: CGPoint(x: 10.6, y: 15.4))
                check.addLine(to: CGPoint(x: 16.4, y: 9.2))
                context.stroke(check, with: .color(color), style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
            case .failed:
                var bar = Path()
                bar.move(to: CGPoint(x: 12, y: 7.5))
                bar.addLine(to: CGPoint(x: 12, y: 12.7))
                context.stroke(bar, with: .color(color), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                context.fill(Path(ellipseIn: CGRect(x: 10.7, y: 15.1, width: 2.6, height: 2.6)), with: .color(color))
            default:
                // The prototype's mic at 0.44 of the box, centred.
                context.translateBy(x: 12, y: 12)
                context.scaleBy(x: 0.44, y: 0.44)
                context.translateBy(x: -12, y: -12)
                var mic = Path(roundedRect: CGRect(x: 9, y: 3, width: 6, height: 12), cornerRadius: 3)
                mic.move(to: CGPoint(x: 19, y: 11))
                mic.addArc(center: CGPoint(x: 12, y: 11), radius: 7, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
                mic.move(to: CGPoint(x: 12, y: 18))
                mic.addLine(to: CGPoint(x: 12, y: 21))
                context.stroke(mic, with: .color(color), style: StrokeStyle(lineWidth: 3.3, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(width: size, height: size)
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
                    SpeechDial(look: SpeechDial.Look(status, lastFraction: speech.lastDownload?.fraction ?? 1), size: 16)
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
