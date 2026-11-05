import OpenCodeKit
import SwiftUI

@main
struct HexCodeApp: App {
    @State private var app = HexAppModel()
    @AppStorage(HexAppearance.key) private var appearance: HexAppearance = .auto

    init() {
        // Hex Code always looks like Hex; Appearance only picks light, dark or the system's.
        ThemeManager.appID = "hexcode"
        ThemeManager.shared.applyPreset(id: "hex")
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if Self.runsSpeechLab {
                    DictationScreen()
                } else {
                    HexRootView(app: app, appearance: $appearance)
                        .preferredColorScheme(appearance.colorScheme)
                }
            }
            .openCodeTheme()
        }
    }

    /// `-HexCodeLab <folder>` and `-HexCodeSample <file>` open the dictation lab screen, which runs
    /// the speech model lab clip sets on the phone.
    private static var runsSpeechLab: Bool {
        #if DEBUG
        let defaults = UserDefaults.standard
        return defaults.string(forKey: "HexCodeLab") != nil || defaults.string(forKey: "HexCodeSample") != nil
        #else
        return false
        #endif
    }
}

struct HexRootView: View {
    let app: HexAppModel
    @Binding var appearance: HexAppearance

    var body: some View {
        Group {
            switch app.phase {
            case .launching:
                OpenCodeColors.backgroundBase.ignoresSafeArea()
            case .firstRun:
                if let firstRun = app.firstRun {
                    FirstRunView(model: firstRun)
                }
            case .main:
                if let main = app.main {
                    HexMainView(main: main, voice: app.voice, appearance: $appearance, onForgetServer: {
                        Task { await app.startOver() }
                    })
                }
            }
        }
        .background(OpenCodeColors.backgroundBase.ignoresSafeArea())
        .task { await app.launch() }
    }
}
