import Foundation
import SwiftUI
import UIKit
@testable import OpenCode_Mobile

@MainActor
enum SnapshotEvidenceRenderer {
    static let phoneWidth: CGFloat = 390
    static let phoneHeight: CGFloat = 844
    static let scale: CGFloat = 2

    enum Appearance: String {
        case light
        case dark
    }

    static func writePNG<V: View>(
        name: String,
        appearance: Appearance,
        height: CGFloat? = nil,
        @ViewBuilder view: () -> V
    ) throws {
        let outputDirectory = try resolveOutputDirectory()
        try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)

        let wrapped = view()
            .frame(width: phoneWidth, height: height, alignment: .top)
            .openCodeTheme()
            .environment(\.colorScheme, appearance == .dark ? .dark : .light)
            .preferredColorScheme(appearance == .dark ? .dark : .light)

        let renderer = ImageRenderer(content: wrapped)
        renderer.scale = scale
        renderer.proposedSize = ProposedViewSize(width: phoneWidth, height: height)

        guard let image = renderer.uiImage, let data = image.pngData() else {
            throw SnapshotEvidenceRenderError.imageRenderFailed(name)
        }

        let url = outputDirectory.appendingPathComponent("\(name).png")
        try data.write(to: url, options: .atomic)
    }

    static func resolveOutputDirectory() throws -> URL {
        if let override = ProcessInfo.processInfo.environment["SNAPSHOT_EVIDENCE_OUT"],
           !override.isEmpty
        {
            return URL(fileURLWithPath: override, isDirectory: true)
        }

        var directory = URL(fileURLWithPath: #filePath)
        while directory.path != "/" {
            directory.deleteLastPathComponent()
            let marker = directory.appendingPathComponent("AGENTS.md")
            if FileManager.default.fileExists(atPath: marker.path) {
                return directory.appendingPathComponent(".snapshot-evidence/out", isDirectory: true)
            }
        }
        throw SnapshotEvidenceRenderError.repositoryRootNotFound
    }
}

enum SnapshotEvidenceRenderError: Error {
    case imageRenderFailed(String)
    case repositoryRootNotFound
}
