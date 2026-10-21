import Foundation
import Testing
@testable import OpenCode_Mobile

struct V2VcsInfoSettledTests {
    private func info(_ json: String) throws -> VcsInfo {
        SessionDomainAdapter().vcsInfo(from: try JSONDecoder().decode(V2VcsInfoDTO.self, from: Data(json.utf8)))
    }

    @Test func coldServerAnswerWithoutProviderIsNotSettled() throws {
        let cold = try info(#"{"branch":{}}"#)
        #expect(cold.currentBranch == nil)
        #expect(!cold.isSettled)
        #expect(try !info("{}").isSettled)
    }

    @Test func providerSettlesTheAnswer() throws {
        let warm = try info(#"{"provider":"git","branch":{"current":"feature/tax-rounding","default":"main"}}"#)
        #expect(warm.currentBranch == "feature/tax-rounding")
        #expect(warm.isSettled)
        // Detached HEAD: a provider but no current branch is a settled "no branch".
        let detached = try info(#"{"provider":"git","branch":{"default":"main"}}"#)
        #expect(detached.currentBranch == nil)
        #expect(detached.isSettled)
    }

    @Test func currentBranchWithoutProviderStillCounts() throws {
        #expect(try info(#"{"branch":{"current":"dev"}}"#).isSettled)
    }

    @Test func v1AnswersAreAlwaysSettled() {
        #expect(VcsInfo(currentBranch: nil, defaultBranch: nil).isSettled)
    }
}
