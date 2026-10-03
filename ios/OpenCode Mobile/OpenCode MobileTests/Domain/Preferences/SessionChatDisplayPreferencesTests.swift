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
        preferences.setSmoothStreamingUpdates(false)

        preferences.clear()

        #expect(preferences.collapseConsecutiveToolCalls == true)
        #expect(defaults.object(forKey: "collapseConsecutiveToolCalls") == nil)
        #expect(preferences.smoothStreamingUpdates == true)
        #expect(defaults.object(forKey: "smoothStreamingUpdates") == nil)
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

    @Test func missingSmoothStreamingKey_defaultsOn() {
        let suite = "SessionChatDisplayPreferencesTests.smoothDefault"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.smoothStreamingUpdates == true)
    }

    @Test func storedSmoothStreamingOff_isPreserved() {
        let suite = "SessionChatDisplayPreferencesTests.smoothStored"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defaults.set(false, forKey: "smoothStreamingUpdates")

        let preferences = SessionChatDisplayPreferences(defaults: defaults)

        #expect(preferences.smoothStreamingUpdates == false)
        #expect(SessionChatDisplayPreferences.currentSmoothStreamingUpdates(defaults: defaults) == false)
    }
}
