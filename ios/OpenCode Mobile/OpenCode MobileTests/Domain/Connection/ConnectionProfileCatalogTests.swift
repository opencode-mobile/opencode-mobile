import Foundation
import Testing
@testable import OpenCode_Mobile

struct ConnectionProfileCatalogTests {
    @Test func saveFirstProfile_assignsIdAndMakesActive() throws {
        let catalog = try ConnectionProfileCatalog().save(profile(baseURL: "http://127.0.0.1:4096", name: "Mini"))
        let saved = catalog.profiles[0]
        #expect(!saved.id.isEmpty)
        #expect(saved.baseURL == "http://127.0.0.1:4096/")
        #expect(saved.name == "Mini")
        #expect(catalog.activeId == saved.id)
        #expect(catalog.activeProfile()?.displayName == "Mini")
    }

    @Test func saveNewProfile_appendsAndActivates() throws {
        let first = try ConnectionProfileCatalog().save(profile(baseURL: "http://10.0.0.1:4096/", name: "Home"))
        let second = try first.save(profile(baseURL: "http://10.0.0.2:4096/", name: "Work"))
        #expect(second.profiles.count == 2)
        #expect(second.activeProfile()?.name == "Work")
        #expect(first.activeProfile()?.name == "Home")
    }

    @Test func saveExistingProfile_doesNotSwitchActive() throws {
        let first = try ConnectionProfileCatalog().save(profile(baseURL: "http://10.0.0.1:4096/", name: "Home", id: "home"))
        let two = try first.save(profile(baseURL: "http://10.0.0.2:4096/", name: "Work", id: "work"))
        let edited = try two.save(profile(baseURL: "http://10.0.0.1:4096/", name: "Studio", id: "home"))
        #expect(edited.activeId == "work")
        #expect(edited.profiles.first { $0.id == "home" }?.name == "Studio")
    }

    @Test func saveRejectsDuplicateUrlAndUsername() throws {
        let catalog = try ConnectionProfileCatalog().save(profile(baseURL: "http://10.0.0.1:4096/", username: "opencode"))
        #expect(throws: DuplicateConnectionProfileException.self) {
            try catalog.save(profile(baseURL: "http://10.0.0.1:4096", username: "opencode"))
        }
    }

    @Test func saveRejectsSecondProfileOnTheOtherStoredAddress() throws {
        let lan = "http://192.168.1.2:49374/"
        let tailscale = "http://100.64.0.3:49374/"
        let catalog = try ConnectionProfileCatalog().save(
            ConnectionProfile(
                baseURL: tailscale,
                serverURLs: [lan, tailscale],
                username: "opencode",
                password: "secret",
                id: "qr"
            )
        )
        #expect(throws: DuplicateConnectionProfileException.self) {
            try catalog.save(
                ConnectionProfile(
                    baseURL: lan,
                    username: "opencode",
                    password: "secret",
                    id: "other"
                )
            )
        }
        let moved = try catalog.save(
            ConnectionProfile(
                baseURL: lan,
                serverURLs: [lan, tailscale],
                username: "opencode",
                password: "secret",
                id: "qr"
            )
        )
        #expect(moved.profiles.count == 1)
        #expect(moved.profiles[0].id == "qr")
        #expect(moved.profiles[0].baseURL == lan)
        #expect(moved.profiles[0].serverURLs == [lan, tailscale])
    }

    @Test func saveAllowsSameUrlDifferentUsername() throws {
        let catalog = try ConnectionProfileCatalog()
            .save(profile(baseURL: "http://10.0.0.1:4096/", username: "opencode"))
            .save(profile(baseURL: "http://10.0.0.1:4096/", username: "other"))
        #expect(catalog.profiles.count == 2)
    }

    @Test func blankName_fallsBackToHostLabel() {
        let profile = profile(baseURL: "http://192.168.1.42:4096/", name: "  ")
        #expect(profile.displayName == "192.168.1.42:4096")
    }

    @Test func removeActive_selectsNextThenPrevious() throws {
        let catalog = try ConnectionProfileCatalog()
            .save(profile(baseURL: "http://10.0.0.1:4096/", name: "A", id: "a"))
            .save(profile(baseURL: "http://10.0.0.2:4096/", name: "B", id: "b"))
            .save(profile(baseURL: "http://10.0.0.3:4096/", name: "C", id: "c"))
            .apply(id: "b")
        let afterB = catalog.remove(id: "b")
        #expect(afterB.activeId == "c")
        let afterC = afterB.remove(id: "c")
        #expect(afterC.activeId == "a")
        let empty = afterC.remove(id: "a")
        #expect(empty.activeId == nil)
        #expect(empty.profiles.isEmpty)
    }

    @Test func removeNonActive_leavesActiveUnchanged() throws {
        let catalog = try ConnectionProfileCatalog()
            .save(profile(baseURL: "http://10.0.0.1:4096/", name: "A", id: "a"))
            .save(profile(baseURL: "http://10.0.0.2:4096/", name: "B", id: "b"))
        let removed = catalog.remove(id: "a")
        #expect(removed.activeId == "b")
        #expect(removed.profiles.map { $0.id } == ["b"])
    }

    private func profile(
        baseURL: String,
        username: String = "opencode",
        name: String = "",
        id: String = ""
    ) -> ConnectionProfile {
        ConnectionProfile(
            baseURL: baseURL,
            username: username,
            password: "secret",
            id: id,
            name: name
        )
    }
}
