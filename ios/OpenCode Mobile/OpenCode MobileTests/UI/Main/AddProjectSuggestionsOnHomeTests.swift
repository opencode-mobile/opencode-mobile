import Testing
@testable import OpenCode_Mobile

@MainActor
struct AddProjectSuggestionsOnHomeTests {
    @Test func otherWorktreesOfAProjectOnHomeAreNotSuggested() {
        var inputs = AddProjectSheetInputs()
        inputs.home = "/home"
        inputs.fieldText = "/home/"
        inputs.added = ["/src/repo"]
        inputs.addedProjectIDs = ["prj_repo"]
        inputs.serverProjects = [
            Project(id: "prj_repo", worktree: "/src/repo-wt", displayName: "repo-wt", activityAtEpochMs: 2, isRepository: true),
            Project(id: "prj_b", worktree: "/src/b", displayName: "b", activityAtEpochMs: 1, isRepository: true),
        ]

        let sheet = AddProjectSheetState.make(inputs)

        #expect(sheet.suggestions.map(\.path) == ["/src/b"])
    }
}
