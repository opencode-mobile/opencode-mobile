import Foundation

public enum VcsDiffMode: String, Sendable {
    case workingTree = "git"
    case mainBranch = "branch"

    public var v2WireValue: String {
        switch self {
        case .workingTree: "working"
        case .mainBranch: "branch"
        }
    }
}
