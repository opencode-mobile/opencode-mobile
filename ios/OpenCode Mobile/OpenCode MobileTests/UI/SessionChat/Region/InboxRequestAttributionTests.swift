import Foundation
import Testing
@testable import OpenCode_Mobile

struct InboxRequestAttributionTests {
    private let sessions = [
        Session(id: "root", title: "Fix tests", slug: nil, projectID: nil, directory: nil, parentID: nil, updatedAtEpochMs: nil),
        Session(
            id: "child", title: "Run the suite (@general subagent)", slug: nil, projectID: nil,
            directory: nil, parentID: "root", updatedAtEpochMs: nil
        ),
        Session(
            id: "grandchild", title: "Look around (@explore subagent)", slug: nil, projectID: nil,
            directory: nil, parentID: "child", updatedAtEpochMs: nil
        ),
        Session(id: "plain", title: "Untitled child", slug: nil, projectID: nil, directory: nil, parentID: "root", updatedAtEpochMs: nil),
    ]

    @Test func ownSessionRequest_hasNoAttribution() {
        #expect(InboxRequestAttribution.resolve(requestSessionID: "root", rootSessionID: "root", sessions: sessions) == nil)
    }

    @Test func subagentRequest_namesAgentFromSessionTitle() throws {
        let attribution = try #require(
            InboxRequestAttribution.resolve(requestSessionID: "child", rootSessionID: "root", sessions: sessions)
        )
        #expect(attribution.agentName == "general")
        #expect(attribution.letter == "G")
        #expect(attribution.parentID == "root")
        #expect(attribution.permissionSummary(permission: "bash", target: "npm test") == "general · subagent wants to run: npm test")
    }

    @Test func v2SessionAgent_namesTheSubagentWithoutATitleSuffix() throws {
        var child = Session(
            id: "v2-child", title: "Run the tests", slug: nil, projectID: nil,
            directory: nil, parentID: "root", updatedAtEpochMs: nil
        )
        child.agentName = "general"
        let attribution = try #require(
            InboxRequestAttribution.resolve(requestSessionID: "v2-child", rootSessionID: "root", sessions: [child])
        )
        #expect(attribution.letter == "G")
        #expect(attribution.permissionSummary(permission: "shell", target: "npm test") == "general · subagent wants to run: npm test")
    }

    @Test func nestedSubagent_opensUnderItsOwnParent() throws {
        let attribution = try #require(
            InboxRequestAttribution.resolve(requestSessionID: "grandchild", rootSessionID: "root", sessions: sessions)
        )
        #expect(attribution.questionSummary == "explore · subagent asks")
        #expect(attribution.parentID == "child")
    }

    @Test func subagentWithoutAgentInTitle_fallsBackToPlainSubagent() throws {
        let attribution = try #require(
            InboxRequestAttribution.resolve(requestSessionID: "plain", rootSessionID: "root", sessions: sessions)
        )
        #expect(attribution.agentName == nil)
        #expect(attribution.letter == "U")
        #expect(attribution.questionSummary == "subagent asks")
        #expect(attribution.permissionSummary(permission: "edit", target: "README.md") == "subagent wants to edit: README.md")
    }

    @Test func unknownSession_stillAttributesToTheRoot() throws {
        let attribution = try #require(
            InboxRequestAttribution.resolve(requestSessionID: "missing", rootSessionID: "root", sessions: [])
        )
        #expect(attribution.parentID == "root")
        #expect(attribution.sessionTitle == "Subagent")
    }

    @Test func agentName_parsesOnlyTheTaskToolSuffix() {
        #expect(InboxRequestAttribution.agentName(fromTitle: "Scan (@explore subagent)") == "explore")
        #expect(InboxRequestAttribution.agentName(fromTitle: "Scan (@explore subagent) ") == "explore")
        #expect(InboxRequestAttribution.agentName(fromTitle: "Scan (@ subagent)") == nil)
        #expect(InboxRequestAttribution.agentName(fromTitle: "Scan @explore subagent") == nil)
        #expect(InboxRequestAttribution.agentName(fromTitle: "") == nil)
    }

    @Test func permissionSummary_withoutTargetOrKnownAction() throws {
        let attribution = try #require(
            InboxRequestAttribution.resolve(requestSessionID: "child", rootSessionID: "root", sessions: sessions)
        )
        #expect(attribution.permissionSummary(permission: "external_directory", target: nil) == "general · subagent wants to use external directory")
    }

    @Test func paging_clampsAtTheEndsAndFollowsTheSelectedID() {
        let ids = ["a", "b", "c"]
        #expect(InboxRequestPaging.index(ids: ids, selectedID: nil) == 0)
        #expect(InboxRequestPaging.index(ids: ids, selectedID: "c") == 2)
        #expect(InboxRequestPaging.index(ids: ids, selectedID: "gone") == 0)
        #expect(InboxRequestPaging.pagedID(ids: ids, selectedID: nil, delta: 1) == "b")
        #expect(InboxRequestPaging.pagedID(ids: ids, selectedID: "c", delta: 1) == "c")
        #expect(InboxRequestPaging.pagedID(ids: ids, selectedID: "a", delta: -1) == "a")
        #expect(InboxRequestPaging.pagedID(ids: [], selectedID: nil, delta: 1) == nil)
    }
}
