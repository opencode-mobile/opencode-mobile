import SwiftUI

/// The launch screen's image (`UIImageName` = LaunchLogo: the app icon as a rounded tile, 96 pt),
/// drawn where the system draws it: at its own size, centred in the whole screen rather than the
/// safe area, so launch screen → wait state has no jump (app plan, "Cold start"). Decorative.
struct LaunchLogo: View {
    var body: some View {
        Image(.launchLogo)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}
