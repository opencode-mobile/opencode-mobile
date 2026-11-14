import Foundation
import OpenCodeKit

/// The paired computer's friendly two-word name, the same on every phone (see "The server's
/// name" in the build plan). Addresses are never shown as a server's name.
enum ServerName {
    static let adjectives = [
        "Bold", "Brave", "Calm", "Chirpy", "Clever", "Cosy", "Curious", "Dapper", "Eager", "Fuzzy", "Gentle", "Happy",
        "Humble", "Jolly", "Keen", "Lucky", "Mellow", "Merry", "Nimble", "Plucky", "Polite", "Quick", "Quiet", "Rapid",
        "Sleepy", "Snappy", "Spry", "Sunny", "Swift", "Tidy", "Witty", "Zesty",
    ]
    static let animals = [
        "Badger", "Bison", "Falcon", "Fox", "Gecko", "Hedgehog", "Heron", "Koala", "Lemur", "Lynx", "Marmot", "Meerkat",
        "Narwhal", "Ocelot", "Otter", "Owl", "Panda", "Pelican", "Penguin", "Puffin", "Quokka", "Raccoon", "Robin", "Seal",
        "Sloth", "Squirrel", "Stoat", "Tapir", "Toucan", "Walrus", "Wombat", "Yak",
    ]

    /// The name for a server: its hostname when known, else the first address from the pairing code.
    static func name(hostname: String?, fallbackAddress: String) -> String {
        name(seed: seed(hostname: hostname, fallbackAddress: fallbackAddress))
    }

    /// Lowercased hostname without a trailing `.local`; the first address when there is no hostname.
    static func seed(hostname: String?, fallbackAddress: String) -> String {
        var seed = (hostname ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if seed.hasSuffix(".local") { seed.removeLast(".local".count) }
        return seed.isEmpty ? fallbackAddress : seed
    }

    /// `ADJECTIVES[h % 32] + " " + ANIMALS[(h >> 8) % 32]`, with h the 32-bit FNV-1a hash of the seed.
    static func name(seed: String) -> String {
        let normalized = self.seed(hostname: seed, fallbackAddress: seed)
        let hash = fnv1a(normalized)
        return "\(adjectives[Int(hash % 32)]) \(animals[Int((hash >> 8) % 32)])"
    }

    static func fnv1a(_ text: String) -> UInt32 {
        var hash: UInt32 = 0x811C_9DC5
        for byte in text.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 0x0100_0193
        }
        return hash
    }
}

/// Reads the server's `hostname` from `GET /api/info`, for its name.
enum ServerInfoReader {
    static func hostname(_ connection: ServerConnection) async -> String? {
        struct Info: Decodable { let hostname: String? }
        let request = connection.authorizedRequest(path: "api/info")
        guard let (data, response) = try? await OpenCodeURLSessions.rest.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200
        else { return nil }
        return (try? JSONDecoder().decode(Info.self, from: data))?.hostname
    }
}
