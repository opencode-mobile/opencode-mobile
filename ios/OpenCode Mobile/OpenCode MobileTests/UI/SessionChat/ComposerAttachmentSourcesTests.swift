import Testing
@testable import OpenCode_Mobile

@Suite struct ComposerAttachmentSourcesTests {
    @Test func noLogFile_photosAndFiles_showsSheet() {
        let sources = ComposerAttachmentSources(photos: true, files: true, logFile: false)
        #expect(sources.isAttachEnabled)
        #expect(sources.showsSourceSheet)
        #expect(!sources.logFile)
    }

    @Test func noLogFile_photosOnly_skipsSheet() {
        let sources = ComposerAttachmentSources(photos: true, files: false, logFile: false)
        #expect(sources.isAttachEnabled)
        #expect(!sources.showsSourceSheet)
    }

    @Test func noLogFile_nothing_disablesAttach() {
        let sources = ComposerAttachmentSources(photos: false, files: false, logFile: false)
        #expect(!sources.isAttachEnabled)
        #expect(!sources.showsSourceSheet)
    }

    @Test func logFile_textOnly_enablesAttachAndShowsLogFile() {
        let sources = ComposerAttachmentSources(photos: false, files: false, logFile: true)
        #expect(sources.isAttachEnabled)
        #expect(sources.showsSourceSheet)
        #expect(sources.logFile)
    }

    @Test func logFile_photosOnly_showsSheetSoLogFileIsReachable() {
        let sources = ComposerAttachmentSources(photos: true, files: false, logFile: true)
        #expect(sources.isAttachEnabled)
        #expect(sources.showsSourceSheet)
        #expect(sources.photos)
        #expect(sources.logFile)
    }

    @Test func composer_alwaysOffersLogFile() {
        let composer = SessionChatComposerState(text: "", modelOptions: [], selectedMode: "build")
        #expect(composer.attachmentSources.logFile)
        #expect(composer.attachmentSources.isAttachEnabled)
    }

    @Test func streamedUploads_acceptAnyType() {
        var composer = SessionChatComposerState(text: "", modelOptions: [], selectedMode: "build")
        #expect(!composer.attachmentSources.acceptsAnyType)

        composer.streamedUploadAvailable = true
        #expect(composer.attachmentSources.acceptsAnyType)
    }
}
