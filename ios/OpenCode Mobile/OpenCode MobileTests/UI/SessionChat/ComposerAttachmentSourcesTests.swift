import Testing
@testable import OpenCode_Mobile

@Suite struct ComposerAttachmentSourcesTests {
    @Test func locked_photosAndFiles_showsSheetWithoutLogFile() {
        let sources = ComposerAttachmentSources(photos: true, files: true, logFile: false)
        #expect(sources.isAttachEnabled)
        #expect(sources.showsSourceSheet)
        #expect(!sources.logFile)
    }

    @Test func locked_photosOnly_skipsSheet() {
        let sources = ComposerAttachmentSources(photos: true, files: false, logFile: false)
        #expect(sources.isAttachEnabled)
        #expect(!sources.showsSourceSheet)
    }

    @Test func locked_nothing_disablesAttach() {
        let sources = ComposerAttachmentSources(photos: false, files: false, logFile: false)
        #expect(!sources.isAttachEnabled)
        #expect(!sources.showsSourceSheet)
    }

    @Test func unlocked_textOnly_enablesAttachAndShowsLogFile() {
        let sources = ComposerAttachmentSources(photos: false, files: false, logFile: true)
        #expect(sources.isAttachEnabled)
        #expect(sources.showsSourceSheet)
        #expect(sources.logFile)
    }

    @Test func unlocked_photosOnly_showsSheetSoLogFileIsReachable() {
        let sources = ComposerAttachmentSources(photos: true, files: false, logFile: true)
        #expect(sources.isAttachEnabled)
        #expect(sources.showsSourceSheet)
        #expect(sources.photos)
        #expect(sources.logFile)
    }

    @Test func engineOwnedModel_enablesPhotosWithoutComposerModel() {
        // T3 keeps the model in the harness picker, so the composer has none selected.
        let composer = SessionChatComposerState(text: "", modelOptions: [], selectedMode: "build")
        let images = composer.attachmentSources(developerToolsUnlocked: false, allowsDocuments: false, modelOwnedByEngine: true)
        #expect(images == ComposerAttachmentSources(photos: true, files: false, logFile: false))
        let files = composer.attachmentSources(developerToolsUnlocked: false, allowsDocuments: true, modelOwnedByEngine: true)
        #expect(files == ComposerAttachmentSources(photos: true, files: true, logFile: false))
    }

    @Test func composerOwnedModel_withoutSelection_disablesAttach() {
        let sources = SessionChatComposerState(text: "", modelOptions: [], selectedMode: "build").attachmentSources(developerToolsUnlocked: false)
        #expect(!sources.isAttachEnabled)
    }
}
