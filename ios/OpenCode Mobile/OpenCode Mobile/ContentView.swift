//
//  ContentView.swift
//  OpenCode Mobile
//
//  Created by Ivan Fenenko on 04.05.26.
//

import Factory
import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ProjectListHomeView(viewModel: Container.makeProjectListHomeViewModel())
        }
        .onAppear {
            Container.shared.foregroundEventStream().start()
        }
        .onChange(of: scenePhase) { _, phase in
            let stream = Container.shared.foregroundEventStream()
            switch phase {
            case .active:
                stream.start()
            case .background:
                stream.stop()
            case .inactive:
                break
            @unknown default:
                break
            }
        }
    }
}

#Preview {
    ContentView()
        .openCodeTheme()
}

#Preview("Dark") {
    ContentView()
        .openCodeTheme()
        .preferredColorScheme(.dark)
}
