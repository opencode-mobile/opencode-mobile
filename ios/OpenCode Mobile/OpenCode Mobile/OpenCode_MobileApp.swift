//
//  OpenCode_MobileApp.swift
//  OpenCode Mobile
//
//  Created by Ivan Fenenko on 04.05.26.
//

import SwiftUI

@main
struct OpenCode_MobileApp: App {
    init() {
        _ = ThemeManager.shared
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .openCodeTheme()
        }
    }
}
