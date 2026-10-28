import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionChatDisplayPreferencesTests {
    @Test func missingKey_defaultsOn() {
        let suite = "SessionChatDisplayPreferencesTests.default"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.collapseConsecutiveToolCalls == true)
    }

    @Test func storedOff_isPreserved() {
        let suite = "SessionChatDisplayPreferencesTests.stored"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(false, forKey: "collapseConsecutiveToolCalls")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.collapseConsecutiveToolCalls == false)
    }

    @Test func clear_restoresDefaultOn() {
        let suite = "SessionChatDisplayPreferencesTests.clear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.setCollapseConsecutiveToolCalls(false)

        preferences.clear()

        #expect(preferences.collapseConsecutiveToolCalls == true)
        #expect(defaults.object(forKey: "collapseConsecutiveToolCalls") == nil)
        #expect(preferences.followUpDelivery == .steer)
        #expect(defaults.object(forKey: "followUpDelivery") == nil)
    }

    @Test func chipTimeline_defaultsOff() {
        let suite = "SessionChatDisplayPreferencesTests.chipDefault"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.chipTimeline == false)
    }

    @Test func chipTimeline_setPersistsAndReloads() {
        let suite = "SessionChatDisplayPreferencesTests.chipStored"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.setChipTimeline(true)

        #expect(preferences.chipTimeline == true)
        #expect(defaults.bool(forKey: "chipTimeline") == true)
        #expect(SessionChatDisplayPreferences(defaults: defaults).chipTimeline == true)
    }

    @Test func chipTimeline_clearResetsOff() {
        let suite = "SessionChatDisplayPreferencesTests.chipClear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.setChipTimeline(true)

        preferences.clear()

        #expect(preferences.chipTimeline == false)
        #expect(defaults.object(forKey: "chipTimeline") == nil)
    }

    @Test func chipTimelineIntro_laterPersistsSnoozeThenSecondLaterEndsIntro() {
        let suite = "SessionChatDisplayPreferencesTests.chipIntroLater"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.postponeChipTimelineIntro(nowMs: 1_000)
        let reloaded = SessionChatDisplayPreferences(defaults: defaults)

        #expect(reloaded.chipTimelineIntroSnoozedUntilMs == 1_000 + ChipTimelineIntro.snoozeMs)
        #expect(reloaded.chipTimelineIntroDone == false)

        reloaded.postponeChipTimelineIntro(nowMs: 1_000 + ChipTimelineIntro.snoozeMs)

        #expect(SessionChatDisplayPreferences(defaults: defaults).chipTimelineIntroDone)
    }

    @Test func chipTimelineIntro_settingsChoiceEndsIntro() {
        let suite = "SessionChatDisplayPreferencesTests.chipIntroSettings"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.setChipTimeline(false)

        #expect(preferences.chipTimelineIntroDone)
        #expect(!preferences.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: 1_000))
    }

    @Test func chipTimelineIntro_clearResetsIntro() {
        let suite = "SessionChatDisplayPreferencesTests.chipIntroClear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.postponeChipTimelineIntro(nowMs: 1_000)
        preferences.finishChipTimelineIntro()

        preferences.clear()

        #expect(preferences.chipTimelineIntroDone == false)
        #expect(preferences.chipTimelineIntroSnoozedUntilMs == nil)
        #expect(defaults.object(forKey: "chipTimelineIntroDone") == nil)
        #expect(defaults.object(forKey: "chipTimelineIntroSnoozedUntilMs") == nil)
    }

    @Test func install_freshResolvesActivityCardAndSkipsThePicker() {
        let suite = "SessionChatDisplayPreferencesTests.freshInstall"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.resolveTimelineForInstall(existingInstall: false)

        #expect(preferences.timelineStyle == .activityCard)
        #expect(preferences.timelineStylePickerDone == true)
        #expect(!preferences.timelineStylePickerPending)
        let reloaded = SessionChatDisplayPreferences(defaults: defaults)
        #expect(reloaded.timelineStyle == .activityCard)
        #expect(reloaded.timelineStylePickerDone == true)
        #expect(!preferences.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: 1_000))
    }

    @Test func install_existingResolvesStandardAndOffersThePicker() {
        let suite = "SessionChatDisplayPreferencesTests.existingInstall"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let flagDefaults = UserDefaults(suiteName: suite + ".flags")!
        flagDefaults.removePersistentDomain(forName: suite + ".flags")
        let flags = FeatureFlags(defaults: flagDefaults)
        flags.setOverride(.sessionChatChipTimeline, true)
        let preferences = SessionChatDisplayPreferences(defaults: defaults, featureFlags: flags)

        preferences.resolveTimelineForInstall(existingInstall: true)

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.object(forKey: "chipTimeline") as? Bool == false)
        #expect(preferences.timelineStylePickerPending)
        #expect(SessionChatDisplayPreferences(defaults: defaults).timelineStylePickerPending)
        // The picker replaces the Chip intro while it is pending.
        #expect(!preferences.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: 1_000))
    }

    @Test func install_existingKeepsAStoredChipsStyle() {
        let suite = "SessionChatDisplayPreferencesTests.existingChips"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set("chips", forKey: "timelineStyle")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.resolveTimelineForInstall(existingInstall: true)

        #expect(preferences.timelineStyle == .chips)
        #expect(preferences.timelineStylePickerPending)
    }

    @Test func install_clearAllDataSettlesAsFreshInstall() {
        let suite = "SessionChatDisplayPreferencesTests.clearAllData"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.resolveTimelineForInstall(existingInstall: true)

        // Clear All Data, as AppContainer.clearAllData runs it.
        preferences.clear()
        preferences.resolveTimelineForInstall(existingInstall: false)

        #expect(preferences.timelineStyle == .activityCard)
        #expect(!preferences.timelineStylePickerPending)
        // The next launch, already past onboarding with a profile, must not offer the picker.
        let relaunched = SessionChatDisplayPreferences(defaults: defaults)
        relaunched.resolveTimelineForInstall(existingInstall: true)
        #expect(relaunched.timelineStyle == .activityCard)
        #expect(!relaunched.timelineStylePickerPending)
        #expect(!relaunched.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: 1_000))
    }

    @Test func install_resolveKeepsStoredValues() {
        let suite = "SessionChatDisplayPreferencesTests.resolveStored"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.resolveTimelineForInstall(existingInstall: false)

        // A later launch past onboarding must not flip a fresh install back or reopen the picker.
        preferences.resolveTimelineForInstall(existingInstall: true)
        #expect(preferences.timelineStyle == .activityCard)
        #expect(!preferences.timelineStylePickerPending)

        preferences.setChipTimeline(false)
        preferences.resolveTimelineForInstall(existingInstall: false)
        #expect(preferences.timelineStyle == .standard)
    }

    @Test func picker_anyStyleChoiceEndsIt() {
        let suite = "SessionChatDisplayPreferencesTests.pickerChoice"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.resolveTimelineForInstall(existingInstall: true)

        preferences.setTimelineStyle(.stepRail)

        #expect(preferences.timelineStyle == .stepRail)
        #expect(!preferences.timelineStylePickerPending)
        #expect(preferences.chipTimelineIntroDone)
        #expect(SessionChatDisplayPreferences(defaults: defaults).timelineStylePickerDone == true)
    }

    @Test func picker_dismissStoresActivityCard() {
        let suite = "SessionChatDisplayPreferencesTests.pickerDismiss"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set("chips", forKey: "timelineStyle")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.resolveTimelineForInstall(existingInstall: true)

        preferences.dismissTimelineStylePicker()

        #expect(preferences.timelineStyle == .activityCard)
        #expect(!preferences.timelineStylePickerPending)
        #expect(preferences.chipTimelineIntroDone)
    }

    @Test func picker_clearResetsItToUnset() {
        let suite = "SessionChatDisplayPreferencesTests.pickerClear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.resolveTimelineForInstall(existingInstall: true)

        preferences.clear()

        #expect(preferences.timelineStylePickerDone == nil)
        #expect(defaults.object(forKey: "timelineStylePickerDone") == nil)
        // Clear All Data returns to onboarding, so the next resolve is a new install.
        preferences.resolveTimelineForInstall(existingInstall: false)
        #expect(preferences.timelineStyle == .activityCard)
        #expect(!preferences.timelineStylePickerPending)
    }

    @Test func followUpDelivery_defaultsSteerAndPersistsQueue() {
        let suite = "SessionChatDisplayPreferencesTests.followUp"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        #expect(preferences.followUpDelivery == .steer)
        preferences.setFollowUpDelivery(.queue)
        #expect(preferences.followUpDelivery == .queue)
        let reloaded = SessionChatDisplayPreferences(defaults: defaults)
        #expect(reloaded.followUpDelivery == .queue)
    }
    // MARK: - Timeline Style (#779)

    private func freshDefaults(_ name: String) -> UserDefaults {
        let suite = "SessionChatDisplayPreferencesTests.timelineStyle.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    private func flags(chipsOn: Bool, _ name: String) -> FeatureFlags {
        let flags = FeatureFlags(defaults: freshDefaults("flags.\(name)"))
        flags.setOverride(.sessionChatChipTimeline, chipsOn)
        return flags
    }

    @Test func timelineStyle_migratesStoredChipTrueToChips() {
        let defaults = freshDefaults("migrateTrue")
        defaults.set(true, forKey: "chipTimeline")

        let preferences = SessionChatDisplayPreferences(defaults: defaults, featureFlags: flags(chipsOn: true, "migrateTrue"))

        #expect(preferences.timelineStyle == .chips)
        #expect(defaults.string(forKey: "timelineStyle") == "chips")
        #expect(defaults.object(forKey: "chipTimeline") as? Bool == true)
    }

    @Test func timelineStyle_migratesStoredChipFalseToStandard() {
        let defaults = freshDefaults("migrateFalse")
        defaults.set(false, forKey: "chipTimeline")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.string(forKey: "timelineStyle") == "standard")
    }

    @Test func timelineStyle_storedStyleWinsOverOldKey() {
        let defaults = freshDefaults("storedWins")
        defaults.set(true, forKey: "chipTimeline")
        defaults.set("step_rail", forKey: "timelineStyle")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.timelineStyle == .stepRail)
        #expect(preferences.stepRailTimeline)
        #expect(preferences.chipTimeline == false)
    }

    @Test func timelineStyle_missingResolvesInstallDefault() {
        let fresh = SessionChatDisplayPreferences(defaults: freshDefaults("installFresh"))
        fresh.resolveTimelineForInstall(existingInstall: false)
        #expect(fresh.timelineStyle == .activityCard)

        let existing = SessionChatDisplayPreferences(defaults: freshDefaults("installExisting"))
        existing.resolveTimelineForInstall(existingInstall: true)
        #expect(existing.timelineStyle == .standard)

        let untouched = SessionChatDisplayPreferences(defaults: freshDefaults("installNone"))
        #expect(untouched.timelineStyle == .standard)
    }

    @Test func timelineStyle_resolveKeepsAStoredStepRail() {
        let defaults = freshDefaults("installKeepsStepRail")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)
        preferences.setTimelineStyle(.stepRail)

        preferences.resolveTimelineForInstall(existingInstall: false)

        #expect(preferences.timelineStyle == .stepRail)
    }

    @Test func timelineStyle_flagOffMakesChipsEffectiveStandard() {
        let defaults = freshDefaults("flagOff")
        let preferences = SessionChatDisplayPreferences(defaults: defaults, featureFlags: flags(chipsOn: false, "flagOff"))
        preferences.setTimelineStyle(.chips)

        #expect(preferences.timelineStyle == .chips)
        #expect(preferences.chipTimeline == false)
        #expect(preferences.effectiveTimelineStyle == .standard)
        #expect(preferences.availableTimelineStyles == [.standard, .stepRail, .activityCard])

        preferences.setTimelineStyle(.stepRail)
        #expect(preferences.stepRailTimeline)
        #expect(preferences.effectiveTimelineStyle == .stepRail)
    }

    @Test func timelineStyle_flagOnOffersAllStyles() {
        let preferences = SessionChatDisplayPreferences(
            defaults: freshDefaults("flagOn"),
            featureFlags: flags(chipsOn: true, "flagOn")
        )
        #expect(preferences.availableTimelineStyles == [.standard, .chips, .stepRail, .activityCard])
    }

    @Test func timelineStyle_setPersistsEndsIntroAndClearResets() {
        let defaults = freshDefaults("setClear")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.setTimelineStyle(.stepRail)

        #expect(preferences.chipTimelineIntroDone)
        #expect(SessionChatDisplayPreferences(defaults: defaults).timelineStyle == .stepRail)

        preferences.clear()

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.object(forKey: "timelineStyle") == nil)
        #expect(defaults.object(forKey: "chipTimeline") == nil)
    }

    @Test func timelineStyle_stepRailNeverOffersTheChipIntro() {
        let defaults = freshDefaults("intro")
        defaults.set("step_rail", forKey: "timelineStyle")
        let preferences = SessionChatDisplayPreferences(defaults: defaults, featureFlags: flags(chipsOn: true, "intro"))

        #expect(!preferences.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: 1_000))
    }

    @Test func timelineStyle_activityCardWireValueRoundTrips() {
        let defaults = freshDefaults("activityCard")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.setTimelineStyle(.activityCard)

        #expect(SessionChatTimelineStyle.activityCard.wireValue == "activity_card")
        #expect(SessionChatTimelineStyle.fromWire("activity_card") == .activityCard)
        #expect(defaults.string(forKey: "timelineStyle") == "activity_card")
        #expect(defaults.bool(forKey: "chipTimeline") == false)
        let reloaded = SessionChatDisplayPreferences(defaults: defaults)
        #expect(reloaded.timelineStyle == .activityCard)
        #expect(reloaded.activityCardTimeline)
        #expect(reloaded.stepRailTimeline == false)
        #expect(reloaded.chipTimeline == false)
        #expect(reloaded.effectiveTimelineStyle == .activityCard)
        #expect(SessionChatTimelineStyle.activityCard.title == "Activity Card")
        #expect(
            SessionChatTimelineStyle.activityCard.subtitle
                == "Running steps on one live card that folds into a summary"
        )
    }

    @Test func timelineStyle_activityCardNeverOffersTheChipIntro() {
        let defaults = freshDefaults("introCard")
        defaults.set("activity_card", forKey: "timelineStyle")
        let preferences = SessionChatDisplayPreferences(defaults: defaults, featureFlags: flags(chipsOn: true, "introCard"))

        #expect(!preferences.shouldOfferChipTimelineIntro(turnToolCalls: 20, nowMs: 1_000))
    }

    @Test func timelineStyle_chipShimMapsToChipsAndStandard() {
        let preferences = SessionChatDisplayPreferences(defaults: freshDefaults("shim"))
        preferences.setChipTimeline(true)
        #expect(preferences.timelineStyle == .chips)
        preferences.setChipTimeline(false)
        #expect(preferences.timelineStyle == .standard)
    }
}
