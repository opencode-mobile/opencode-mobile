import SwiftUI
import VoiceEngineKit

/// What the speech model's details sheet says (app plan, "Speech line and dial" › Details sheet), worked out from
/// the model's status so it can be tested without a view.
enum SpeechSheetCopy {
    enum Tone: Equatable {
        case plain
        case amber
        case green
        case red
    }

    /// The top: the title in its tone, a line under it and a quiet third line.
    struct Hero: Equatable {
        var title: String
        var tone: Tone
        var line: String
        var detail: String
    }

    enum Phase: Int, CaseIterable {
        case download, load, warmUp, ready
    }

    enum PhaseMark: Equatable {
        case done
        case now
        /// The current phase while the connection is down.
        case stalled
        /// Where it failed.
        case failed
        case later
    }

    /// True when the download got all of it (its bytes reached the total), or when there was no download because the
    /// model was already on the phone; false when it stopped short, or before its total was known.
    private static func downloadFinished(_ download: ModelDownload?) -> Bool {
        guard let download, let total = download.total else { return download == nil }
        return download.done >= total
    }

    /// `locale` formats the speed's decimal ("8.4" or "8,4"); tests pin it.
    static func hero(_ status: SpeechStatus, details: SpeechDetails, locale: Locale = .current) -> Hero {
        switch status {
        case .notOnPhone:
            return Hero(
                title: String(localized: "speech.sheet.not_on_phone", defaultValue: "Not on this phone yet"),
                tone: .plain,
                line: details.modelBytes.map { String(localized: "speech.sheet.to_download", defaultValue: "\(SpeechLine.megabytes($0)) MB to download") }
                    ?? String(localized: "speech.sheet.downloads_once", defaultValue: "Downloads once, then stays on this phone"),
                detail: String(localized: "speech.sheet.starts_soon", defaultValue: "starts in a moment, in the background")
            )
        case let .downloading(download):
            let dropped = download.connection != .flowing
            return Hero(
                title: dropped
                    ? String(localized: "speech.sheet.dropped", defaultValue: "Connection dropped")
                    : String(localized: "speech.sheet.downloading", defaultValue: "Downloading"),
                tone: dropped ? .amber : .plain,
                line: progressText(download) ?? String(localized: "speech.sheet.starting", defaultValue: "Starting the download"),
                detail: dropped
                    ? String(localized: "speech.sheet.picks_up", defaultValue: "picks up where it stopped")
                    : speed(download, locale: locale)
            )
        case .loading:
            return Hero(
                title: String(localized: "speech.sheet.loading", defaultValue: "Loading"),
                tone: .plain,
                line: String(localized: "speech.sheet.loading.line", defaultValue: "Reading the model into memory"),
                detail: String(localized: "speech.sheet.loading.detail", defaultValue: "a few seconds")
            )
        case .warmingUp:
            return Hero(
                title: String(localized: "speech.sheet.warming", defaultValue: "Warming up"),
                tone: .plain,
                line: String(localized: "speech.sheet.warming.line", defaultValue: "One silent test take"),
                detail: String(localized: "speech.sheet.warming.detail", defaultValue: "so your first take is as fast as the rest")
            )
        case .ready:
            return Hero(
                title: String(localized: "speech.sheet.ready", defaultValue: "Ready"),
                tone: .green,
                line: onPhone(details.modelBytes),
                detail: String(localized: "speech.sheet.ready.detail", defaultValue: "loaded · warmed up")
            )
        case .failed:
            let stopped = details.lastDownload.flatMap { downloadFinished($0) ? nil : progressText($0) }
            return Hero(
                title: String(localized: "home.speech.failed", defaultValue: "Speech model didn’t load"),
                tone: .red,
                line: stopped.map { String(localized: "speech.sheet.stopped", defaultValue: "Stopped at \($0)") } ?? onPhone(details.modelBytes),
                detail: String(localized: "speech.sheet.kept", defaultValue: "your takes are kept")
            )
        }
    }

    /// The download's progress as "120 of 640 MB"; nil before the total is known.
    static func progressText(_ download: ModelDownload) -> String? {
        guard let total = download.total else { return nil }
        let done = SpeechLine.megabytes(download.done)
        return String(localized: "speech.sheet.of", defaultValue: "\(done) of \(SpeechLine.megabytes(total)) MB")
    }

