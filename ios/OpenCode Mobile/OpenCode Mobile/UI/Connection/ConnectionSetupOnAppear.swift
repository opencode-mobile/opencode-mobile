import Foundation

/// One-shot gate for presenting connection setup after App onboarding.
///
/// Home attaches presentation to `.task`, which can re-run when a session pops or a
/// cover dismisses. Without consuming the flag on first present, the setup cover
/// reappears on top of the navigation stack.
enum ConnectionSetupOnAppear {
    struct Decision: Equatable {
        let shouldPresent: Bool
        let pending: Bool
    }

    static func consume(_ pending: Bool) -> Decision {
        if pending {
            return Decision(shouldPresent: true, pending: false)
        }
        return Decision(shouldPresent: false, pending: false)
    }
}
