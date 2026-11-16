import Foundation
import SwiftUI
import Testing
@testable import OpenCode_Mobile

@MainActor
struct AppearancePreferencesTests {
    private func freshDefaults() -> UserDefaults {
        let name = "AppearancePreferencesTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func startsOnSystemModeAndDefaultSizes() {
        let preferences = AppearancePreferences(defaults: freshDefaults())
        #expect(preferences.mode == .system)
        for region in AppTextRegion.allCases {
            #expect(preferences.textSize(for: region) == .default)
        }
        #expect(!preferences.canResetTextSizes)
        #expect(!preferences.canResetAll)
    }

    @Test func modeAndSizesSurviveRelaunch() {
        let defaults = freshDefaults()
        let preferences = AppearancePreferences(defaults: defaults)
        preferences.setMode(.dark)
        preferences.setTextSize(.extraSmall, for: .homeList)
        preferences.setTextSize(.large, for: .sessionChat)
        preferences.setTextSize(.extraLarge, for: .code)

        let relaunched = AppearancePreferences(defaults: defaults)
        #expect(relaunched.mode == .dark)
        #expect(relaunched.homeListTextSize == .extraSmall)
        #expect(relaunched.sessionChatTextSize == .large)
        #expect(relaunched.codeTextSize == .extraLarge)
    }

    @Test func eachRegionKeepsItsOwnSize() {
        let preferences = AppearancePreferences(defaults: freshDefaults())
        preferences.setTextSize(.small, for: .sessionChat)
        #expect(preferences.homeListTextSize == .default)
        #expect(preferences.sessionChatTextSize == .small)
        #expect(preferences.codeTextSize == .default)
    }

    @Test func unknownStoredValuesFallBackToDefaults() {
        let defaults = freshDefaults()
        defaults.set("sepia", forKey: "appearanceMode")
        defaults.set("huge", forKey: "codeTextSize")
        let preferences = AppearancePreferences(defaults: defaults)
        #expect(preferences.mode == .system)
        #expect(preferences.codeTextSize == .default)
    }

    @Test func modeAloneEnablesOnlyResetAll() {
        let preferences = AppearancePreferences(defaults: freshDefaults())
        preferences.setMode(.light)
        #expect(!preferences.canResetTextSizes)
        #expect(preferences.canResetAll)
    }

    @Test func aChangedSizeEnablesBothResets() {
        let preferences = AppearancePreferences(defaults: freshDefaults())
        preferences.setTextSize(.large, for: .code)
        #expect(preferences.canResetTextSizes)
        #expect(preferences.canResetAll)
    }

    @Test func resetTextSizesKeepsTheMode() {
        let defaults = freshDefaults()
        let preferences = AppearancePreferences(defaults: defaults)
        preferences.setMode(.dark)
        preferences.setTextSize(.extraLarge, for: .homeList)
        preferences.setTextSize(.small, for: .code)

        preferences.resetTextSizes()

        #expect(preferences.mode == .dark)
        for region in AppTextRegion.allCases {
            #expect(preferences.textSize(for: region) == .default)
        }
        #expect(!preferences.canResetTextSizes)
        #expect(preferences.canResetAll)
        #expect(AppearancePreferences(defaults: defaults).homeListTextSize == .default)
    }

    @Test func resetAllRestoresSizesAndSystemMode() {
        let defaults = freshDefaults()
        let preferences = AppearancePreferences(defaults: defaults)
        preferences.setMode(.light)
        preferences.setTextSize(.small, for: .sessionChat)

        preferences.resetAll()

        #expect(preferences.mode == .system)
        #expect(preferences.sessionChatTextSize == .default)
        #expect(!preferences.canResetAll)
        #expect(AppearancePreferences(defaults: defaults).mode == .system)
    }

    @Test func clearRemovesStoredValues() {
        let defaults = freshDefaults()
        let preferences = AppearancePreferences(defaults: defaults)
        preferences.setMode(.dark)
        preferences.setTextSize(.large, for: .homeList)

        preferences.clear()

        #expect(preferences.mode == .system)
        #expect(preferences.homeListTextSize == .default)
        #expect(defaults.object(forKey: "appearanceMode") == nil)
        #expect(defaults.object(forKey: "homeListTextSize") == nil)
    }

    @Test func stepsRunFromSmallestToExtraLargeWithDefaultAtStepThree() {
        #expect(AppTextSize.allCases.map(\.factor) == [0.78, 0.85, 0.92, 1.0, 1.12, 1.26])
        #expect(AppTextSize.default.stepIndex == 3)
        #expect(AppTextSize.fromStepIndex(0) == .smallest)
        #expect(AppTextSize.fromStepIndex(5) == .extraLarge)
        #expect(AppTextSize.fromStepIndex(-1) == .smallest)
        #expect(AppTextSize.fromStepIndex(9) == .extraLarge)
    }

    @Test func modeMapsToTheWindowStyle() {
        #expect(AppearanceMode.system.userInterfaceStyle == .unspecified)
        #expect(AppearanceMode.light.userInterfaceStyle == .light)
        #expect(AppearanceMode.dark.userInterfaceStyle == .dark)
    }
}

@MainActor
struct OpenCodeTextScaleTests {
    @Test func defaultSizeAtTheDefaultPhoneSizeKeepsBaseSizes() {
        #expect(abs(OpenCodeTypography.textScale(size: .default, dynamicTypeSize: .large) - 1) < 0.001)
    }

    @Test func appSizeMultipliesThePhoneSize() {
        let phone = OpenCodeTypography.dynamicTypeMultiplier(.xxLarge)
        #expect(phone > 1)
        let scale = OpenCodeTypography.textScale(size: .large, dynamicTypeSize: .xxLarge)
        #expect(abs(scale - 1.12 * phone) < 0.001)
    }

    @Test func smallerPhoneSizeShrinksText() {
        #expect(OpenCodeTypography.dynamicTypeMultiplier(.xSmall) < 1)
    }

    @Test func accessibilitySizesAreCappedAtTheFirstOne() {
        #expect(
            OpenCodeTypography.dynamicTypeMultiplier(.accessibility5)
                == OpenCodeTypography.dynamicTypeMultiplier(.accessibility1)
        )
    }

    @Test func stepSliderSnapsToTheNearestStep() {
        // 24 pt knob: the track's steps sit 50 pt apart on a 224 pt track.
        #expect(OpenCodeStepSlider.index(at: 12, width: 224, stepCount: 5) == 0)
        #expect(OpenCodeStepSlider.index(at: 112, width: 224, stepCount: 5) == 2)
        #expect(OpenCodeStepSlider.index(at: 140, width: 224, stepCount: 5) == 3)
        #expect(OpenCodeStepSlider.index(at: -40, width: 224, stepCount: 5) == 0)
        #expect(OpenCodeStepSlider.index(at: 400, width: 224, stepCount: 5) == 4)
    }
}