    /// "8.4 MB/s · about 2 min left", "8.4 MB/s · under a minute left", or "measuring speed…" for the first 3 s.
    static func speed(_ download: ModelDownload, locale: Locale = .current) -> String {
        guard let speed = download.bytesPerSecond else {
            return String(localized: "speech.sheet.measuring", defaultValue: "measuring speed…")
        }
        let rate = (speed / 1_000_000).formatted(.number.precision(.fractionLength(1)).locale(locale))
        switch download.secondsLeft.map(SpeechLine.minutesLeft) {
        case 0?:
            return String(localized: "speech.sheet.speed_soon", defaultValue: "\(rate) MB/s · under a minute left")
        case let minutes?:
            return String(localized: "speech.sheet.speed_left", defaultValue: "\(rate) MB/s · about \(minutes) min left")
        case nil:
            return String(localized: "speech.sheet.speed", defaultValue: "\(rate) MB/s")
        }
    }

    /// "640 MB on this phone".
    private static func onPhone(_ bytes: Int64?) -> String {
        guard let bytes else { return String(localized: "speech.sheet.on_phone_plain", defaultValue: "On this phone") }
        return String(localized: "speech.sheet.on_phone", defaultValue: "\(size(bytes)) on this phone")
    }

    /// A size for a row: whole megabytes, rounded down as every MB figure is (`SpeechLine.megabytes`), at least 1.
    static func size(_ bytes: Int64) -> String {
        let megabytes = max(1, SpeechLine.megabytes(bytes))
        return String(localized: "speech.sheet.mb", defaultValue: "\(megabytes) MB")
    }

    /// Download → Load → Warm up → Ready: done ones checked, the current one marked (amber while the connection is
    /// down, red where it failed).
    static func phases(_ status: SpeechStatus, lastDownload: ModelDownload?) -> [PhaseMark] {
        let current: Phase?
        var mark = PhaseMark.now
        switch status {
        case .notOnPhone: current = nil
        case let .downloading(download):
            current = .download
            if download.connection != .flowing { mark = .stalled }
        case .loading: current = .load
        case .warmingUp: current = .warmUp
        case .ready: return Phase.allCases.map { _ in .done }
        case .failed:
            current = downloadFinished(lastDownload) ? .load : .download
            mark = .failed
        }
        return Phase.allCases.map { phase in
            guard let current else { return .later }
            if phase.rawValue < current.rawValue { return .done }
            return phase == current ? mark : .later
        }
    }

    /// The download stopped short (a failed download rather than a failed load).
    static func failedDownloading(_ lastDownload: ModelDownload?) -> Bool {
        !downloadFinished(lastDownload)
    }
}

/// The speech model's details (app plan, "Speech line and dial" › Details sheet): the dial at 60 pt with what it is
/// doing, the phases, a status line when one applies (connection dropped with Try now, failed with Try again, a take
/// waiting), what runs on this phone and the pause detector. It updates live while open.
struct SpeechModelSheet: View {
    let home: HomeModel
    let onDone: () -> Void

