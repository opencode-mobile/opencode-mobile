import Foundation
import Testing
@testable import OpenCode_Mobile

/// The deny note rides on the reply body; without one the key must be absent (the v2
/// body allows no extra properties, and v1 treats any message as a note).
@MainActor
struct PermissionReplyDTOTests {
    private func json(_ value: some Encodable) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @Test func v1Reply_withoutMessage_omitsTheKey() throws {
        let body = try json(PermissionResponseRequestDTO(reply: "reject"))
        #expect(body["reply"] as? String == "reject")
        #expect(body.keys.sorted() == ["reply"])
    }

    @Test func v1Reply_withMessage_sendsIt() throws {
        let body = try json(PermissionResponseRequestDTO(reply: "reject", message: "Use the staging config"))
        #expect(body["reply"] as? String == "reject")
        #expect(body["message"] as? String == "Use the staging config")
    }

    @Test func v2Reply_withoutMessage_omitsTheKey() throws {
        let body = try json(V2PermissionReplyRequestDTO(decision: "reject"))
        #expect(body["decision"] as? String == "reject")
        #expect(body.keys.sorted() == ["decision"])
    }

    @Test func v2Reply_withMessage_sendsIt() throws {
        let body = try json(V2PermissionReplyRequestDTO(decision: "reject", message: "Use the staging config"))
        #expect(body["decision"] as? String == "reject")
        #expect(body["message"] as? String == "Use the staging config")
    }

    @Test func adapterNote_isTrimmed_blankDropped_andOnlyForDeny() {
        let adapter = SessionDomainAdapter()
        #expect(adapter.permissionReplyMessage("  Use pnpm\n", decision: .deny) == "Use pnpm")
        #expect(adapter.permissionReplyMessage(" \n\t", decision: .deny) == nil)
        #expect(adapter.permissionReplyMessage(nil, decision: .deny) == nil)
        #expect(adapter.permissionReplyMessage("Use pnpm", decision: .allowOnce) == nil)
        #expect(adapter.permissionReplyMessage("Use pnpm", decision: .alwaysAllow) == nil)
    }
}
