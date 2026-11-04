// swift-tools-version: 6.0
import PackageDescription

// Hex Code's on-device speech engine on iOS: Parakeet V2 through FluidAudio (CoreML), and the 16 kHz recorder.
// adapter/ios/VoiceEngine.swift is the only public surface the app uses.
let package = Package(
    name: "VoiceEngineKit",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "VoiceEngineKit", targets: ["VoiceEngineKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/FluidInference/FluidAudio.git", exact: "0.15.5"),
    ],
    targets: [
        .target(
            name: "VoiceEngineKit",
            dependencies: [.product(name: "FluidAudio", package: "FluidAudio")],
            path: "adapter/ios"
        ),
    ],
    swiftLanguageModes: [.v6]
)
