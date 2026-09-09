import Foundation

struct ThemePreset: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    var blurb: String

    init(id: String, name: String, blurb: String = "") {
        self.id = id
        self.name = name
        self.blurb = blurb
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        blurb = try container.decodeIfPresent(String.self, forKey: .blurb) ?? ""
    }
}

struct SyntaxHighlightPreset: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    var blurb: String
    var light: [String: String]
    var dark: [String: String]

    var isMatch: Bool { id == Self.matchID }

    static let matchID = "match"
    static let match = SyntaxHighlightPreset(
        id: matchID,
        name: "Match theme",
        blurb: "Use the UI theme’s own tokens",
        light: [:],
        dark: [:]
    )

    init(
        id: String,
        name: String,
        blurb: String = "",
        light: [String: String] = [:],
        dark: [String: String] = [:]
    ) {
        self.id = id
        self.name = name
        self.blurb = blurb
        self.light = light
        self.dark = dark
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        blurb = try container.decodeIfPresent(String.self, forKey: .blurb) ?? ""
        light = try container.decodeIfPresent([String: String].self, forKey: .light) ?? [:]
        dark = try container.decodeIfPresent([String: String].self, forKey: .dark) ?? [:]
    }
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
