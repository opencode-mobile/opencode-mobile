// swift-tools-version: 6.2
import PackageDescription

// Code shared by OpenCode Mobile and Hex Code: networking, connection and pairing, secure credentials,
// the server API clients, theme, and the base components. Moved here from the OpenCode Mobile target
// unchanged, so its build settings match that target's (Swift 5 mode, MainActor by default,
// approachable concurrency).
let package = Package(
    name: "OpenCodeKit",
    platforms: [.iOS("17.5")],
    products: [
        .library(name: "OpenCodeKit", targets: ["OpenCodeKit"]),
    ],
    targets: [
        .target(
            name: "OpenCodeKit",
            path: "Sources/OpenCodeKit",
            swiftSettings: [
                .defaultIsolation(MainActor.self),
                .enableUpcomingFeature("MemberImportVisibility"),
                .enableUpcomingFeature("DisableOutwardActorInference"),
                .enableUpcomingFeature("GlobalActorIsolatedTypesUsability"),
                .enableUpcomingFeature("InferIsolatedConformances"),
                .enableUpcomingFeature("InferSendableFromCaptures"),
                .enableUpcomingFeature("NonisolatedNonsendingByDefault"),
            ]
        ),
    ],
    swiftLanguageModes: [.v5]
)
