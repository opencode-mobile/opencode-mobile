import Foundation
import Testing
@testable import OpenCode_Mobile

struct AttachmentRejectionTests {
    @Test func everyRejectionReadsItsKeyedEnglish() {
        let expected: [(AttachmentRejection, String)] = [
            (.folder, "Folders can't be attached."),
            (.duplicate, "This file has already been uploaded"),
            (.tooLarge(), "This file is larger than 20 MB."),
            (.unsupportedType, "Only images, PDFs, or text files can be attached here."),
            (.modelWithoutImages, "This model does not support image attachments."),
            (.modelWithoutPDF, "This model does not support PDF attachments."),
            (.modelWithoutSome, "This model does not support some of the selected attachments."),
            (.unreadable, "The attachment could not be read."),
            (.invalidData, "The attachment data is invalid."),
            (.uploadFailed, "Upload failed"),
        ]
        for (rejection, english) in expected {
            #expect(String(localized: rejection.text) == english, "\(rejection.logMessage)")
        }
    }

    @Test func theSizeLimitIsAnArgument() {
        #expect(String(localized: AttachmentRejection.tooLarge(limitBytes: 5 * 1024 * 1024).text) == "This file is larger than 5 MB.")
    }

    @Test func onlyAnAttachmentLoadErrorKeepsItsReason() {
        #expect(AttachmentLoadError.tooLarge.attachmentRejection == .tooLarge())
        #expect(AttachmentLoadError.invalidDataURL.attachmentRejection == .invalidData)
        #expect(CocoaError(.fileReadNoPermission).attachmentRejection == .unreadable)
        #expect(AttachmentLoadError.unsupportedType.localizedDescription == "Only images, PDFs, or text files can be attached here.")
    }
}
