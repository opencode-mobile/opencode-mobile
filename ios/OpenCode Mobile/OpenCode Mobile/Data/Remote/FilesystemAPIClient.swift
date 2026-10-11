import Foundation

nonisolated struct FilesystemAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let urlSession: URLSession
    private let decoder = JSONDecoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = OpenCodeURLSessions.rest
    ) {
        self.connectionProvider = connectionProvider
        self.urlSession = urlSession
    }

    init(fixedConnection: ServerConnection, urlSession: URLSession = OpenCodeURLSessions.rest) {
        self.connectionProvider = { fixedConnection }
        self.urlSession = urlSession
    }

    func getPaths() async throws -> PathDTO {
        try await get(path: "path")
    }

    // Lists absoluteDirectory via path=. + directory= so paths outside the boot worktree work.
    func listFiles(absoluteDirectory: String) async throws -> [FileNodeDTO] {
        try await get(path: "file", directory: absoluteDirectory, query: ["path": "."])
    }

    func getLocationV2() async throws -> V2LocationInfoDTO {
        try await get(path: "api/location")
    }

    func listFilesV2(absoluteDirectory: String) async throws -> [V2FsEntryDTO] {
        let envelope: APILocatedEnvelope<[V2FsEntryDTO]> = try await get(
            path: "api/fs/list",
            locationDirectory: absoluteDirectory,
            query: ["path": "."]
        )
        return envelope.data
    }

    // MARK: File Browser (worktree stays the directory; only `path` varies)

    /// v1 `GET file?path=<dir>&directory=<worktree>`; `.` lists the worktree root.
    func listProjectEntries(worktree: String, relativeDirectory: String) async throws -> [FileNodeDTO] {
        try await get(
            path: "file",
            directory: worktree,
            query: ["path": relativeDirectory.isEmpty ? "." : relativeDirectory]
        )
    }

    /// v1 `GET find/file?query=&dirs=false&limit=`.
    func findProjectFiles(worktree: String, query: String, limit: Int) async throws -> [String] {
        try await get(
            path: "find/file",
            directory: worktree,
            query: ["query": query, "dirs": "false", "limit": String(limit)]
        )
    }

    /// v1 `GET file/content`. Reading stops past `limit` bytes of JSON body.
    func readProjectFile(worktree: String, relativePath: String, limit: Int) async throws -> BoundedHTTPBody {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        var request = connection.authorizedRequest(path: "file/content", directory: worktree)
        request.url = request.url.map { Self.appendingQuery($0, ["path": relativePath]) }
        return try await boundedData(for: request, limit: limit)
    }

    /// v2 `GET api/fs/list`; the root omits `path`.
    func listProjectEntriesV2(worktree: String, relativeDirectory: String) async throws -> [V2FsEntryDTO] {
        let envelope: APILocatedEnvelope<[V2FsEntryDTO]> = try await get(
            path: "api/fs/list",
            locationDirectory: worktree,
            query: relativeDirectory.isEmpty ? [:] : ["path": relativeDirectory]
        )
        return envelope.data
    }

    /// v2 `GET api/fs/find?query=&type=file&limit=`.
    func findProjectFilesV2(worktree: String, query: String, limit: Int) async throws -> [V2FsEntryDTO] {
        let envelope: APILocatedEnvelope<[V2FsEntryDTO]> = try await get(
            path: "api/fs/find",
            locationDirectory: worktree,
            query: ["query": query, "type": "file", "limit": String(limit)]
        )
        return envelope.data
    }

    /// v2 `GET api/fs/read/<segments>`: raw bytes. `rangeEnd` sends `Range: bytes=0-<rangeEnd>`;
    /// older servers ignore it and answer 200 with the full body, so reading also stops past `limit`.
    func readProjectFileV2(
        worktree: String,
        relativePath: String,
        rangeEnd: Int?,
        limit: Int
    ) async throws -> BoundedHTTPBody {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        var request = connection.authorizedRequest(path: "api/fs/read", locationDirectory: worktree)
        if let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.percentEncodedPath += "/" + Self.encodedPathSegments(relativePath)
            request.url = components.url
        }
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        if let rangeEnd {
            request.setValue("bytes=0-\(rangeEnd)", forHTTPHeaderField: "Range")
        }
        return try await boundedData(for: request, limit: limit)
    }

    /// Each segment percent-encoded with only RFC 3986 unreserved characters left bare.
    static func encodedPathSegments(_ relativePath: String) -> String {
        let allowed = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        return relativePath
            .split(separator: "/", omittingEmptySubsequences: true)
            .map { String($0).addingPercentEncoding(withAllowedCharacters: allowed) ?? String($0) }
            .joined(separator: "/")
    }

    private static func appendingQuery(_ url: URL, _ query: [String: String]) -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        var items = components.queryItems ?? []
        query.sorted { $0.key < $1.key }.forEach { items.append(URLQueryItem(name: $0.key, value: $0.value)) }
        components.queryItems = items
        return components.url ?? url
    }

    /// Streams the body and stops after `limit + 1` bytes so callers can tell a cut body apart.
    private func boundedData(for request: URLRequest, limit: Int) async throws -> BoundedHTTPBody {
        let startedAt = OpenCodeAPITrace.start(request)
        let bytes: URLSession.AsyncBytes
        let response: URLResponse
        do {
            (bytes, response) = try await urlSession.bytes(for: request)
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            bytes.task.cancel()
            throw ProjectsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            bytes.task.cancel()
            throw ProjectsAPIError.httpStatus(http.statusCode, nil)
        }
        var data = Data()
        if http.expectedContentLength > 0 {
            data.reserveCapacity(Int(min(Int64(limit + 1), http.expectedContentLength)))
        }
        var chunk: [UInt8] = []
        chunk.reserveCapacity(64 * 1024)
        var exceeded = false
        do {
            for try await byte in bytes {
                chunk.append(byte)
                if chunk.count == 64 * 1024 {
                    data.append(contentsOf: chunk)
                    chunk.removeAll(keepingCapacity: true)
                }
                if data.count + chunk.count > limit {
                    exceeded = true
                    break
                }
            }
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        data.append(contentsOf: chunk)
        if exceeded { bytes.task.cancel() }
        return BoundedHTTPBody(
            data: data,
            statusCode: http.statusCode,
            contentType: http.value(forHTTPHeaderField: "Content-Type"),
            contentRange: http.value(forHTTPHeaderField: "Content-Range"),
            exceededLimit: exceeded
        )
    }

    private func get<T: Decodable>(
        path: String,
        directory: String? = nil,
        locationDirectory: String? = nil,
        query: [String: String] = [:]
    ) async throws -> T {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        var request = connection.authorizedRequest(
            path: path,
            directory: directory,
            locationDirectory: locationDirectory
        )
        if !query.isEmpty, let url = request.url,
           var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            query.forEach { name, value in
                items.append(URLQueryItem(name: name, value: value))
            }
            components.queryItems = items
            request.url = components.url
        }
        let data = try await data(for: request)
        return try decoder.decode(T.self, from: data)
    }

    private func data(for request: URLRequest) async throws -> Data {
        let startedAt = OpenCodeAPITrace.start(request)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.data(for: request)
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        guard let http = response as? HTTPURLResponse else {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw ProjectsAPIError.invalidResponse
        }
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            throw ProjectsAPIError.httpStatus(http.statusCode, message)
        }
        return data
    }
}

/// A response body read up to a byte limit.
nonisolated struct BoundedHTTPBody: Sendable {
    let data: Data
    let statusCode: Int
    let contentType: String?
    let contentRange: String?
    /// True when the server sent more than the limit; `data` then holds `limit + 1` bytes.
    let exceededLimit: Bool
}
