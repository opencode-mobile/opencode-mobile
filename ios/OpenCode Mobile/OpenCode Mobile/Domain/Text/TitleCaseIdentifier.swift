import Foundation

enum TitleCaseIdentifier {
    static func displayName(for value: String) -> String {
        let parts = value.split { $0 == "-" || $0 == "_" }
        let titled = parts.map { part in
            part.prefix(1).uppercased() + part.dropFirst()
        }.joined(separator: " ")
        return titled.isEmpty ? value : titled
    }
}
