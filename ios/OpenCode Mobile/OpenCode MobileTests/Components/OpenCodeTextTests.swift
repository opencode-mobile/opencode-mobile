import Foundation
import Testing
@testable import OpenCode_Mobile

struct OpenCodeTextTests {
    @Test func literalIsLookedUpInTheStringCatalog() {
        let text: OpenCodeText = "servers.title"

        #expect(text.resolved == "Servers")
    }

    @Test func pluralKeyPicksTheQuantity() {
        func label(_ count: Int) -> String {
            OpenCodeText(LocalizedStringResource(
                "servers.profile_count",
                defaultValue: "\(count) Connection Profiles"
            )).resolved
        }

        #expect(label(1) == "1 Connection Profile")
        #expect(label(3) == "3 Connection Profiles")
    }

    @Test func placeholderArgumentStaysVerbatim() {
        let name = "servers.title"
        let text = OpenCodeText(LocalizedStringResource(
            "servers.remove_dialog.title_named",
            defaultValue: "Remove “\(name)”?"
        ))

        #expect(text.resolved == "Remove “servers.title”?")
    }

    @Test func verbatimIsNeverLookedUp() {
        #expect(OpenCodeText.verbatim("servers.title").resolved == "servers.title")
        #expect(OpenCodeText.verbatim("50% done %@").resolved == "50% done %@")
    }

    @Test func unknownLiteralFallsBackToItsEnglishText() {
        let text: OpenCodeText = "Recent Window"

        #expect(text.resolved == "Recent Window")
    }

    @Test func verbatimFallbackUsesContentOnlyWhenNotBlank() {
        #expect(OpenCodeText.verbatim("Server error", fallback: "common.cancel").resolved == "Server error")
        #expect(OpenCodeText.verbatim("  ", fallback: "common.cancel").resolved == "Cancel")
        #expect(OpenCodeText.verbatim(nil, fallback: "common.cancel").resolved == "Cancel")
    }

    @Test func equalityKeepsLocalizedAndVerbatimApart() {
        #expect(OpenCodeText("common.cancel") == "common.cancel")
        #expect(OpenCodeText.verbatim("common.cancel") != "common.cancel")
        #expect(OpenCodeText.unlocalized("Cancel") == .verbatim("Cancel"))
    }
}
