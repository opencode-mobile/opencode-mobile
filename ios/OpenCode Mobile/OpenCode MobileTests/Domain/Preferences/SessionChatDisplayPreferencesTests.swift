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

    @Test func clear_removesLegacyChipTimelineKeys() {
        let suite = "SessionChatDisplayPreferencesTests.legacyClear"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        // Keys an install from before #1120 may still hold.
        defaults.set(true, forKey: "chipTimeline")
        defaults.set(true, forKey: "chipTimelineIntroDone")
        defaults.set(NSNumber(value: Int64(1_000)), forKey: "chipTimelineIntroSnoozedUntilMs")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.clear()

        #expect(defaults.object(forKey: "chipTimeline") == nil)
        #expect(defaults.object(forKey: "chipTimelineIntroDone") == nil)
        #expect(defaults.object(forKey: "chipTimelineIntroSnoozedUntilMs") == nil)
        #expect(defaults.object(forKey: "timelineStyle") == nil)
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
    }

    @Test func install_existingResolvesStandardAndOffersThePicker() {
        let suite = "SessionChatDisplayPreferencesTests.existingInstall"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.resolveTimelineForInstall(existingInstall: true)

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.string(forKey: "timelineStyle") == "standard")
        #expect(preferences.timelineStylePickerPending)
        #expect(SessionChatDisplayPreferences(defaults: defaults).timelineStylePickerPending)
    }

    @Test func install_existingWithAStoredChipsStyleResolvesStandard() {
        let suite = "SessionChatDisplayPreferencesTests.existingChips"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set("chips", forKey: "timelineStyle")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.resolveTimelineForInstall(existingInstall: true)

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.string(forKey: "timelineStyle") == "standard")
        #expect(preferences.timelineStylePickerPending)
    }

    @Test func install_existingWithOnlyTheLegacyChipKeyStaysStandardAndOffersThePicker() {
        let suite = "SessionChatDisplayPreferencesTests.existingLegacyChip"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(false, forKey: "chipTimeline")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.resolveTimelineForInstall(existingInstall: true)

        #expect(preferences.timelineStyle == .standard)
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

        preferences.setTimelineStyle(.standard)
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

    @Test func timelineStyle_migratesStoredChipTrueToStandard() {
        let defaults = freshDefaults("migrateTrue")
        defaults.set(true, forKey: "chipTimeline")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.string(forKey: "timelineStyle") == "standard")
        // The legacy key stays so the install still reads as existing.
        #expect(defaults.object(forKey: "chipTimeline") as? Bool == true)
    }

    @Test func timelineStyle_storedChipsResolvesStandardAndRewritesIt() {
        let defaults = freshDefaults("storedChips")
        defaults.set("chips", forKey: "timelineStyle")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.string(forKey: "timelineStyle") == "standard")
        #expect(SessionChatTimelineStyle.fromWire("chips") == .standard)
        #expect(SessionChatTimelineStyle.allCases == [.standard, .stepRail, .activityCard])
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

    @Test func timelineStyle_setPersistsAndClearResets() {
        let defaults = freshDefaults("setClear")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.setTimelineStyle(.stepRail)

        #expect(SessionChatDisplayPreferences(defaults: defaults).timelineStyle == .stepRail)

        preferences.clear()

        #expect(preferences.timelineStyle == .standard)
        #expect(defaults.object(forKey: "timelineStyle") == nil)
        #expect(defaults.object(forKey: "chipTimeline") == nil)
    }

    @Test func timelineStyle_activityCardWireValueRoundTrips() {
        let defaults = freshDefaults("activityCard")
        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        preferences.setTimelineStyle(.activityCard)

        #expect(SessionChatTimelineStyle.activityCard.wireValue == "activity_card")
        #expect(SessionChatTimelineStyle.fromWire("activity_card") == .activityCard)
        #expect(defaults.string(forKey: "timelineStyle") == "activity_card")
        let reloaded = SessionChatDisplayPreferences(defaults: defaults)
        #expect(reloaded.timelineStyle == .activityCard)
        #expect(reloaded.activityCardTimeline)
        #expect(reloaded.stepRailTimeline == false)
        #expect(SessionChatTimelineStyle.activityCard.settingsTitle.resolved == "Activity Card")
        #expect(
            SessionChatTimelineStyle.activityCard.settingsSubtitle.resolved
                == "Running steps on one live card that folds into a summary"
        )
    }
}
