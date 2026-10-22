import Testing
@testable import OpenCode_Mobile

@Suite
struct HomeProjectGroupingTests {
    private let repo = HomeProjectResolution(id: "prj_repo", canonical: "/src/repo", isRepository: true)

    @Test func worktreesOfOneRepositoryMergeInAddedOrder() {
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/other", "/src/repo-wt"],
            resolutions: [
                "/src/repo": repo,
                "/src/other": HomeProjectResolution(id: "prj_other", canonical: "/src/other", isRepository: true),
                "/src/repo-wt": repo,
            ]
        )

        #expect(projects.map(\.key) == ["prj_repo", "prj_other"])
        #expect(projects[0].memberPaths == ["/src/repo", "/src/repo-wt"])
        #expect(projects[0].primaryPath == "/src/repo")
        #expect(projects[0].projectID == "prj_repo")
        #expect(projects[0].worktrees.map(\.directory) == ["/src/repo", "/src/repo-wt"])
        #expect(projects[0].worktrees.map(\.isMain) == [true, false])
    }

    @Test func orderFollowsTheFirstMember() {
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/src/repo-wt", "/src/other", "/src/repo"],
            resolutions: [
                "/src/repo-wt": repo,
                "/src/other": HomeProjectResolution(id: "prj_other", canonical: "/src/other"),
                "/src/repo": repo,
            ]
        )

        #expect(projects.map(\.key) == ["prj_repo", "prj_other"])
        #expect(projects[0].primaryPath == "/src/repo-wt")
        // Main checkout still sorts first.
        #expect(projects[0].worktrees.first?.directory == "/src/repo")
    }

    @Test func globalProjectsNeverMerge() {
        let global = HomeProjectResolution(id: "global", canonical: "/")
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/notes", "/scratch"],
            resolutions: ["/notes": global, "/scratch": global]
        )

        #expect(projects.map(\.key) == ["path:/notes", "path:/scratch"])
        #expect(projects.map(\.displayName) == ["notes", "scratch"])
        #expect(projects.allSatisfy { $0.worktrees.count == 1 && $0.worktrees[0].isMain })
        #expect(projects.allSatisfy { !$0.isRepository })
    }

    @Test func unresolvedPathsStayAlone() {
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/src/repo", "/src/repo-wt"],
            resolutions: ["/src/repo": repo]
        )

        #expect(projects.map(\.key) == ["prj_repo", "path:/src/repo-wt"])
        #expect(projects[1].projectID == nil)
        #expect(projects[1].worktrees.map(\.directory) == ["/src/repo-wt"])
    }

    @Test func displayNamePrefersProjectNameThenMainCheckoutFolder() {
        var named = repo
        named.name = "  Mobile  "
        let byName = HomeProjectGrouping.group(addedPaths: ["/src/repo-wt"], resolutions: ["/src/repo-wt": named])
        let byFolder = HomeProjectGrouping.group(addedPaths: ["/src/repo-wt"], resolutions: ["/src/repo-wt": repo])
        let emptyName = HomeProjectGrouping.group(
            addedPaths: ["/src/repo-wt"],
            resolutions: ["/src/repo-wt": HomeProjectResolution(id: "prj_repo", canonical: "/src/repo", name: "")]
        )

        #expect(byName.first?.displayName == "Mobile")
        #expect(byFolder.first?.displayName == "repo")
        #expect(emptyName.first?.displayName == "repo")
    }

    @Test func inventoryAddsWorktreesMainFirstThenByFolderWithBranches() {
        let projects = HomeProjectGrouping.group(
            addedPaths: ["/src/repo-wt"],
            resolutions: ["/src/repo-wt": repo],
            inventories: ["/src/repo-wt": ["/wt/zeta/", "/wt/alpha", "/src/repo"]],
            branches: ["/src/repo": "main", "/wt/alpha": "HEAD", "/wt/zeta": "feature/zeta"]
        )
        let worktrees = projects[0].worktrees

        #expect(worktrees.map(\.directory) == ["/src/repo", "/wt/alpha", "/src/repo-wt", "/wt/zeta"])
        #expect(worktrees.map(\.branch) == ["main", nil, nil, "feature/zeta"])
        #expect(worktrees.map(\.title) == ["main", "alpha", "repo-wt", "feature/zeta"])
        #expect(worktrees.map(\.subtitle) == ["main checkout", "alpha", "repo-wt", "zeta"])
        #expect(projects[0].hasManyWorktrees)
    }

    @Test func branchMarksAProjectAsRepository() {
        let plain = HomeProjectResolution(id: "prj_x", canonical: "/x")
        let without = HomeProjectGrouping.group(addedPaths: ["/x"], resolutions: ["/x": plain])
        let with = HomeProjectGrouping.group(addedPaths: ["/x"], resolutions: ["/x": plain], branches: ["/x": "dev"])

        #expect(without.first?.isRepository == false)
        #expect(with.first?.isRepository == true)
    }
}

