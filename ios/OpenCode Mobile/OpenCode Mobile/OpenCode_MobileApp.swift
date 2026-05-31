//
//  OpenCode_MobileApp.swift
//  OpenCode Mobile
//
//  Created by Ivan Fenenko on 04.05.26.
//

import SwiftUI
import SwiftData

@main
struct OpenCode_MobileApp: App {
    var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            Item.self,
        ])
        let modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)

        do {
            return try ModelContainer(for: schema, configurations: [modelConfiguration])
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

    init() {
        _ = ThemeManager.shared
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .openCodeTheme()
        }
        .modelContainer(sharedModelContainer)
    }
}
