import Foundation
import Testing
@testable import Hex_Code

/// Long drafts and the You line (app plan, "Long drafts" and "Just sent"): the reader's paragraphs
/// and meta, and the You line's "Read all".
struct ReaderTests {
    private func part(_ index: Int) -> TakePart {
        TakePart(range: index * 100 ..< (index + 1) * 100, samples: [])
    }

    @Test func aLockedTakeReadsAsParagraphsAtItsPauses() {
        var parts = TakeParts()
        parts.add(part(0))
        parts.add(part(1))
        parts.lock()
        parts.end(tail: part(2))
        parts.finish(0, text: "First thought.")
        parts.finish(1, text: " Second one. ")
        parts.finish(2, text: "")
        #expect(parts.paragraphs == ["First thought.", "Second one."])
        #expect(parts.text == "First thought. Second one.")

        var draft = DictationDraft()
        let id = draft.beginTake()!
        draft.endTake(duration: 151)
        draft.finishTranscription(id, text: parts.text, paragraphs: parts.paragraphs)
        let taken = draft.beginTake()!
        draft.endTake(duration: 4)
        draft.finishTranscription(taken, text: "And a short one")
        #expect(draft.paragraphs == ["First thought.", "Second one.", "And a short one"])
        #expect(draft.text == "First thought. Second one. And a short one")
    }

    @Test func theMetaHasTheLengthAndTheWords() {
        #expect(ReaderMeta.text(duration: 151, words: 235) == "2:31 · 235 words")
        #expect(ReaderMeta.text(duration: nil, words: 12) == "12 words")
        #expect(ReaderMeta.text(duration: 3, words: 1) == "0:03 · 1 word")
    }

    @Test func theYouLinesReadAllCountsTheWords() {
        #expect(YouLine.readAll("Make the quiz game with three rounds") == "7 words · Read all ›")
        #expect(YouLine.maxLines == 3)
    }

    @Test @MainActor func youSaidUsesThePhonesOwnParagraphsForItsMessage() {
        let home = HomeModel(recorder: FakeRecorder(), speech: FakeSpeech())
        let other = home.sentReader(for: "Sent from the laptop")
        #expect(other.paragraphs == ["Sent from the laptop"])
        #expect(other.duration == nil)
        #expect(other.meta == "4 words")
    }
}
