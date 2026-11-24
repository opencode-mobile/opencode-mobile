import XCTest

/// Captures native component tiles for the HTML design system gallery. Run it through
/// `scripts/component-gallery/capture-ios.sh`, which forwards:
/// - `TEST_RUNNER_COMPONENT_CAPTURE_OUT`: folder for `<component>/<state>--<appearance>.png`
///   and `records.json`.
/// - `TEST_RUNNER_COMPONENT_CAPTURE_CONTRACT`: path of `captures/contract.json`.
/// - `TEST_RUNNER_COMPONENT_CAPTURE_ONLY` (optional): comma-separated component ids.
/// Skips itself when the output folder is unset, so ordinary UI test runs are unaffected.
///
/// One launch covers the whole run: the app shows one tile at a time and reports its frame, the
/// test crops a screen screenshot to it and taps to move on.
final class ComponentCaptureUITests: XCTestCase {
    private struct Contract: Decodable {
        struct Component: Decodable {
            struct State: Decodable { let id: String }
            let id: String
            let states: [State]
        }

        let components: [Component]
    }

    private struct Meta: Decodable {
        let registered: [String]
        let skipped: [Skip]
        let notes: [Note]
    }

    private struct Skip: Codable {
        let component: String
        let state: String
        let reason: String
    }

    private struct Note: Codable {
        let component: String
        let state: String
        let note: String
    }

    private struct Record: Encodable {
        let component: String
        let state: String
        let appearance: String
        let file: String
        let widthPt: Double
        let heightPt: Double
        let note: String?
    }

