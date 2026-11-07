import SwiftUI
import Testing
@testable import Hex_Code

/// Reduce Motion (app plan, "Motion and Reduce Motion"): no movement and no looping decoration;
/// pulses rest at their visible state; spinners, the stop timer and the meter's levels keep moving.
struct ReduceMotionTests {
    @Test func stateChangesAreInstant() {
        #expect(HexMotion.animation(HexMotion.fade, reduced: true) == nil)
        #expect(HexMotion.animation(HexMotion.fade, reduced: false) != nil)
    }

    @Test func pressFeedbackKeepsItsColourButNotItsScale() {
        #expect(HexMotion.pressScale(0.96, pressed: true, reduced: true) == 1)
        #expect(HexMotion.pressScale(0.96, pressed: true, reduced: false) == 0.96)
        #expect(HexMotion.pressScale(0.96, pressed: false, reduced: false) == 1)
    }

    @Test func pulsingDotsRestAtFullColour() {
        #expect(HexDot.pulse(for: .red, reduced: true) == nil)
        #expect(HexDot.pulse(for: .syncing, reduced: true) == nil)
        #expect(HexDot.pulse(for: .red, reduced: false) != nil)
        #expect(HexDot.pulse(for: .green, reduced: false) == nil)
    }

    @Test func theListeningWaveStandsStill() {
        let still = (0 ..< 36).map { HexMeterLevel.listening(bar: $0, at: 0) }
        for time in [0.3, 1.7, 12.0] {
            #expect((0 ..< 36).map { HexMeterLevel.listening(bar: $0, at: HexMeterLevel.waveTime(time, reduced: true)) } == still)
        }
        #expect(HexMeterLevel.waveTime(1.7, reduced: false) == 1.7)
    }

    @Test func theMeterFollowsTheVoiceAtOnce() {
        #expect(HexMeterLevel.follow(0.1, towards: 0.8, elapsed: 1.0 / 30, reduced: true) == 0.8)
        #expect(HexMeterLevel.follow(0.1, towards: 0.8, elapsed: 1.0 / 30, reduced: false) < 0.8)
    }

    @Test func theHoldHintDoesntFollowTheDrag() {
        #expect(HexMotion.follow(40, reduced: true) == 0)
        #expect(HexMotion.follow(40, reduced: false) == 40)
    }
}

/// Increase Contrast (design system, Colours): `text3` draws as `text2`, `text4` as `text3`.
struct IncreaseContrastTests {
    @Test func theFaintTextTokensStepUp() {
        for dark in [true, false] {
            #expect(HexColor.resolved(HexColor.text3, dark: dark, increasedContrast: true) == HexColor.resolved(HexColor.text2, dark: dark, increasedContrast: false))
            #expect(HexColor.resolved(HexColor.text4, dark: dark, increasedContrast: true) == HexColor.resolved(HexColor.text3, dark: dark, increasedContrast: false))
            #expect(HexColor.resolved(HexColor.text3, dark: dark, increasedContrast: false) != HexColor.resolved(HexColor.text2, dark: dark, increasedContrast: false))
        }
    }
}
