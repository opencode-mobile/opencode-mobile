// swift-tools-version: 6.0
import PackageDescription

// Hex's on-device speech engine for Hex Code on iOS. upstream/ is a read-only mirror of Hex files
// (scripts/sync-hex.mjs); adapter/ is the only public surface the app uses. Built in the same
// Swift 6 mode as Hex so mirrored files compile unchanged.
let package = Package(
    name: "HexVoiceEngine",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "HexVoiceEngine", targets: ["HexVoiceEngine"]),
    ],
    dependencies: [
        // same version as Hex's ios/project.yml
        .package(url: "https://github.com/FluidInference/FluidAudio.git", exact: "0.15.5"),
    ],
    targets: [
        .target(
            name: "HexVoiceEngine",
            dependencies: [.product(name: "FluidAudio", package: "FluidAudio")],
            path: ".",
            exclude: ["hex-upstream.json", "upstream/LICENSE"],
            sources: ["upstream/ios", "adapter/ios"]
        ),
    ],
    swiftLanguageModes: [.v6]
)