    private var speech: any SpeechTranscribing { home.speech }

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("speech.sheet.title", defaultValue: "Speech model")),
            leading: { EmptyView() },
            trailing: { HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), action: onDone) }
        ) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                content(status: speech.status, details: speech.details, now: context.date)
            }
            .padding(.bottom, 16)
        }
    }

    private func content(status: SpeechStatus, details: SpeechDetails, now: Date) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            hero(status, details: details)
                .padding(.horizontal, 2)
                .padding(.top, 4)
                .padding(.bottom, 16)
            phases(status, lastDownload: details.lastDownload)
                .padding(.horizontal, 2)
                .padding(.bottom, 16)
            statusLines(status, details: details, now: now)
            onThisPhone(details)
            pauseDetector(details)
        }
    }

    // MARK: Top

    private func hero(_ status: SpeechStatus, details: SpeechDetails) -> some View {
        let hero = SpeechSheetCopy.hero(status, details: details)
        return HStack(spacing: 16) {
            HexSpeechDial(look: HexSpeechDial.Look(status, lastFraction: details.lastDownload?.fraction ?? 1), size: .sheet)
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: hero.title)
                    .hexFont(HexTextStyle.headline.sized(19))
                    .foregroundStyle(color(hero.tone))
                Text(verbatim: hero.line)
                    .hexFont(HexTextStyle.body.weight(.regular))
                    .foregroundStyle(HexColor.text1)
                    .padding(.top, 3)
                Text(verbatim: hero.detail)
                    .hexFont(.mono)
                    .foregroundStyle(HexColor.text3)
                    .padding(.top, 5)
            }
            .lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private func color(_ tone: SpeechSheetCopy.Tone) -> Color {
        switch tone {
        case .plain: HexColor.text1
        case .amber: HexColor.amber
        case .green: HexColor.green
        case .red: HexColor.red
        }
    }

    // MARK: Phases

    private func phases(_ status: SpeechStatus, lastDownload: ModelDownload?) -> some View {
        let marks = SpeechSheetCopy.phases(status, lastDownload: lastDownload)
        let names = [
            String(localized: "speech.sheet.phase.download", defaultValue: "Download"),
            String(localized: "speech.sheet.phase.load", defaultValue: "Load"),
            String(localized: "speech.sheet.phase.warm", defaultValue: "Warm up"),
            String(localized: "speech.sheet.phase.ready", defaultValue: "Ready"),
        ]
        return HStack(spacing: 0) {
            ForEach(Array(names.enumerated()), id: \.offset) { index, name in
                phase(name, mark: marks[index])
                if index < names.count - 1 {
                    Rectangle()
                        .fill(marks[index] == .done && (marks[index + 1] == .done || status == .ready) ? HexColor.green.opacity(0.45) : HexColor.line2)
                        .frame(height: 1)
                        .frame(minWidth: 6)
                        .padding(.horizontal, 6)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func phase(_ name: String, mark: SpeechSheetCopy.PhaseMark) -> some View {
        HStack(spacing: 6) {
            Group {
                switch mark {
                case .done:
                    HexIconView(.check, size: 13).foregroundStyle(HexColor.green)
                case .now:
                    Circle().fill(HexColor.text1).frame(width: 8, height: 8)
                        .background(Circle().fill(HexColor.tint(12)).frame(width: 14, height: 14))
                case .stalled:
                    Circle().fill(HexColor.amber).frame(width: 8, height: 8)
                case .failed:
                    Circle().fill(HexColor.red).frame(width: 8, height: 8)
                case .later:
                    Circle().fill(HexColor.text4).frame(width: 6, height: 6)
                }
            }
            .frame(width: 14, height: 14)
            Text(verbatim: name)
                .hexFont(HexTextStyle.caption.weight(mark == .now || mark == .stalled || mark == .failed ? .semibold : .regular))
                .foregroundStyle(phaseColor(mark))
                .lineLimit(1)
                .fixedSize()
        }
    }

    private func phaseColor(_ mark: SpeechSheetCopy.PhaseMark) -> Color {
        switch mark {
        case .done: HexColor.text2
        case .now, .stalled: HexColor.text1
        case .failed: HexColor.red
        case .later: HexColor.text3
        }
    }

    // MARK: Status lines

    @ViewBuilder
    private func statusLines(_ status: SpeechStatus, details: SpeechDetails, now: Date) -> some View {
        if case let .downloading(download) = status, download.connection != .flowing {
            let words = SpeechLine.of(status, now: now)
            statusBox(
                tone: .amber,
                icon: .wifi,
                title: {
                    if case let .retrying(seconds) = words {
                        return String(localized: "speech.sheet.retrying", defaultValue: "Retrying in \(seconds) s")
                    }
                    return String(localized: "speech.sheet.trying", defaultValue: "Trying again…")
                }(),
                detail: String(localized: "speech.sheet.retries", defaultValue: "Retries a few times, picking up where it stopped."),
                // No Try now while it is already trying again.
                action: words == .tryingAgain ? nil : AnyView(HexButton(LocalizedStringResource("speech.sheet.try_now", defaultValue: "Try now")) { home.speechTryNow() })
            )
        }
        if status == .failed {
            let downloading = SpeechSheetCopy.failedDownloading(details.lastDownload)
            let at = details.lastDownload.map { SpeechLine.megabytes($0.done) } ?? 0
            statusBox(
                tone: .red,
                icon: .alert,
                title: downloading
                    ? String(localized: "speech.sheet.kept_failing", defaultValue: "The download kept failing.")
                    : String(localized: "speech.sheet.load_failed", defaultValue: "The model didn’t load."),
                detail: downloading
                    ? String(localized: "speech.sheet.picks_up_at", defaultValue: "Try again picks up at \(at) MB.")
                    : String(localized: "speech.sheet.loads_again", defaultValue: "Try again loads it once more."),
                action: AnyView(HexButton(LocalizedStringResource("common.try_again", defaultValue: "Try again"), style: .primary) { home.speechTryAgain() })
            )
        }
        let waiting = home.draft.waitingTakes
        if waiting.count > 0, status != .ready {
            statusBox(
                tone: .plain,
                icon: .mic,
                title: waiting.count == 1
                    ? String(localized: "speech.sheet.waiting_one", defaultValue: "1 take waiting · \(DraftClock.format(waiting.duration))")
                    : String(localized: "speech.sheet.waiting", defaultValue: "\(waiting.count) takes waiting · \(DraftClock.format(waiting.duration))"),
                detail: String(localized: "speech.sheet.waiting.detail", defaultValue: "It turns into text as soon as the model is ready."),
                action: nil
            )
        }
    }

    private func statusBox(tone: SpeechSheetCopy.Tone, icon: HexIcon, title: String, detail: String, action: AnyView?) -> some View {
        let shape = RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
        let (fill, border, iconColor): (Color, Color, Color) = switch tone {
        case .amber: (HexColor.amberSoft, HexColor.amberLine, HexColor.amber)
        case .red: (HexColor.redSoft, HexColor.red.opacity(0.3), HexColor.red)
        case .plain, .green: (HexColor.panel2, HexColor.line, HexColor.text3)
        }
        return HStack(alignment: .top, spacing: 10) {
            HexIconView(icon, size: 15)
                .foregroundStyle(iconColor)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                    .hexFont(HexTextStyle.callout.weight(tone == .red ? .regular : .semibold))
                    .foregroundStyle(HexColor.text1)
                Text(verbatim: detail)
                    .hexFont(.footnote)
                    .foregroundStyle(HexColor.text2)
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            if let action {
                action.frame(maxHeight: .infinity, alignment: .center)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(fill, in: shape)
        .overlay(shape.strokeBorder(border, lineWidth: 1))
        .padding(.bottom, 14)
    }

    // MARK: On this phone

    private func onThisPhone(_ details: SpeechDetails) -> some View {
        HexDetailsSection(title: Text(LocalizedStringResource("speech.sheet.on_this_phone", defaultValue: "On this phone"))) {
            HexDetailRow(
                leading: .icon(.cpu, HexColor.text3),
                title: Text(verbatim: details.modelName),
                detail: Text(verbatim: details.modelBytes.map {
                    String(localized: "speech.sheet.model_detail", defaultValue: "Core ML · \(SpeechSheetCopy.size($0))")
                } ?? String(localized: "speech.sheet.model_detail_plain", defaultValue: "Core ML"))
            )
            HexDetailRow(
                leading: .icon(.term, HexColor.text3),
                title: Text(verbatim: details.runtimeName),
                detail: Text(verbatim: details.runtimeDetail)
            )
            HexDetailRow(
                leading: .icon(.lock, HexColor.text3),
                title: Text(LocalizedStringResource("speech.sheet.on_phone_runs", defaultValue: "Runs on this phone")),
                detail: Text(LocalizedStringResource("speech.sheet.never_leaves", defaultValue: "Speech never leaves it."))
            )
        }
    }

    // MARK: Pause detector

    private func pauseDetector(_ details: SpeechDetails) -> some View {
        let (tone, state): (HexDot.Tone, String) = switch details.pauseDetector {
        case .notOnPhone: (.neutral, String(localized: "speech.sheet.not_on_phone", defaultValue: "Not on this phone yet"))
        case .downloaded: (.green, String(localized: "speech.sheet.vad.downloaded", defaultValue: "Downloaded"))
        case .loading: (.neutral, String(localized: "speech.sheet.loading", defaultValue: "Loading"))
        case .ready: (.green, String(localized: "speech.sheet.ready", defaultValue: "Ready"))
        case .failed: (.red, String(localized: "speech.sheet.vad.failed", defaultValue: "Didn’t load"))
        }
        let name = details.pauseDetectorBytes.map { "\(details.pauseDetectorName) · \(SpeechSheetCopy.size($0))" } ?? details.pauseDetectorName
        return HexDetailsSection(title: Text(LocalizedStringResource("speech.sheet.pause_detector", defaultValue: "Pause detector"))) {
            HexDetailRow(
                leading: .icon(.pause, HexColor.text3),
                title: Text(verbatim: name),
                detail: Text(LocalizedStringResource("speech.sheet.vad.detail", defaultValue: "Cuts locked takes at pauses"))
            ) {
                HStack(spacing: 6) {
                    HexDot(tone: tone)
                    HexDetailTime(text: state)
                }
            }
        }
    }
}
