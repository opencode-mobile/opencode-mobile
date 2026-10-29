#if HIGHLIGHT_LAB
import Foundation

/// Lab reports under the app's Documents `highlight-lab/`: `latest.md` and `latest.json`
/// (the shared report formats), timestamped copies of both, and `latest-tables.json` so the
/// Benchmark screen can show the last report after a restart.
nonisolated struct HighlightLabStorage: Sendable {
    nonisolated struct SavedFiles: Equatable, Sendable {
        let markdown: URL
        let json: URL
        let stampedMarkdown: URL
        let stampedJSON: URL
    }

    let directory: URL

    static var documents: HighlightLabStorage {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return HighlightLabStorage(directory: documents.appendingPathComponent("highlight-lab", isDirectory: true))
    }

    var latestMarkdown: URL { directory.appendingPathComponent("latest.md") }
    var latestJSON: URL { directory.appendingPathComponent("latest.json") }
    private var latestTables: URL { directory.appendingPathComponent("latest-tables.json") }

    /// Writes the report; `label` names the timestamped copies, for example "quick".
    @discardableResult
    func save(_ report: HighlightLabReport, label: String, at date: Date = Date()) throws -> SavedFiles {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let stamp = Self.stamp(date) + "-" + Self.fileSafe(label)
        let stampedMarkdown = directory.appendingPathComponent("\(stamp).md")
        let stampedJSON = directory.appendingPathComponent("\(stamp).json")
        for url in [latestMarkdown, stampedMarkdown] {
            try Data(report.markdown.utf8).write(to: url, options: .atomic)
        }
        for url in [latestJSON, stampedJSON] {
            try Data(report.json.utf8).write(to: url, options: .atomic)
        }
        try JSONEncoder().encode(report.tables).write(to: latestTables, options: .atomic)
        return SavedFiles(markdown: latestMarkdown, json: latestJSON, stampedMarkdown: stampedMarkdown, stampedJSON: stampedJSON)
    }

    /// The last saved report, or nil when there is none or it cannot be read.
    func loadLatest() -> HighlightLabReport? {
        guard let tablesData = try? Data(contentsOf: latestTables),
              let tables = try? JSONDecoder().decode(HighlightLabReportTables.self, from: tablesData),
              let markdown = try? String(contentsOf: latestMarkdown, encoding: .utf8),
              let json = try? String(contentsOf: latestJSON, encoding: .utf8)
        else { return nil }
        return HighlightLabReport(tables: tables, markdown: markdown, json: json)
    }

    static func stamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: date)
    }

    /// Letters, digits, `-` and `_`; anything else becomes `_`, capped at 60 characters.
    static func fileSafe(_ label: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let mapped = label.unicodeScalars.map { allowed.contains($0) && $0.isASCII ? String($0) : "_" }.joined()
        return String(mapped.prefix(60))
    }
}

/// Device and build lines for a report. No device name or other personal value.
@MainActor
enum HighlightLabEnvironment {
    static func lines() -> [String] {
        let environment = ProcessInfo.processInfo.environment
        let simulatorModel = environment["SIMULATOR_MODEL_IDENTIFIER"]
        var lines = [
            "Platform: iOS \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "Device: \(simulatorModel.map { "Simulator \($0)" } ?? machine())",
            "Cores: \(ProcessInfo.processInfo.activeProcessorCount)",
        ]
        #if DEBUG
        lines.append("Build: Debug (unoptimized; not for numbers)")
        #else
        lines.append("Build: Lab (optimized, release Kotlin framework)")
        #endif
        let version = AppVersion.current()
        lines.append("App: \(version.version) (\(version.build))")
        return lines
    }

    private static func machine() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
#endif
