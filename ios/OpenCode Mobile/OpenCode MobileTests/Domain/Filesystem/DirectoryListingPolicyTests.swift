import Foundation
import Testing
@testable import OpenCode_Mobile

struct DirectoryListingPolicyTests {
    @Test func directories_keepsHiddenFoldersAndDropsFilesAndDotEntries() {
        let nodes = [
            DirectoryNode(name: "zeta", absolutePath: "/home/zeta", isDirectory: true),
            DirectoryNode(name: "readme.md", absolutePath: "/home/readme.md", isDirectory: false),
            DirectoryNode(name: ".hidden", absolutePath: "/home/.hidden", isDirectory: true),
            DirectoryNode(name: ".env", absolutePath: "/home/.env", isDirectory: false),
            DirectoryNode(name: ".", absolutePath: "/home", isDirectory: true),
            DirectoryNode(name: "..", absolutePath: "/", isDirectory: true),
            DirectoryNode(name: "alpha", absolutePath: "/home/alpha", isDirectory: true),
        ]

        let directories = DirectoryListingPolicy.directories(nodes)

        #expect(Set(directories.map(\.name)) == [".hidden", "alpha", "zeta"])
        #expect(directories.filter { DirectoryListingPolicy.isHidden($0.name) }.map(\.name) == [".hidden"])
    }

    @Test func directories_sortsCaseInsensitivelyWithoutACap() {
        let names = ["beta", "Alpha", "gamma", "Delta"] + (0 ..< 300).map { String(format: "x%03d", $0) }
        let nodes = names.reversed().map { DirectoryNode(name: $0, absolutePath: "/home/\($0)", isDirectory: true) }

        let directories = DirectoryListingPolicy.directories(nodes)

        #expect(directories.count == 304)
        #expect(directories.prefix(4).map(\.name) == ["Alpha", "beta", "Delta", "gamma"])
        #expect(directories.last?.name == "x299")
    }

    @Test func visibleEntries_filtersBeforeCapping() {
        let directories = DirectoryListingPolicy.directories(
            (0 ..< 250).map { DirectoryNode(name: String(format: "f%03d", $0), absolutePath: "/h/\($0)", isDirectory: true) }
        )

        let all = DirectoryListingPolicy.visibleEntries(directories, filter: "")
        #expect(all.rows.count == DirectoryListingPolicy.maxVisibleDirectories)
        #expect(all.rows.first?.name == "f000")
        #expect(all.rows.last?.name == "f199")
        #expect(all.truncated)

        let late = DirectoryListingPolicy.visibleEntries(directories, filter: "F24")
        #expect(late.rows.map(\.name) == (240 ... 249).map { "f\($0)" })
        #expect(late.truncated == false)

        let small = DirectoryListingPolicy.visibleEntries(directories, filter: "f0", limit: 10)
        #expect(small.rows.map(\.name) == (0 ..< 10).map { String(format: "f%03d", $0) })
        #expect(small.truncated)

        let exact = DirectoryListingPolicy.visibleEntries(directories, filter: "f", limit: 250)
        #expect(exact.rows.count == 250)
        #expect(exact.truncated == false)

        #expect(DirectoryListingPolicy.visibleEntries(directories, filter: "zz").rows.isEmpty)
    }

    @Test func visibleEntries_showsHiddenFoldersOnlyForADotFilter() {
        let directories = DirectoryListingPolicy.directories([
            DirectoryNode(name: ".config", absolutePath: "/h/.config", isDirectory: true),
            DirectoryNode(name: ".cache", absolutePath: "/h/.cache", isDirectory: true),
            DirectoryNode(name: "code", absolutePath: "/h/code", isDirectory: true),
        ])

        #expect(DirectoryListingPolicy.visibleEntries(directories, filter: "").rows.map(\.name) == ["code"])
        #expect(DirectoryListingPolicy.visibleEntries(directories, filter: "c").rows.map(\.name) == ["code"])
        #expect(DirectoryListingPolicy.visibleEntries(directories, filter: ".").rows.map(\.name) == [".cache", ".config"])
        #expect(DirectoryListingPolicy.visibleEntries(directories, filter: ".CO").rows.map(\.name) == [".config"])
    }
}
