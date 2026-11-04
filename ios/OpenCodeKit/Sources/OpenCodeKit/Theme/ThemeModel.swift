import Foundation

public struct ThemePreset: Identifiable, Equatable, Codable {
    public let id: String
    public let name: String
    public var blurb: String

    public init(id: String, name: String, blurb: String = "") {
        self.id = id
        self.name = name
        self.blurb = blurb
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        blurb = try container.decodeIfPresent(String.self, forKey: .blurb) ?? ""
    }
}

public struct SyntaxHighlightPreset: Identifiable, Equatable, Codable {
    public let id: String
    public let name: String
    public var blurb: String
    public var light: [String: String]
    public var dark: [String: String]

    public var isMatch: Bool { id == Self.matchID }

    public static let matchID = "match"
    public static let match = SyntaxHighlightPreset(
        id: matchID,
        name: "Match theme",
        blurb: "Use the UI theme’s own tokens",
        light: [:],
        dark: [:]
    )

    public init(
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

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        blurb = try container.decodeIfPresent(String.self, forKey: .blurb) ?? ""
        light = try container.decodeIfPresent([String: String].self, forKey: .light) ?? [:]
        dark = try container.decodeIfPresent([String: String].self, forKey: .dark) ?? [:]
    }
}

public struct OpenCodeTheme: Codable, Equatable {
    public let schema: String?
    public let name: String
    public let id: String
    public let light: ThemeVariant
    public let dark: ThemeVariant

    public enum CodingKeys: String, CodingKey {
        case schema = "$schema"
        case name, id, light, dark
    }

    public init(
        schema: String?,
        name: String,
        id: String,
        light: ThemeVariant,
        dark: ThemeVariant
    ) {
        self.schema = schema
        self.name = name
        self.id = id
        self.light = light
        self.dark = dark
    }
}

public struct ThemeVariant: Codable, Equatable {
    public let palette: PaletteSeeds
    public let overrides: [String: String]?

    public init(
        palette: PaletteSeeds,
        overrides: [String: String]?
    ) {
        self.palette = palette
        self.overrides = overrides
    }
}

public struct PaletteSeeds: Codable, Equatable {
    public let neutral: String
    public let ink: String
    public let primary: String
    public let success: String
    public let warning: String
    public let error: String
    public let info: String
    public let interactive: String?
    public let diffAdd: String?
    public let diffDelete: String?

    public init(
        neutral: String,
        ink: String,
        primary: String,
        success: String,
        warning: String,
        error: String,
        info: String,
        interactive: String?,
        diffAdd: String?,
        diffDelete: String?
    ) {
        self.neutral = neutral
        self.ink = ink
        self.primary = primary
        self.success = success
        self.warning = warning
        self.error = error
        self.info = info
        self.interactive = interactive
        self.diffAdd = diffAdd
        self.diffDelete = diffDelete
    }
}

public struct ResolvedTokens: Equatable {
    public var tokens: [String: String] = [:]

    public subscript(_ key: String) -> String {
        tokens[key] ?? "#000000"
    }

    public init(
        tokens: [String: String] = [:]
    ) {
        self.tokens = tokens
    }
}

public enum ThemeError: LocalizedError {
    case missingPaletteSeeds([String])
    case missingModeVariants
    case invalidJSON(String)

    public var errorDescription: String? {
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
    public var normalizedHex: String {
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
