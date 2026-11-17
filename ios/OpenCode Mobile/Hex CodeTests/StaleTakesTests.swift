import Foundation
import Testing
@testable import Hex_Code

/// Launch deletes takes a crash or a kill left in the temporary folder, and nothing else.
struct StaleTakesTests {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "stale-takes-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func write(_ name: String, in folder: URL, changed: Date) throws {
        let url = folder.appending(path: name)
        try Data([0]).write(to: url)
        try FileManager.default.setAttributes([.modificationDate: changed], ofItemAtPath: url.path)
    }

    private func names(in folder: URL) throws -> Set<String> {
        Set(try FileManager.default.contentsOfDirectory(atPath: folder.path))
    }

    @Test func deletesEarlierTakesOnly() throws {
        let dir = try folder()
        defer { try? FileManager.default.removeItem(at: dir) }
        let launch = Date(timeIntervalSince1970: 1_800_000_000)
        let before = launch.addingTimeInterval(-60)
        try write("take-\(UUID().uuidString).wav", in: dir, changed: before)
        try write("take-\(UUID().uuidString).wav", in: dir, changed: before)
        try write("take-started-now.wav", in: dir, changed: launch.addingTimeInterval(1))
        try write("warm-up-1.wav", in: dir, changed: before)
        try write("take-notes.txt", in: dir, changed: before)

        let removed = StaleTakes.remove(in: dir, before: launch)

        #expect(removed == 2)
        #expect(try names(in: dir) == ["take-started-now.wav", "warm-up-1.wav", "take-notes.txt"])
    }

    @Test func aMissingFolderDeletesNothing() {
        let missing = FileManager.default.temporaryDirectory.appending(path: "stale-takes-missing-\(UUID().uuidString)")
        #expect(StaleTakes.remove(in: missing, before: Date()) == 0)
    }
}
