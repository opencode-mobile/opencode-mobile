import Testing
@testable import OpenCode_Mobile

struct SseSubscribeTargetsTests {
    @Test func v2_isApiEventOnly() {
        let targets = SseSubscribeTargets.targets(
            protocol: .v2,
            directories: ["/tmp/a", "/tmp/b"]
        )
        #expect(targets == [SseSubscribeTargets.Target(path: "api/event", directory: nil)])
    }

    @Test func v1_isGlobalPlusDistinctInstanceDirectories() {
        let targets = SseSubscribeTargets.targets(
            protocol: .v1,
            directories: ["/tmp/a", " /tmp/a ", "", "/tmp/b"]
        )
        #expect(targets == [
            SseSubscribeTargets.Target(path: "global/event", directory: nil),
            SseSubscribeTargets.Target(path: "event", directory: "/tmp/a"),
            SseSubscribeTargets.Target(path: "event", directory: "/tmp/b"),
        ])
    }
}
