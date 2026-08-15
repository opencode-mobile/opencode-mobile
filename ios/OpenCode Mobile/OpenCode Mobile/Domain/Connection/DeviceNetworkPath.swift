import Foundation

enum DeviceNetworkPathStatus: Equatable, Sendable {
    case ready
    case unavailable
}

protocol DeviceNetworkPath: Sendable {
    var status: DeviceNetworkPathStatus { get }
    func updates() -> AsyncStream<DeviceNetworkPathStatus>
    func waitUntilReady() async
}

struct ReadyDeviceNetworkPath: DeviceNetworkPath {
    var status: DeviceNetworkPathStatus { .ready }

    func updates() -> AsyncStream<DeviceNetworkPathStatus> {
        AsyncStream { continuation in
            continuation.yield(.ready)
            continuation.finish()
        }
    }

    func waitUntilReady() async {}
}
