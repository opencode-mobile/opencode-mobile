import Foundation

protocol PairingCodeRedeeming: Sendable {
    func redeem(baseURL: String, code: String) async throws -> String
}

enum PairingCodeRedeemError: Error, Equatable {
    case expired
    case unreachable
}

struct PairingCodeRedeemer: PairingCodeRedeeming {
    private let session: URLSession

    init(session: URLSession = PairingCodeRedeemer.session) {
        self.session = session
    }

    func redeem(baseURL: String, code: String) async throws -> String {
        let url = try redeemURL(baseURL: baseURL, code: code)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.httpShouldHandleCookies = false

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw PairingCodeRedeemError.unreachable
        }
        guard let http = response as? HTTPURLResponse else {
            throw PairingCodeRedeemError.unreachable
        }
        if http.statusCode == 401 || http.statusCode == 403 {
            throw PairingCodeRedeemError.expired
        }
        guard http.statusCode == 200,
              let token = Self.token(from: data),
              !token.isEmpty
        else {
            throw PairingCodeRedeemError.unreachable
        }
        return token
    }

    private func redeemURL(baseURL: String, code: String) throws -> URL {
        let origin = ConnectionProfileIdentity.normalizeBaseURL(baseURL)
        guard let encodedCode = code.addingPercentEncoding(withAllowedCharacters: Self.codeCharacters),
              !encodedCode.isEmpty,
              let url = URL(string: origin + "auth/connect/" + encodedCode),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              url.host?.isEmpty == false
        else {
            throw PairingCodeRedeemError.unreachable
        }
        return url
    }

    private static func token(from data: Data) -> String? {
        struct Wire: Decodable {
            var token: String
        }
        return (try? JSONDecoder().decode(Wire.self, from: data))?.token
    }

    private static let codeCharacters = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    )

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 15
        configuration.waitsForConnectivity = false
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: RedirectPolicyDelegate.shared, delegateQueue: nil)
    }()
}
