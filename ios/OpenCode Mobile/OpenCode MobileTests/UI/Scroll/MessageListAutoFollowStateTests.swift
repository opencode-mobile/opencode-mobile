import Testing
@testable import OpenCode_Mobile

struct MessageListAutoFollowStateTests {
    @Test func appendWhileAwayFromEndDoesNotFollow() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: false, messageCount: 8)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: true)
        #expect(!shouldFollow)
    }

    @Test func appendWhilePinnedFollows() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: true, messageCount: 8)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: true)
        #expect(shouldFollow)
    }

    @Test func appendAfterPillScrollSettlesAtEndFollows() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: false, messageCount: 8)
        state.markProgrammaticScrollSettled(isPinnedToEnd: true)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: true)
        #expect(shouldFollow)
    }

    @Test func layoutGrowthAfterPinnedFollows() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: true, messageCount: 8)

        #expect(state.shouldFollowLayoutChange(autoFollowEnabled: true, isPinnedToEnd: false))
    }

    @Test func layoutChangeWhilePinnedAndArmedEnsuresEnd() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: true, messageCount: 8)

        #expect(state.shouldEnsureLayoutEnd(autoFollowEnabled: true))
    }

    @Test func layoutGrowthAfterScrollAwayDoesNotFollow() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: false, messageCount: 8)

        #expect(!state.shouldFollowLayoutChange(autoFollowEnabled: true, isPinnedToEnd: false))
        #expect(!state.shouldEnsureLayoutEnd(autoFollowEnabled: true))
    }

    @Test func appendAfterPillScrollMissesEndDoesNotFollow() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.markProgrammaticScrollSettled(isPinnedToEnd: false)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: true)
        #expect(!shouldFollow)
    }

    @Test func appendWithAutoFollowDisabledDoesNotFollow() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: true, messageCount: 8)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: false)
        #expect(!shouldFollow)
    }

    @Test func scrollAwayAfterPinnedDisarmsFollow() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: true, messageCount: 8)
        state.markProgrammaticScrollSettled(isPinnedToEnd: false)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: true)
        #expect(!shouldFollow)
    }

    @Test func geometryUnpinnedDoesNotDisarmFollow() {
        var state = MessageListAutoFollowState(initialMessageCount: 8)

        state.updatePinnedState(isPinnedToEnd: true, messageCount: 8)
        state.updatePinnedState(isPinnedToEnd: false, messageCount: 8)

        let shouldFollow = state.onMessageCountChanged(messageCount: 9, autoFollowEnabled: true)
        #expect(shouldFollow)
    }
}
