import Foundation
import Testing
@testable import OpenCode_Mobile

struct DictationOrbMotionTests {
    private let frame = 1.0 / 60.0

    @Test func smoothing_risesFasterThanItFalls() {
        let rising = DictationOrbMotion()
        _ = rising.advance(to: 0, at: 0)
        let rise = rising.advance(to: 1, at: frame)

        let falling = DictationOrbMotion()
        _ = falling.advance(to: 1, at: 0)
        for step in 1...60 { _ = falling.advance(to: 1, at: Double(step) * frame) }
        let peak = falling.value
        let fall = peak - falling.advance(to: 0, at: 61 * frame)

        #expect(rise > fall)
        #expect(peak > 0.99)
    }

    @Test func smoothing_movesGraduallyBetweenFrames() {
        let motion = DictationOrbMotion()
        _ = motion.advance(to: 1, at: 0)
        var previous = motion.value
        for step in 1...6 {
            let next = motion.advance(to: 1, at: Double(step) * frame)
            #expect(next > previous)
            #expect(next - previous < 0.3)
            previous = next
        }
    }

    @Test func barExtent_staysInRangeAndGrowsWithLevel() {
        for index in 0..<24 {
            for step in 0..<40 {
                let time = Double(step) * 0.05
                let quiet = DictationOrbMotion.barExtent(index: index, level: 0, time: time)
                let loud = DictationOrbMotion.barExtent(index: index, level: 1, time: time)
                #expect((0...0.1).contains(quiet))
                #expect((0...1).contains(loud))
                #expect(loud >= quiet + 0.3)
            }
        }
    }
}
