import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionFailureTests {
    @Test func actionsFollowTheErrorType() {
        #expect(failure("provider.auth").action() == nil)
        #expect(failure("provider.auth").actionLabel() == nil)
        #expect(failure("provider.no-route").action() == .pickModel)
        #expect(failure("unknown").action() == .copy)
        #expect(failure("provider.unknown").action() == .copy)
        #expect(failure("provider.content-filter").action() == nil)
        #expect(failure("provider.rate-limit").action() == nil)
        #expect(failure("permission.rejected").action() == nil)
        #expect(failure("compaction.failed").action() == nil)
    }

    @Test func usageLimitTypesMapToTheirActions() {
        #expect(failure("provider.free-limit").action() == .pickModel)
        #expect(failure("provider.free-limit").actionLabel() == "Pick a model")
        #expect(failure("provider.model-budget-limit").action() == .pickModel)
        #expect(failure("provider.budget-limit").action() == nil)
        #expect(!failure("provider.free-limit").coversLiveRetry())
        #expect(!failure("provider.budget-limit").coversLiveRetry())
    }

    @Test func retryAtCountsFromTheRecordedTime() {
        let limited = SessionFailure.fromV2(
            type: "provider.free-limit",
            message: "Free usage limit reached.",
            status: 429,
            providerBody: nil,
            retryAfterMs: 30_000,
            recordedAtEpochMs: 1_000
        )
        #expect(limited?.retryAfterMs == 30_000)
        #expect(limited?.retryAtEpochMs() == 31_000)
        #expect(limited?.line() == "HTTP 429 · Free usage limit reached.")
    }

    @Test func noRetryAfterMeansNoRetryTime() {
        let plain = SessionFailure.fromV2(
            type: "provider.rate-limit",
            message: "Slow down",
            status: 429,
            providerBody: nil,
            recordedAtEpochMs: 1_000
        )
        #expect(plain?.retryAfterMs == nil)
        #expect(plain?.recordedAtEpochMs == nil)
        #expect(plain?.retryAtEpochMs() == nil)
        #expect(plain == SessionFailure(type: "provider.rate-limit", message: "Slow down", status: 429, providerBody: nil))

        let v1 = SessionFailure.fromV1(name: "APIError", message: "Slow down", statusCode: 429, responseBody: nil)
        #expect(v1?.retryAtEpochMs() == nil)
        #expect(failure("provider.quota").retryAtEpochMs() == nil)
    }

    @Test func negativeRetryAfterIsIgnored() {
        let failure = SessionFailure.fromV2(
            type: "provider.quota",
            message: "Quota",
            status: nil,
            providerBody: nil,
            retryAfterMs: -5,
            recordedAtEpochMs: 1_000
        )
        #expect(failure?.retryAtEpochMs() == nil)
    }

    @Test func overflowingRetryAfterIsNoHint() {
        let failure = SessionFailure.fromV2(
            type: "provider.free-limit",
            message: "Free usage limit reached.",
            status: 429,
            providerBody: nil,
            retryAfterMs: Int64.max,
            recordedAtEpochMs: 1_792_961_337_683
        )
        #expect(failure?.retryAfterMs == Int64.max)
        #expect(failure?.retryAtEpochMs() == nil)
    }

    @Test func retryAfterLabelUsesSecondsThenMinutes() {
        #expect(SessionFailure.retryAfterLabel(remainingMs: 1) == "Try again in 1 s")
        #expect(SessionFailure.retryAfterLabel(remainingMs: 11_200) == "Try again in 12 s")
        #expect(SessionFailure.retryAfterLabel(remainingMs: 90_000) == "Try again in 90 s")
        #expect(SessionFailure.retryAfterLabel(remainingMs: 90_001) == "Try again in 2 min")
        #expect(SessionFailure.retryAfterLabel(remainingMs: 240_000) == "Try again in 4 min")
        #expect(SessionFailure.retryAfterLabel(remainingMs: 3_600_000) == "Try again in 60 min")
        #expect(SessionFailure.retryAfterLabel(remainingMs: 0) == nil)
        #expect(SessionFailure.retryAfterLabel(remainingMs: -1_000) == nil)
    }

    @Test func rateLimitAndQuotaAreCoveredByTheLiveRetry() {
        #expect(failure("provider.rate-limit").coversLiveRetry())
        #expect(failure(" Provider.Quota ").coversLiveRetry())
        #expect(!failure("provider.auth").coversLiveRetry())
        #expect(!failure("provider.content-filter").coversLiveRetry())
        #expect(!failure("provider.no-route").coversLiveRetry())
    }

    @Test func contextOverflowOffersCompact() {
        #expect(failure("ContextOverflowError", message: "Context overflow").action() == .compact)
        #expect(
            failure("provider.invalid-request", message: "the context is too long", body: "max length").action()
                == .compact
        )
        #expect(failure("provider.invalid-request", message: "bad field").action() == nil)
    }

    @Test func contentFilterHidesTheRetryCard() {
        let hidden = SessionActivity.retrying(
            attempt: 1,
            nextAtMs: 5_000,
            message: "blocked",
            errorType: "provider.content-filter",
            actionLabel: nil,
            actionLink: nil
        ).retryCard
        #expect(hidden == nil)

        let shown = SessionActivity.retrying(
            attempt: 2,
            nextAtMs: 5_000,
            message: "  Slow down please, this reason is longer than eighty characters and must be cut off here now ",
            errorType: "provider.rate-limit",
            actionLabel: " Upgrade ",
            actionLink: " https://example.com "
        ).retryCard
        #expect(shown?.attempt == 2)
        #expect(shown?.nextAtMs == 5_000)
        #expect(shown?.message.count == 80)
        #expect(shown?.actionLabel == "Upgrade")
        #expect(shown?.actionLink == "https://example.com")
    }

    private func failure(_ type: String, message: String = "failed", body: String? = nil) -> SessionFailure {
        SessionFailure(type: type, message: message, status: nil, providerBody: body)
    }
}