@Suite
struct HomeScopeTests {
    private let projects = HomeProjectGrouping.group(
        addedPaths: ["/src/repo", "/src/repo-wt", "/notes"],
        resolutions: [
            "/src/repo": HomeProjectResolution(id: "prj_repo", canonical: "/src/repo", isRepository: true),
            "/src/repo-wt": HomeProjectResolution(id: "prj_repo", canonical: "/src/repo", isRepository: true),
            "/notes": HomeProjectResolution(id: "global", canonical: "/"),
        ]
    )

    @Test func idsRoundTrip() {
        let scope = HomeScope.worktree(projectKey: "prj_repo", directory: "/src/repo-wt")
        #expect(HomeScope.worktreeParts(scope.id)?.projectKey == "prj_repo")
        #expect(HomeScope.worktreeParts(scope.id)?.directory == "/src/repo-wt")
        #expect(HomeScope.all.id == SessionListFilterTab.allId)
        #expect(HomeScope.project("prj_repo").id == "prj_repo")
    }

    @Test func memberPathsMapToTheirMergedProject() {
        #expect(HomeScope.resolve(id: "/src/repo-wt", projects: projects) == .project("prj_repo"))
        #expect(HomeScope.resolve(id: "path:/src/repo-wt", projects: projects) == .project("prj_repo"))
        #expect(HomeScope.resolve(id: "/notes", projects: projects) == .project("path:/notes"))
        #expect(HomeScope.resolve(id: "/gone", projects: projects) == nil)
        #expect(HomeScope.resolve(id: "all", projects: projects) == .all)
    }

    @Test func worktreeScopeFollowsItsDirectory() {
        let stale = HomeScope.worktreeID(projectKey: "path:/src/repo-wt", directory: "/src/repo-wt")
        #expect(
            HomeScope.resolve(id: stale, projects: projects)
                == .worktree(projectKey: "prj_repo", directory: "/src/repo-wt")
        )
        // A single-worktree project has no worktree scopes.
        let single = HomeScope.worktreeID(projectKey: "path:/notes", directory: "/notes")
        #expect(HomeScope.resolve(id: single, projects: projects) == .project("path:/notes"))
    }

    @Test func worktreeScopeReadsAsItsProjectWhileWorktreesAreOff() {
        let scope = HomeScope.worktreeID(projectKey: "prj_repo", directory: "/src/repo-wt")
        #expect(HomeScope.resolve(id: scope, projects: projects, worktreesEnabled: false) == .project("prj_repo"))
        #expect(
            HomeScope.resolve(id: scope, projects: projects)
                == .worktree(projectKey: "prj_repo", directory: "/src/repo-wt")
        )
        #expect(HomeScope.resolve(id: "prj_repo", projects: projects, worktreesEnabled: false) == .project("prj_repo"))
    }

    @Test func mainCheckoutDirectoryIsTheMainWorktree() {
        #expect(projects[0].mainCheckoutDirectory == "/src/repo")
        #expect(projects[1].mainCheckoutDirectory == "/notes")
        #expect(HomeScope.worktree(projectKey: "prj_repo", directory: "/x").projectKey == "prj_repo")
        #expect(HomeScope.all.projectKey == nil)
    }

    @Test func worktreeScopeKeepsOnlyThatDirectory() {
        let scope = HomeScope.worktree(projectKey: "prj_repo", directory: "/src/repo-wt")
        #expect(scope.includes(projectKey: "prj_repo", directory: "/src/repo-wt/"))
        #expect(!scope.includes(projectKey: "prj_repo", directory: "/src/repo"))
        #expect(!scope.includes(projectKey: "path:/notes", directory: "/src/repo-wt"))
        #expect(HomeScope.project("prj_repo").includes(projectKey: "prj_repo", directory: "/anything"))
        #expect(HomeScope.all.includes(projectKey: "x", directory: "/y"))
    }
}

