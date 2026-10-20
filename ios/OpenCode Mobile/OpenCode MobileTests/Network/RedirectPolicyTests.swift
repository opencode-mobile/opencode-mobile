import Foundation
import Testing
@testable import OpenCode_Mobile

struct RedirectPolicyTests {
    private func request(_ url: String, authorization: String? = "Basic abc") -> URLRequest {
        var request = URLRequest(url: URL(string: url)!)
        if let authorization {
            request.setValue(authorization, forHTTPHeaderField: "Authorization")
        }
        return request
    }

    @Test func sameOriginRedirect_keepsTheAuthorizationHeader() {
        let decision = RedirectPolicy.decide(
            original: request("https://box.example.com/api/session"),
            redirect: request("https://box.example.com/api/session/")
        )
        guard case let .follow(next) = decision else {
            Issue.record("expected follow")
            return
        }
        #expect(next.value(forHTTPHeaderField: "Authorization") == "Basic abc")
    }

    @Test func crossOriginRedirect_stripsCredentials() {
        var redirect = request("https://other.example.com/login")
        redirect.setValue("session=1", forHTTPHeaderField: "Cookie")
        let decision = RedirectPolicy.decide(
            original: request("https://box.example.com/api/session"),
            redirect: redirect
        )
        guard case let .follow(next) = decision else {
            Issue.record("expected follow")
            return
        }
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(next.value(forHTTPHeaderField: "Cookie") == nil)
        #expect(next.url?.host == "other.example.com")
    }

    @Test func differentPortIsAnotherOrigin() {
        let decision = RedirectPolicy.decide(
            original: request("http://192.168.0.5:4096/global/health"),
            redirect: request("http://192.168.0.5:8080/global/health")
        )
        guard case let .follow(next) = decision else {
            Issue.record("expected follow")
            return
        }
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func httpsToHttpDowngrade_isRefused() {
        let decision = RedirectPolicy.decide(
            original: request("https://box.example.com/api/session"),
            redirect: request("http://box.example.com/api/session")
        )
        #expect(decision == .refuse)
    }

    @Test func httpToHttpsUpgrade_sameHost_isFollowedWithCredentials() {
        let decision = RedirectPolicy.decide(
            original: request("http://box.example.com/api/session"),
            redirect: request("https://box.example.com/api/session")
        )
        // Scheme change makes it another origin: followed, but without the header.
        guard case let .follow(next) = decision else {
            Issue.record("expected follow")
            return
        }
        #expect(next.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func missingOriginalRequest_isRefused() {
        #expect(RedirectPolicy.decide(original: nil, redirect: request("https://x.example.com")) == .refuse)
    }
}
