import Testing
@testable import OpenCode_Mobile

struct ScrollToEndUserScrollClassifierTests {
    @Test func dragWithStableContentIsUserScroll() {
        #expect(
            ScrollToEndUserScrollClassifier.isUserDrivenOffsetChange(
                contentOffsetChanged: true,
                contentSizeChanged: false,
                containerSizeChanged: false,
                isProgrammaticScrolling: false
            )
        )
    }

    @Test func contentGrowthWhilePinnedIsNotUserScroll() {
        #expect(
            !ScrollToEndUserScrollClassifier.isUserDrivenOffsetChange(
                contentOffsetChanged: true,
                contentSizeChanged: true,
                containerSizeChanged: false,
                isProgrammaticScrolling: false
            )
        )
    }

    @Test func viewportResizeIsNotUserScroll() {
        #expect(
            !ScrollToEndUserScrollClassifier.isUserDrivenOffsetChange(
                contentOffsetChanged: true,
                contentSizeChanged: false,
                containerSizeChanged: true,
                isProgrammaticScrolling: false
            )
        )
    }

    @Test func programmaticScrollIsNotUserScroll() {
        #expect(
            !ScrollToEndUserScrollClassifier.isUserDrivenOffsetChange(
                contentOffsetChanged: true,
                contentSizeChanged: false,
                containerSizeChanged: false,
                isProgrammaticScrolling: true
            )
        )
    }

    @Test func layoutWithoutOffsetChangeIsNotUserScroll() {
        #expect(
            !ScrollToEndUserScrollClassifier.isUserDrivenOffsetChange(
                contentOffsetChanged: false,
                contentSizeChanged: true,
                containerSizeChanged: false,
                isProgrammaticScrolling: false
            )
        )
    }
}
