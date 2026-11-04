import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct SessionTitleSearchTests {
    @Test func normalizedQuery_trimsWhitespace() {
        #expect(SessionTitleSearch.normalizedQuery("  search \n") == "search")
        #expect(SessionTitleSearch.normalizedQuery("   ").isEmpty)
    }

    @Test func matchRange_isTheFirstCaseInsensitiveOccurrence() throws {
        let title = "Search the search bar"
        let range = try #require(SessionTitleSearch.matchRange(in: title, query: "SEARCH"))
        #expect(title[range] == "Search")
        #expect(range.lowerBound == title.startIndex)
    }

    @Test func matchRange_usesTheTrimmedQuery() throws {
        let title = "Fix login flow"
        let range = try #require(SessionTitleSearch.matchRange(in: title, query: " login "))
        #expect(title[range] == "login")
    }

    @Test func matchRange_isNilForABlankOrMissingQuery() {
        #expect(SessionTitleSearch.matchRange(in: "Anything", query: "") == nil)
        #expect(SessionTitleSearch.matchRange(in: "Anything", query: "  ") == nil)
        #expect(SessionTitleSearch.matchRange(in: "Anything", query: "nothing") == nil)
    }

    @Test func matches_followsMatchRange() {
        #expect(SessionTitleSearch.matches("Refactor Home", query: "home"))
        #expect(!SessionTitleSearch.matches("Refactor Home", query: "chat"))
        #expect(!SessionTitleSearch.matches("Refactor Home", query: " "))
    }

    @Test func searchResults_keepsMatchingRootsThatAreNotArchived() {
        let sessions = [
            session("root", title: "Search on Home"),
            session("other", title: "Unrelated"),
            session("child", title: "search subtask", parentID: "root"),
            session("archived", title: "Old search", archived: 9),
        ]

        #expect(SessionTitleSearch.searchResults(sessions, query: "search").map(\.id) == ["root"])
    }

    private func session(_ id: String, title: String, parentID: String? = nil, archived: Int64? = nil) -> Session {
        Session(
            id: id,
            title: title,
            slug: nil,
            projectID: nil,
            directory: "/repo",
            parentID: parentID,
            updatedAtEpochMs: 1,
            archivedAtEpochMs: archived
        )
    }
}
