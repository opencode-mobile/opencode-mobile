#if DEBUG
import SwiftUI
import UIKit

/// `-OPENCODE_CAPTURE_COMPONENTS <id,id,...> -OPENCODE_CAPTURE_CHANNEL <folder>` opens the capture
/// screen instead of the app. `ComponentCaptureUITests` drives it: one launch walks every tile of
/// every requested component in light and then dark, one tile on screen at a time.
struct ComponentCaptureLaunch: Equatable {
    let componentIDs: [String]
    /// Folder shared with the UI test (simulator apps can write host paths).
    let channel: URL

    static let requested: ComponentCaptureLaunch? = parse(ProcessInfo.processInfo.arguments)

    static func parse(_ arguments: [String]) -> ComponentCaptureLaunch? {
        func value(_ flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
            return arguments[index + 1]
        }
        guard let list = value("-OPENCODE_CAPTURE_COMPONENTS"),
              let channel = value("-OPENCODE_CAPTURE_CHANNEL"), !channel.isEmpty
        else { return nil }
        let ids = list.split(separator: ",").map(String.init).filter { !$0.isEmpty }
        return ComponentCaptureLaunch(
            componentIDs: ids.isEmpty ? ComponentCaptureRegistry.entries.map(\.componentID) : ids,
            channel: URL(fileURLWithPath: channel, isDirectory: true)
        )
    }

    /// Fixed look for every capture: the bundled Porcelain theme, default text sizes, light
    /// appearance to start, and no UIKit animations. Called from the app's init, before any view.
    @MainActor
    static func prepareIfRequested() {
        guard requested != nil else { return }
        UIView.setAnimationsEnabled(false)
        ThemeManager.shared.clearAll()
        AppearancePreferences.shared.clear()
        AppearancePreferences.shared.setMode(.light)
    }
}

/// One tile the run shows: a component state in one appearance.
private struct ComponentCaptureStep {
    let entry: ComponentCaptureEntry
    let state: ComponentCaptureState
    let appearance: AppearanceMode
}

/// Shows one tile at a time at the top of the screen. The tile is a full-width container with
/// 16 pt padding on `background-base`, identified `capture.<component-id>.<state-id>`.
///
/// The UI test and the app talk through files in the channel folder, so nothing on screen (a
/// window overlay, a modal trait) can get in the way:
/// - `meta.json` (app): the run's plan, written once.
/// - `step.json` (app): `{index, component, state, appearance, x, y, width, height}` once a tile
///   has settled, frame in window points; `{"done": true}` after the last tile.
/// - `advance` (test): the index it has captured; the app then shows the next tile.
struct ComponentCaptureHost: View {
    let launch: ComponentCaptureLaunch

    @State private var index = 0
    @State private var tileFrame: CGRect = .zero

    private var steps: [ComponentCaptureStep] {
        let entries = launch.componentIDs.compactMap(ComponentCaptureRegistry.entry(for:))
        return [AppearanceMode.light, .dark].flatMap { appearance in
            entries.flatMap { entry in
                entry.states.map { ComponentCaptureStep(entry: entry, state: $0, appearance: appearance) }
            }
        }
    }

