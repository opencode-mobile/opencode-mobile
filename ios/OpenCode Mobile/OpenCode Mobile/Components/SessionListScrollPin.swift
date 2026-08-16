import CoreGraphics

enum SessionListScrollPin {
    static let topOffsetThreshold: CGFloat = 1
    static let staleAfterMs: Int64 = 10 * 60 * 1000

    static func isAtTop(offsetFromTop: CGFloat) -> Bool {
        offsetFromTop <= topOffsetThreshold
    }

    static func followingTop(
        currentlyFollowing: Bool,
        isUserScrolling: Bool,
        isAtTop: Bool
    ) -> Bool {
        if isUserScrolling {
            return isAtTop
        }
        if isAtTop {
            return true
        }
        return currentlyFollowing
    }

    static func shouldScrollToTrueTop(
        followingTop: Bool,
        newestIdentityChanged: Bool,
        forceRevealNewest: Bool
    ) -> Bool {
        if forceRevealNewest {
            return true
        }
        return followingTop && newestIdentityChanged
    }

    static func shouldResetForStaleAbsence(
        lastHiddenAtEpochMs: Int64?,
        nowEpochMs: Int64,
        staleAfterMs: Int64 = staleAfterMs
    ) -> Bool {
        guard let lastHiddenAtEpochMs else { return false }
        return nowEpochMs - lastHiddenAtEpochMs >= staleAfterMs
    }
}
