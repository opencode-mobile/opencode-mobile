import Testing
import UIKit
@testable import OpenCode_Mobile

struct SessionChatAttachmentDropTrackerTests {
    private let image = [AttachmentDropItem(kind: .image, filename: "photo.png")]
    private let noImages = AttachmentDropContext(imagesSupported: false)

    @Test func acceptedDragShowsTheCardAndDropAttaches() {
        var tracker = SessionChatAttachmentDropTracker()
        tracker.update(inside: true, items: { image }, context: { AttachmentDropContext() })

        #expect(tracker.summary?.rejected == false)
        #expect(tracker.operation == .copy)
        let attaches = tracker.finish()
        #expect(attaches)
        #expect(tracker.summary == nil)
    }

    @Test func declinedDragClearsTheCardWhenItEnds() {
        var tracker = SessionChatAttachmentDropTracker()
        tracker.update(inside: true, items: { image }, context: { noImages })

        #expect(tracker.summary?.rejected == true)
        #expect(tracker.summary?.subtitle == AttachmentDropRouting.imageUnsupportedMessage)
        #expect(tracker.operation == .forbidden)
        let attaches = tracker.finish()
        #expect(!attaches)
        #expect(tracker.summary == nil)
    }

    @Test func leavingTheChatClearsTheCardAndCancels() {
        var tracker = SessionChatAttachmentDropTracker()
        tracker.update(inside: true, items: { image }, context: { noImages })
        tracker.update(inside: false, items: { image }, context: { noImages })

        #expect(tracker.summary == nil)
        #expect(tracker.operation == .cancel)
    }

    @Test func textOnlyDragShowsNoCard() {
        var tracker = SessionChatAttachmentDropTracker()
        tracker.update(inside: true, items: { [] }, context: { AttachmentDropContext() })

        #expect(tracker.summary == nil)
        #expect(tracker.operation == .cancel)
        let attaches = tracker.finish()
        #expect(!attaches)
    }

    @Test func summaryIsWordedOncePerDrag() {
        var tracker = SessionChatAttachmentDropTracker()
        var routed = 0
        for _ in 0 ..< 3 {
            tracker.update(inside: true, items: { routed += 1; return image }, context: { AttachmentDropContext() })
        }

        #expect(routed == 1)
    }
}
