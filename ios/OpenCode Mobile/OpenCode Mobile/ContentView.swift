//
//  ContentView.swift
//  OpenCode Mobile
//
//  Created by Ivan Fenenko on 04.05.26.
//

import SwiftUI

struct ContentView: View {
    var body: some View {
        AppRootView()
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