    private struct Records: Encodable {
        let components: [String]
        let captures: [Record]
        let skipped: [Skip]
        let extraStates: [String]
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testCaptureComponents() throws {
        let environment = ProcessInfo.processInfo.environment
        guard let outPath = environment["COMPONENT_CAPTURE_OUT"], !outPath.isEmpty else {
            throw XCTSkip("COMPONENT_CAPTURE_OUT is unset; run scripts/component-gallery/capture-ios.sh.")
        }
        guard let contractPath = environment["COMPONENT_CAPTURE_CONTRACT"] else {
            XCTFail("COMPONENT_CAPTURE_CONTRACT is unset.")
            return
        }
        let contract = try JSONDecoder().decode(
            Contract.self,
            from: Data(contentsOf: URL(fileURLWithPath: contractPath))
        )
        let only = Set(
            (environment["COMPONENT_CAPTURE_ONLY"] ?? "")
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        )
        let components = contract.components.filter { only.isEmpty || only.contains($0.id) }
        let out = URL(fileURLWithPath: outPath, isDirectory: true)
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

        let channel = out.appendingPathComponent("_channel", isDirectory: true)
        try? FileManager.default.removeItem(at: channel)
        try FileManager.default.createDirectory(at: channel, withIntermediateDirectories: true)

        let app = XCUIApplication()
        app.launchArguments += [
            "-OPENCODE_CAPTURE_COMPONENTS", components.map(\.id).joined(separator: ","),
            "-OPENCODE_CAPTURE_CHANNEL", channel.path,
        ]
        app.launch()
        defer { app.terminate() }

        let metaData = try waitForFile(channel.appendingPathComponent("meta.json"), timeout: 30)
        let meta = try JSONDecoder().decode(Meta.self, from: metaData)
        let notes = Dictionary(
            meta.notes.map { ("\($0.component)/\($0.state)", $0.note) },
            uniquingKeysWith: { first, _ in first }
        )
        let window = app.windows.firstMatch.frame
        // Fonts and the first layout pass settle before the first crop.
        Thread.sleep(forTimeInterval: 1.0)

        var captures: [Record] = []
        var captured: Set<String> = []
        var expectedIndex = 0
        let stepURL = channel.appendingPathComponent("step.json")
        let advanceURL = channel.appendingPathComponent("advance")
        while true {
            let step = try waitForStep(expectedIndex, at: stepURL)
            if step.done == true { break }
            let component = step.component ?? "", state = step.state ?? "", appearance = step.appearance ?? ""
            let frame = CGRect(x: step.x ?? 0, y: step.y ?? 0, width: step.width ?? 0, height: step.height ?? 0)
            XCTAssertTrue(
                window.insetBy(dx: -0.5, dy: -0.5).contains(frame) && frame.height >= 1,
                "\(component)/\(state) does not fit the screen: \(frame) in \(window)."
            )
            let png = try crop(XCUIScreen.main.screenshot().image, to: frame)
            let folder = out.appendingPathComponent(component, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let name = "\(state)--\(appearance).png"
            try png.write(to: folder.appendingPathComponent(name), options: .atomic)
            captures.append(Record(
                component: component,
                state: state,
                appearance: appearance,
                file: "\(component)/\(name)",
                widthPt: Double(frame.width),
                heightPt: Double(frame.height),
                note: notes["\(component)/\(state)"]
            ))
            captured.insert("\(component)/\(state)")
            try Data("\(expectedIndex)".utf8).write(to: advanceURL, options: .atomic)
            expectedIndex += 1
        }

        // Every contract state has a tile or a recorded skip.
        let skippedKeys = Set(meta.skipped.map { "\($0.component)/\($0.state)" })
        var skipped = meta.skipped
        var missing: [String] = []
        for component in components {
            if skippedKeys.contains("\(component.id)/*") { continue }
            guard meta.registered.contains(component.id) else {
                skipped.append(Skip(component: component.id, state: "*", reason: "No iOS capture registered yet"))
                missing.append(component.id)
                continue
            }
            for state in component.states {
                let key = "\(component.id)/\(state.id)"
                if !captured.contains(key), !skippedKeys.contains(key) { missing.append(key) }
            }
        }
        let contractKeys = Set(components.flatMap { c in c.states.map { "\(c.id)/\($0.id)" } })
        let extras = captured.subtracting(contractKeys).sorted()

        let records = Records(
            components: components.map(\.id),
            captures: captures,
            skipped: skipped,
            extraStates: extras
        )
        try JSONEncoder().encode(records).write(to: out.appendingPathComponent("records.json"), options: .atomic)
        XCTAssertTrue(missing.isEmpty, "No tile or skip for: \(missing.joined(separator: ", "))")
    }

    private struct Step: Decodable {
        let index: Int?
        let done: Bool?
        let component: String?
        let state: String?
        let appearance: String?
        let x: Double?
        let y: Double?
        let width: Double?
        let height: Double?
    }

    private func waitForFile(_ url: URL, timeout: TimeInterval) throws -> Data {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let data = try? Data(contentsOf: url) { return data }
            Thread.sleep(forTimeInterval: 0.05)
        }
        XCTFail("The app never wrote \(url.lastPathComponent).")
        throw XCTSkip("no \(url.lastPathComponent)")
    }

    /// Waits until the app reports the expected step, or that it is done.
    private func waitForStep(_ index: Int, at url: URL) throws -> Step {
        let deadline = Date().addingTimeInterval(15)
        var last = ""
        while Date() < deadline {
            if let data = try? Data(contentsOf: url), let step = try? JSONDecoder().decode(Step.self, from: data) {
                if step.done == true || step.index == index { return step }
                last = String(decoding: data, as: UTF8.self)
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
        XCTFail("Step \(index) never appeared; last step \(last).")
        throw XCTSkip("stalled")
    }

    private func crop(_ image: UIImage, to frame: CGRect) throws -> Data {
        guard let cgImage = image.cgImage else { throw XCTSkip("screenshot has no bitmap") }
        let scale = CGFloat(cgImage.width) / image.size.width
        let pixels = CGRect(
            x: (frame.minX * scale).rounded(),
            y: (frame.minY * scale).rounded(),
            width: (frame.width * scale).rounded(),
            height: (frame.height * scale).rounded()
        )
        guard let cropped = cgImage.cropping(to: pixels),
              let data = UIImage(cgImage: cropped, scale: scale, orientation: .up).pngData()
        else { throw XCTSkip("crop failed for \(frame)") }
        return data
    }
}
