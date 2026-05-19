//
//  ContentView.swift
//  OpenCode Mobile
//
//  Created by Ivan Fenenko on 04.05.26.
//

import Factory
import SwiftUI

struct ContentView: View {
    var body: some View {
        NavigationStack {
            ProjectListHomeView(viewModel: Container.makeProjectListHomeViewModel())
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
