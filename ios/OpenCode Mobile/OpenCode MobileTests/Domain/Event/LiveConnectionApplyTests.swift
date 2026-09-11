import Testing
@testable import OpenCode_Mobile

struct LiveConnectionApplyTests {
    @Test func startDecision_occupancyGone_isIgnore() {
        #expect(
            LiveConnectionApply.startDecision(
                occupancyCurrent: false,
                applied: sample(),
                requested: sample(directories: ["/b"]),
                generation: 3
            ) == .ignore
        )
    }

    @Test func startDecision_noAppliedConfig_isStashPending() {
        #expect(
            LiveConnectionApply.startDecision(
                occupancyCurrent: true,
                applied: nil,
                requested: sample(),
                generation: 3
            ) == .stashPending
        )
    }

    @Test func startDecision_sameConfig_isIdempotent() {
        #expect(
            LiveConnectionApply.startDecision(
                occupancyCurrent: true,
                applied: sample(),
                requested: sample(),
                generation: 3
            ) == .idempotent
        )
    }

    @Test func startDecision_directoriesChanged_isReconcile() {
        let requested = sample(directories: ["/a", "/b"])
        #expect(
            LiveConnectionApply.startDecision(
                occupancyCurrent: true,
                applied: sample(),
                requested: requested,
                generation: 3
            ) == .reconcile(generation: 3, config: requested)
        )
    }

    @Test func startDecision_protocolChanged_isRetarget() {
        let requested = sample(serverProtocol: .v2)
        #expect(
            LiveConnectionApply.startDecision(
                occupancyCurrent: true,
                applied: sample(),
                requested: requested,
                generation: 3
            ) == .retarget(generation: 3, config: requested)
        )
    }

    @Test func change_profileIdentity_isIdentity() {
        #expect(
            LiveConnectionApply.change(
                applied: sample(),
                requested: sample(connectionIdentity: "http://other|opencode")
            ) == .identity
        )
    }

    private func sample(
        serverProtocol: ServerProtocol = .v1,
        directories: [String] = ["/a"],
        connectionIdentity: String = "http://host|opencode"
    ) -> LiveConnectionConfig {
        LiveConnectionConfig(
            profileID: "p1",
            connectionIdentity: connectionIdentity,
            serverProtocol: serverProtocol,
            directories: directories
        )
    }
}