@Suite
struct HomeRowLocationTests {
    private let project = HomeProjectGrouping.group(
        addedPaths: ["/src/opencode-mobile", "/src/opencode-mobile-723"],
        resolutions: [
            "/src/opencode-mobile": HomeProjectResolution(id: "prj", canonical: "/src/opencode-mobile", isRepository: true),
            "/src/opencode-mobile-723": HomeProjectResolution(id: "prj", canonical: "/src/opencode-mobile", isRepository: true),
        ],
        inventories: ["/src/opencode-mobile": ["/wt/brave-otter"]],
        branches: ["/src/opencode-mobile": "main", "/src/opencode-mobile-723": "feature/723-iphone-duo"]
    )[0]

    @Test func mainCheckoutShowsTheBranchOnly() {
        let location = HomeRowLocation.make(project: project, sessionDirectory: "/src/opencode-mobile", includesProjectName: false)
        #expect(location?.parts == [.branch("main")])
    }

    @Test func otherWorktreeAddsItsFolder() {
        let location = HomeRowLocation.make(project: project, sessionDirectory: "/src/opencode-mobile-723", includesProjectName: false)
        #expect(location?.parts == [.branch("feature/723-iphone-duo"), .text("opencode-mobile-723")])
        #expect(location?.text == "feature/723-iphone-duo \u{00B7} opencode-mobile-723")
    }

    @Test func allScopeLeadsWithTheProjectName() {
        let location = HomeRowLocation.make(project: project, sessionDirectory: "/src/opencode-mobile", includesProjectName: true)
        #expect(location?.parts == [.text("opencode-mobile"), .branch("main")])
    }

    @Test func unknownBranchShowsTheFolder() {
        let location = HomeRowLocation.make(project: project, sessionDirectory: "/wt/brave-otter", includesProjectName: false)
        #expect(location?.parts == [.text("brave-otter")])
        let unlisted = HomeRowLocation.make(project: project, sessionDirectory: "/elsewhere/tmp", includesProjectName: true)
        #expect(unlisted?.parts == [.text("opencode-mobile"), .text("tmp")])
    }

    @Test func missingDirectoryUsesThePrimaryPath() {
        let location = HomeRowLocation.make(project: project, sessionDirectory: nil, includesProjectName: false)
        #expect(location?.parts == [.branch("main")])
    }

    @Test func worktreesOffKeepsThePathSubtitle() {
        #expect(
            HomeRowLocation.make(
                project: project,
                sessionDirectory: "/src/opencode-mobile-723",
                includesProjectName: true,
                worktreesEnabled: false
            ) == nil
        )
    }

    @Test func nonRepositoryKeepsThePathSubtitle() {
        let plain = HomeProjectGrouping.group(
            addedPaths: ["/notes"],
            resolutions: ["/notes": HomeProjectResolution(id: "global", canonical: "/")]
        )[0]
        #expect(HomeRowLocation.make(project: plain, sessionDirectory: "/notes", includesProjectName: true) == nil)
    }
}
