import Foundation
import Testing
@testable import OpenCode_Mobile

struct V2SessionArchiveContractTests {
    static let v20PatchBody =
        #"{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{}},"additionalProperties":false}"#

    static let v21PatchBody =
        #"{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{},"archived":{"anyOf":[{"type":"boolean"},{"type":"null"}]}},"additionalProperties":false}"#

    static func document(patchBody: String) -> String {
        #"{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":"#
            + patchBody + "}}}}}}}"
    }

    @Test func declaredWhenSessionPatchAcceptsArchived() throws {
        #expect(try V2SessionArchiveContract.isDeclared(data: Data(Self.document(patchBody: Self.v21PatchBody).utf8)))
    }

    @Test func notDeclaredOnOlderServerPatchBody() throws {
        #expect(try !V2SessionArchiveContract.isDeclared(data: Data(Self.document(patchBody: Self.v20PatchBody).utf8)))
    }

    @Test func followsComponentReference() throws {
        let json = ##"{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/SessionUpdate"}}}}}}},"components":{"schemas":{"SessionUpdate":"##
            + Self.v21PatchBody + "}}}"

        #expect(try V2SessionArchiveContract.isDeclared(data: Data(json.utf8)))
    }

    @Test func notDeclaredWithoutSessionPatch() throws {
        #expect(try !V2SessionArchiveContract.isDeclared(data: Data(#"{"paths":{"/api/session":{"get":{}}}}"#.utf8)))
        #expect(try !V2SessionArchiveContract.isDeclared(data: Data("{}".utf8)))
    }

    @Test func rejectsNonJSONDocument() {
        #expect(throws: (any Error).self) {
            _ = try V2SessionArchiveContract.isDeclared(data: Data("<!doctype html>".utf8))
        }
    }
}
