import Foundation

enum DeveloperToolsUnlock {
    static let requiredTaps = 10
    static let tapWindowMs: Int64 = 2_000

    static func nextCount(previousCount: Int, lastTapAtMs: Int64, nowMs: Int64) -> Int {
        if nowMs - lastTapAtMs > tapWindowMs { return 1 }
        return previousCount + 1
    }
}
