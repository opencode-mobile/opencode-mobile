import Foundation
import UIKit

protocol FeedbackMetadataProviding {
    func metadata(screen: FeedbackScreen) -> FeedbackMetadata
}

/// Reads app, device and active-connection context for a report. Only the
/// protocol and health version come from the connection, never its address,
/// credentials or profile name.
struct DefaultFeedbackMetadataProvider: FeedbackMetadataProviding {
    let activeConnectionManager: ActiveConnectionManager
    var appVersion: () -> AppVersion = { AppVersion.current() }
    var device: () -> String = { FeedbackDeviceModel.identifier() }
    var osVersion: () -> String = { "iOS \(UIDevice.current.systemVersion)" }
    var locale: () -> String = { Locale.current.identifier(.bcp47) }

    func metadata(screen: FeedbackScreen) -> FeedbackMetadata {
        let version = appVersion()
        return FeedbackMetadata(
            version: version.version,
            build: version.build,
            osVersion: osVersion(),
            device: device(),
            locale: locale(),
            screen: screen,
            serverProtocol: activeConnectionManager.serverProtocol,
            serverVersion: activeConnectionManager.serverVersion
        )
    }
}

nonisolated enum FeedbackDeviceModel {
    /// Hardware identifier such as `iPhone17,1`. The simulator reports the model
    /// it simulates instead of the host architecture.
    static func identifier(environment: [String: String] = ProcessInfo.processInfo.environment) -> String {
        if let simulated = environment["SIMULATOR_MODEL_IDENTIFIER"], !simulated.isEmpty {
            return simulated
        }
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafeBytes(of: &systemInfo.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
        return machine.isEmpty ? "unknown" : machine
    }
}
