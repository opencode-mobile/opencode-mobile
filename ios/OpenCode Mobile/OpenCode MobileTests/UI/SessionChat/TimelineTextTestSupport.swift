@testable import OpenCode_Mobile

/// The English users read for timeline text; nil stays nil. Takes optional and
/// non-optional fields alike, so assertions compare strings.
func english(_ text: TimelineText?) -> String? {
    text?.resolved
}
