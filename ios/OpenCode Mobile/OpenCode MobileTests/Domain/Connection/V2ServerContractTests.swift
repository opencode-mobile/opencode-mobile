import Foundation
import Testing
@testable import OpenCode_Mobile

struct V2ServerContractTests {
    static let v20PatchBody =
        #"{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{}},"additionalProperties":false}"#

    static let v21PatchBody =
        #"{"type":"object","properties":{"title":{"anyOf":[{"type":"string"},{"type":"null"}]},"metadata":{},"permissions":{},"archived":{"anyOf":[{"type":"boolean"},{"type":"null"}]}},"additionalProperties":false}"#

    static func document(patchBody: String) -> String {
        #"{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":"#
            + patchBody + "}}}}}}}"
    }

    @Test func declaredWhenSessionPatchAcceptsArchived() throws {
        #expect(try V2ServerContract.features(data: Data(Self.document(patchBody: Self.v21PatchBody).utf8)).sessionArchive)
    }

    @Test func notDeclaredOnOlderServerPatchBody() throws {
        #expect(try !V2ServerContract.features(data: Data(Self.document(patchBody: Self.v20PatchBody).utf8)).sessionArchive)
    }

    @Test func followsComponentReference() throws {
        let json = ##"{"paths":{"/api/session/{sessionID}":{"patch":{"requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/SessionUpdate"}}}}}}},"components":{"schemas":{"SessionUpdate":"##
            + Self.v21PatchBody + "}}}"

        #expect(try V2ServerContract.features(data: Data(json.utf8)).sessionArchive)
    }

    @Test func notDeclaredWithoutSessionPatch() throws {
        #expect(try !V2ServerContract.features(data: Data(#"{"paths":{"/api/session":{"get":{}}}}"#.utf8)).sessionArchive)
        #expect(try !V2ServerContract.features(data: Data("{}".utf8)).sessionArchive)
    }

    @Test func turnDiffDeclaredWhenSessionDiffRouteExists() throws {
        let features = try V2ServerContract.features(data: Data(#"{"paths":{"/api/session/{sessionID}/diff":{"get":{}}}}"#.utf8))

        #expect(features == V2ServerFeatures(sessionArchive: false, sessionTurnDiff: true))
    }

    @Test func turnDiffNotDeclaredOnOlderServer() throws {
        #expect(try !V2ServerContract.features(data: Data(Self.document(patchBody: Self.v20PatchBody).utf8)).sessionTurnDiff)
        #expect(try !V2ServerContract.features(data: Data("{}".utf8)).sessionTurnDiff)
    }

    @Test func sessionGenerateDeclaredWhenGenerateRouteExists() throws {
        let features = try V2ServerContract.features(data: Data(#"{"paths":{"/api/session/{sessionID}/generate":{"post":{}}}}"#.utf8))

        #expect(features == V2ServerFeatures(sessionGenerate: true))
        #expect(ServerCapabilities.forProtocol(.v2, v2Features: features).supportsSideQuestion)
    }

    @Test func sessionGenerateNotDeclaredWithoutRoute() throws {
        #expect(try !V2ServerContract.features(data: Data(#"{"paths":{"/api/experimental/generate":{"post":{}}}}"#.utf8)).sessionGenerate)
        #expect(try !V2ServerContract.features(data: Data("{}".utf8)).sessionGenerate)
        #expect(!ServerCapabilities.forProtocol(.v2).supportsSideQuestion)
        #expect(!ServerCapabilities.forProtocol(.v1, v2Features: V2ServerFeatures(sessionGenerate: true)).supportsSideQuestion)
    }

    @Test func rejectsNonJSONDocument() {
        #expect(throws: (any Error).self) {
            _ = try V2ServerContract.features(data: Data("<!doctype html>".utf8))
        }
    }
}
