import Foundation

struct ThemePreset: Identifiable, Equatable, Codable {
    let id: String
    let name: String
}

struct OpenCodeTheme: Codable, Equatable {
    let schema: String?
    let name: String
    let id: String
    let light: ThemeVariant
    let dark: ThemeVariant

    enum CodingKeys: String, CodingKey {
        case schema = "$schema"
        case name, id, light, dark
    }
}

struct ThemeVariant: Codable, Equatable {
    let palette: PaletteSeeds
    let overrides: [String: String]?
}

struct PaletteSeeds: Codable, Equatable {
    let neutral: String
    let ink: String
    let primary: String
    let success: String
    let warning: String
    let error: String
    let info: String
    let interactive: String?
    let diffAdd: String?
    let diffDelete: String?
}

struct ResolvedTokens: Equatable {
    var tokens: [String: String] = [:]

    subscript(_ key: String) -> String {
        tokens[key] ?? "#000000"
    }
}

enum ThemeError: LocalizedError {
    case missingPaletteSeeds([String])
    case missingModeVariants
    case invalidJSON(String)

    var errorDescription: String? {
        switch self {
        case .missingPaletteSeeds(let seeds):
            "Missing required palette seeds: \(seeds.joined(separator: ", "))"
        case .missingModeVariants:
            "Theme must include both light and dark variants"
        case .invalidJSON(let detail):
            "Invalid theme JSON: \(detail)"
        }
    }
}

extension String {
    var normalizedHex: String {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("#") {
            return trimmed
        }
        if trimmed.range(of: "^[0-9a-fA-F]{6}$", options: .regularExpression) != nil
            || trimmed.range(of: "^[0-9a-fA-F]{8}$", options: .regularExpression) != nil
        {
            return "#" + trimmed
        }
        return trimmed
    }
}
