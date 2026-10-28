import Foundation
import LocalAuthentication

/// Face ID / Touch ID with device passcode fallback.
protocol AppLockAuthenticating: Sendable {
    /// False when the device has neither biometrics nor a passcode; the lock cannot be enabled then.
    func canAuthenticate() -> Bool
    func authenticate(reason: String) async -> Bool
}

struct LocalAuthenticationAppLockAuthenticator: AppLockAuthenticating {
    func canAuthenticate() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    func authenticate(reason: String) async -> Bool {
        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return false }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            return false
        }
    }
}
