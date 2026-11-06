import SwiftUI

/// The live level meter: thin bars that scroll left as the level comes in. `level` is the bar
/// height for now, 0…1 (see `HexMeterLevel.height(dBFS:)`), or nil while no audio has arrived:
/// then the bars run a gentle listening wave, and real levels blend in over 80 ms once audio comes,
/// so the meter moves from the first frame. It rises fast and falls slowly on real levels, and bars
/// at 0 rest as dots.
struct HexMeter: View {
    let level: () -> Double?
    var bars = 36

    @State private var history: [CGFloat] = []
    @State private var envelope: Double = 0
    @State private var lastFrame: Date?
    @State private var started = Date()
    @State private var audioSince: Date?

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { context in
            Canvas { gc, size in
                let now = context.date
                let time = now.timeIntervalSince(started)
                let sinceAudio = audioSince.map { now.timeIntervalSince($0) }
                let w = size.width / CGFloat(bars)
                let dot = min(3, w * 0.4)
                for i in 0 ..< bars {
                    let real = history.count == bars ? Double(history[i]) : 0
                    let v = HexMeterLevel.blend(
                        listening: HexMeterLevel.listening(bar: i, at: time),
                        real: real,
                        sinceAudio: sinceAudio
                    )
                    let h = max(dot, CGFloat(v * HexMeterLevel.variation(bar: i)) * size.height)
                    let rect = CGRect(x: CGFloat(i) * w + w * 0.3, y: (size.height - h) / 2, width: w * 0.4, height: h)
                    gc.fill(Path(roundedRect: rect, cornerRadius: w * 0.2), with: .style(HexColor.text1))
                }
            }
            .onChange(of: context.date) { _, now in
                let elapsed = lastFrame.map { now.timeIntervalSince($0) } ?? 1.0 / 30
                lastFrame = now
                guard let target = level() else { return }
                if audioSince == nil {
                    // Audio just arrived: start the real history from the wave, so nothing jumps.
                    audioSince = now
                    let time = now.timeIntervalSince(started)
                    history = (0 ..< bars).map { CGFloat(HexMeterLevel.listening(bar: $0, at: time)) }
                    envelope = Double(history.last ?? 0)
                }
                envelope = HexMeterLevel.follow(envelope, towards: min(1, max(0, target)), elapsed: elapsed)
                var next = history.count == bars ? history : Array(repeating: 0, count: bars)
                next.removeFirst()
                next.append(CGFloat(envelope))
                history = next
            }
        }
        .accessibilityHidden(true)
    }
}
