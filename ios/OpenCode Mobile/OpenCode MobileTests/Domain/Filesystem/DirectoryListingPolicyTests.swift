import Foundation
import Testing
@testable import OpenCode_Mobile

struct DirectoryListingPolicyTests {
    @Test func prepare_keepsVisibleDirectoriesOnly_sortedByName() {
        let nodes = [
            DirectoryNode(name: "zeta", absolutePath: "/home/zeta", isDirectory: true),
            DirectoryNode(name: "alpha", absolutePath: "/home/alpha", isDirectory: true),
            DirectoryNode(name: "readme.md", absolutePath: "/home/readme.md", isDirectory: false),
            DirectoryNode(name: ".hidden", absolutePath: "/home/.hidden", isDirectory: true),
            DirectoryNode(name: "..", absolutePath: "/home/..", isDirectory: true),
        ]

        let prepared = DirectoryListingPolicy.prepare(nodes)

        #expect(prepared.map(\.absolutePath) == ["/home/alpha", "/home/zeta"])
    }

    @Test func prepare_truncatesLongListings() {
        let nodes = (1 ... 250).map { index in
            DirectoryNode(
                name: String(format: "dir-%03d", index),
                absolutePath: String(format: "/home/dir-%03d", index),
                isDirectory: true
            )
        }

        let prepared = DirectoryListingPolicy.prepare(nodes)

        #expect(prepared.count == DirectoryListingPolicy.maxVisibleDirectories)
        #expect(prepared.first?.absolutePath == "/home/dir-001")
        #expect(
            prepared.last?.absolutePath ==
                String(format: "/home/dir-%03d", DirectoryListingPolicy.maxVisibleDirectories)
        )
    }
}
