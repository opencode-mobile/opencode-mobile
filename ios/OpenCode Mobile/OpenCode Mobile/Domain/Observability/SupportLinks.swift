import Foundation

/// Links on Settings → About that let people support the project.
enum SupportLinks {
    /// App Store write-a-review page for this app.
    static let appStoreReview = URL(string: "https://apps.apple.com/app/id6799246544?action=write-review")!
    static let gitHub = SourceCode.url
    static let xProfile = URL(string: "https://x.com/ivanfenenko")!
    static let xHandle = "@ivanfenenko"
}
