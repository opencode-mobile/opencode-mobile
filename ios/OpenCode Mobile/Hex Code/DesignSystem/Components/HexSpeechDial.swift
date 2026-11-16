import SwiftUI

/// The speech model's dial (app plan, "Speech line and dial" › The dial): a ring with a small mic inside, drawn in a
/// 24-unit box. A `tint` 13 % track and an arc (2 units, round caps, from 12 o'clock): the share downloaded in
/// `text2`, or `text3` while the connection is down or nothing is on the phone yet; loading, a 28 % arc turning once
/// every 0.9 s; warming up, the full ring breathing (1.4 s); ready, the full ring in `green` with a check; failed,
/// `red` with "!". With Reduce Motion nothing turns or breathes: loading and warming up show the full ring, still.
/// The arc's progress moves linearly over 0.3 s. Android's `HexSpeechDial` is the same component.
struct HexSpeechDial: View {
    enum Look: Equatable {
        /// The share downloaded, 0 to 1.
        case downloading(Double)
        /// The connection is down, or the download hasn't started: grey, at the share downloaded.
        case stalled(Double)
        case loading
        case warmingUp
        case ready
        /// Where it stopped: the share downloaded, 1 when the model was on the phone.
        case failed(Double)
    }

    enum Size {
        /// 14 pt, in the draft's label line.
        case label
        /// 16 pt, in the speech line above the dock.
        case line
        /// 60 pt, at the top of the speech model's details.
        case sheet

        var points: CGFloat {
            switch self {
            case .label: 14
            case .line: 16
            case .sheet: 60
            }
        }
    }

    let look: Look
    var size: Size = .line

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
        .padding(side / 12)
        .frame(width: side, height: side)
        .overlay { glyph }
        .animation(.linear(duration: 0.3), value: progress)
        .accessibilityHidden(true)
    }

    private var side: CGFloat { size.points }

    /// The ring's stroke: 2 units of 24, a little finer at the sheet's size.
    private var line: CGFloat { side / 24 * (side >= 40 ? 1.6 : 2) }

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
        .frame(width: side, height: side)
    }
}
