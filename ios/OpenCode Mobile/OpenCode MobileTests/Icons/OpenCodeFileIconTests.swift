import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct OpenCodeFileIconTests {
    private func file(_ path: String) -> String {
        OpenCodeFileIcon.iconName(path: path, kind: .file, expanded: false)
    }

    private func folder(_ path: String, expanded: Bool = false) -> String {
        OpenCodeFileIcon.iconName(path: path, kind: .directory, expanded: expanded)
    }

    @Test func fileNames_matchLowercasedBasename() {
        #expect(file("package.json") == "Nodejs")
        #expect(file("apps/web/PACKAGE.JSON") == "Nodejs")
        #expect(file("src\\README.md") == "Readme")
        #expect(file("Dockerfile") == "Docker")
    }

    @Test func extensions_tryDottedSuffixesLongestFirst() {
        #expect(file("src/foo.test.ts") == "TestTs")
        #expect(file("types/index.d.ts") == "TypescriptDef")
        #expect(file("a.b.ts") == "Typescript")
        #expect(file("App.tsx") == "React_ts")
        #expect(file("Sources/App.swift") == "Swift")
        #expect(file("Main.kt") == "Kotlin")
    }

    @Test func extensions_matchWholeName() {
        // `dockerfile` is also an extension key; a bare name can match it.
        #expect(file("ci/release.dockerfile") == "Docker")
    }

    @Test func unknownFiles_useDocument() {
        #expect(file("notes.zzz") == "Document")
        #expect(file("Makefile.unknownext") == "Document")
        #expect(file("") == "Document")
    }

    @Test func folders_tryNameVariants() {
        #expect(folder("src") == "FolderSrc")
        #expect(folder("packages/app/src/") == "FolderSrc")
        #expect(folder("github") == "FolderGithub")
        #expect(folder("vscode") == "FolderVscode")
        #expect(folder("tests") == "FolderTest")
    }

    @Test func folders_useOpenVariantWhenExpanded() {
        #expect(folder("src", expanded: true) == "FolderSrcOpen")
        #expect(folder("random") == "Folder")
        #expect(folder("random", expanded: true) == "FolderOpen")
    }

    @Test func everyReachableIcon_hasABundledAsset() {
        let tables = OpenCodeFileTypeIconTables.self
        var names = Set(tables.fileNames.values)
            .union(tables.fileExtensions.values)
            .union([tables.defaultFile, tables.defaultFolder, tables.defaultFolderOpen])
        for folderName in tables.folderNames.keys {
            names.insert(folder(folderName))
            names.insert(folder(folderName, expanded: true))
        }
        for name in names {
            let asset = tables.assets[name]
            #expect(asset != nil, "No asset for \(name)")
            if let asset {
                #expect(UIImage(named: asset) != nil, "Asset \(asset) is not in the bundle")
            }
        }
    }

    @Test func atSuggestion_leadingIcon() {
        let fileSuggestion = ProjectEntry(name: "App.swift", relativePath: "Sources/App.swift", isDirectory: false)
            .toFileSuggestion()
        #expect(fileSuggestion.leadingIcon == .file(path: "Sources/App.swift", kind: .file))
        let folderSuggestion = ProjectEntry(name: "src", relativePath: "src", isDirectory: true).toFileSuggestion()
        #expect(folderSuggestion.leadingIcon == .file(path: "src", kind: .directory))
        let agent = SessionChatAtSuggestion(id: "build", title: "@build", subtitle: nil, agentName: "build")
        #expect(agent.leadingIcon == .icon(.agent))
    }
}
