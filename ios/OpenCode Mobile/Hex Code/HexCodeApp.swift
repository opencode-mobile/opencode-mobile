import SwiftUI

@main
struct HexCodeApp: App {
    @State private var app = HexAppModel()
    @AppStorage(HexAppearance.key) private var appearance: HexAppearance = .auto

    var body: some Scene {
        WindowGroup {
            Group {
                if Self.runsSpeechLab {
                    DictationScreen()
                } else if Self.showsGallery {
                    HexGallery().hexTheme(appearance.colorScheme)
                } else {
                    HexRootView(app: app, appearance: $appearance)
                        .hexTheme(appearance.colorScheme)
                }
            }
        }
    }

    /// `-HexCodeGallery YES` opens the design system gallery on its own. Developer settings open it in
    /// every build.
    private static var showsGallery: Bool {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "HexCodeGallery")
        #else
        return false
        #endif
    }

    /// `-HexCodeLab <folder>` and `-HexCodeSample <file>` open the dictation lab screen, which runs
    /// the speech model lab clip sets on the phone. Developer settings open it in every build as the
    /// Speech lab.
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
                HexColor.background.ignoresSafeArea()
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
        .hexScreenBackground()
        .task { await app.launch() }
    }
}