    var body: some View {
        let steps = steps
        let step = steps.indices.contains(index) ? steps[index] : nil
        ZStack(alignment: .top) {
            OpenCodeColors.backgroundBase.ignoresSafeArea()
            if let step {
                tile(step)
                    .id(index)
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
                        tileFrame = frame
                    }
                    .task(id: index) { await settle(step) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .ignoresSafeArea(.keyboard)
        .transaction {
            $0.disablesAnimations = true
            $0.animation = nil
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            apply(steps.first?.appearance)
            freezeCoreAnimation()
            write("meta.json", ComponentCaptureRegistry.metaJSON(for: launch.componentIDs))
        }
        .task { await followAdvances(steps: steps) }

    }

    private func tile(_ step: ComponentCaptureStep) -> some View {
        // The stack keeps a tile for a component that renders nothing (an empty list group).
        VStack(spacing: 0) { step.state.content() }
            // A focused field keeps focus for its tile; a clear tint hides its caret.
            .modifier(ComponentCaptureCaretHidden(hidden: step.state.focused))
            .modifier(ComponentCaptureKeyboardFocus(enabled: step.state.keyboardFocus))
            .frame(
                maxWidth: .infinity,
                alignment: step.entry.alignment == .center ? .center : .leading
            )
            .frame(height: step.state.height, alignment: .top)
            // Only fixed-height tiles clip; otherwise focus rings and shadows reach into the padding.
            .clipped(step.state.height != nil)
            .padding(16)
            .background {
                OpenCodeColors.backgroundBase
                    .accessibilityElement()
                    .accessibilityLabel(Text(verbatim: "Capture tile"))
                    .accessibilityIdentifier("capture.\(step.entry.componentID).\(step.state.id)")
            }
    }

    /// Captures show no caret or keyboard: some states focus a field on appear (the deny note),
    /// so focus is dropped, then the tile is reported once it has had a frame to lay out.
    private func settle(_ step: ComponentCaptureStep) async {
        let stepIndex = index
        OpenCodeKeyboardFocusState.shared.setCaptureHardwareKeyboard(step.state.keyboardFocus)
        for _ in 0 ..< 2 {
            if !step.state.focused {
                UIApplication.shared.sendAction(
                    #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
                )
            }
            try? await Task.sleep(for: .milliseconds(step.state.focused ? 250 : 40))
        }
        if step.state.delay > .zero {
            try? await Task.sleep(for: step.state.delay)
        }
        guard stepIndex == index, !Task.isCancelled else { return }
        let frame = tileFrame
        let payload: [String: Any] = [
            "index": stepIndex,
            "component": step.entry.componentID,
            "state": step.state.id,
            "appearance": step.appearance.rawValue,
            "x": frame.minX, "y": frame.minY, "width": frame.width, "height": frame.height,
        ]
        write("step.json", json(payload))
    }

    /// Polls the test's `advance` file and moves on once it names the tile on screen.
    private func followAdvances(steps: [ComponentCaptureStep]) async {
        let url = launch.channel.appendingPathComponent("advance")
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(15))
            guard let text = try? String(contentsOf: url, encoding: .utf8),
                  let captured = Int(text.trimmingCharacters(in: .whitespacesAndNewlines)),
                  captured == index
            else { continue }
            let next = index + 1
            if steps.indices.contains(next) {
                if steps[next].appearance != steps[index].appearance { apply(steps[next].appearance) }
                index = next
            } else {
                index = next
                write("step.json", json(["done": true]))
                return
            }
        }
    }

    private func json(_ payload: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    private func write(_ name: String, _ text: String) {
        try? FileManager.default.createDirectory(at: launch.channel, withIntermediateDirectories: true)
        try? Data(text.utf8).write(to: launch.channel.appendingPathComponent(name), options: .atomic)
    }

    /// UIKit spinners and other Core Animation loops stop at their first frame, so every run draws
    /// the same pixels.
    private func freezeCoreAnimation() {
        for scene in UIApplication.shared.connectedScenes {
            for window in (scene as? UIWindowScene)?.windows ?? [] {
                window.layer.speed = 0
                window.layer.timeOffset = 0
            }
        }
    }

    private func apply(_ appearance: AppearanceMode?) {
        guard let appearance else { return }
        AppearancePreferences.shared.setMode(appearance)
    }
}

private struct ComponentCaptureCaretHidden: ViewModifier {
    let hidden: Bool

    func body(content: Content) -> some View {
        if hidden { content.tint(.clear) } else { content }
    }
}
/// Keyboard focus states: the control takes real SwiftUI focus, and the app's own focus ring
/// policy draws the ring.
private struct ComponentCaptureKeyboardFocus: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled { content.openCodeCaptureTakesFocus() } else { content }
    }
}
private extension View {
    @ViewBuilder
    func clipped(_ clips: Bool) -> some View {
        if clips { clipped() } else { self }
    }
}
#endif
