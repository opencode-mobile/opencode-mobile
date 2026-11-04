import OpenCodeKit
import SwiftUI

@main
struct HexCodeApp: App {
    @State private var app = HexAppModel()

    var body: some Scene {
        WindowGroup {
            HexRootView(app: app)
                .openCodeTheme()
        }
    }
}

struct HexRootView: View {
    let app: HexAppModel

    var body: some View {
        Group {
            switch app.phase {
            case .launching:
                OpenCodeColors.backgroundBase.ignoresSafeArea()
            case .firstRun:
                if let firstRun = app.firstRun {
                    FirstRunView(model: firstRun)
                }
            case let .main(setup):
                MainPlaceholderView(setup: setup, voice: app.voice, onStartOver: { Task { await app.startOver() } })
            }
        }
        .background(OpenCodeColors.backgroundBase.ignoresSafeArea())
        .task { await app.launch() }
    }
}
