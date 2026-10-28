import Foundation

nonisolated struct FilesystemAPIClient: Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let resolveURLSession: @Sendable () -> URLSession
    private let decoder = JSONDecoder()

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession? = nil
    ) {
        self.connectionProvider = connectionProvider
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    init(fixedConnection: ServerConnection, urlSession: URLSession? = nil) {
        self.connectionProvider = { fixedConnection }
        self.resolveURLSession = OpenCodeURLSessions.restResolver(injected: urlSession)
    }

    func getPaths() async throws -> PathDTO {
        try await get(path: "path")
    }

    /// v1 `GET file?directory=<root>&path=<absolute dir>`. Using the path's root as the location
    /// keeps browsing from creating server projects or starting watchers, and stays inside the
    /// location on servers that refuse paths outside it.
    func listFiles(absoluteDirectory: String, root: String) async throws -> [FileNodeDTO] {
        try await get(path: "file", directory: root, query: ["path": absoluteDirectory])
    }

    func getLocationV2() async throws -> V2LocationInfoDTO {
        try await get(path: "api/location")
    }

    /// v2 `GET api/fs/list?location[directory]=<root>&path=<absolute dir>`. Entry paths come back
    /// relative to the envelope's `location.directory` (or absolute across Windows drives).
    func listFilesV2(absoluteDirectory: String, root: String) async throws -> APILocatedEnvelope<[V2FsEntryDTO]> {
        try await get(
            path: "api/fs/list",
            locationDirectory: root,
            query: ["path": absoluteDirectory]
        )
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

    /// v2 `GET api/fs/read/<path>` for a file the server holds, such as an uploaded attachment.
    /// The route is confined to its `location[directory]`, so an absolute path is read from its
    /// parent folder by file name (an upload under `paths.tmp` is outside the session directory);
    /// a relative path is read from `directory`.
    func readServerFileV2(path: String, directory: String?, limit: Int) async throws -> BoundedHTTPBody {
        guard let connection = connectionProvider() else {
            throw ProjectsAPIError.noActiveConnection
        }
        let (location, relative) = Self.readLocation(forServerPath: path, sessionDirectory: directory)
        var request = connection.authorizedRequest(path: "api/fs/read", locationDirectory: location)
        if let url = request.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            components.percentEncodedPath += "/" + Self.encodedPathSegments(relative)
            request.url = components.url
        }
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        return try await boundedData(for: request, limit: limit)
    }

    static func readLocation(forServerPath path: String, sessionDirectory: String?) -> (location: String?, relative: String) {
        let isAbsolute = path.hasPrefix("/") || path.hasPrefix("\\") || path.range(of: #"^[A-Za-z]:[\\/]"#, options: .regularExpression) != nil
        guard isAbsolute else { return (sessionDirectory, path) }
        guard let cut = path.lastIndex(where: { $0 == "/" || $0 == "\\" }) else { return (sessionDirectory, path) }
        var parent = String(path[..<cut])
        if parent.isEmpty { parent = String(path[cut]) }
        return (parent, String(path[path.index(after: cut)...]))
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

    /// Collects the body in the chunks URLSession delivers and stops after `limit + 1` bytes, so
    /// callers can tell a cut body apart. Cancelling the calling task cancels the request.
    private func boundedData(for request: URLRequest, limit: Int) async throws -> BoundedHTTPBody {
        let startedAt = OpenCodeAPITrace.start(request)
        let result: BoundedBodyCollector.Result
        do {
            result = try await BoundedBodyCollector.load(request, in: resolveURLSession(), limit: limit)
        } catch ProjectsAPIError.invalidResponse {
            OpenCodeAPITrace.invalidResponse(request, startedAt: startedAt)
            throw ProjectsAPIError.invalidResponse
        } catch {
            OpenCodeAPITrace.failure(request, error: error, startedAt: startedAt)
            throw error
        }
        let http = result.response
        OpenCodeAPITrace.finish(request, statusCode: http.statusCode, startedAt: startedAt)
        guard (200 ..< 300).contains(http.statusCode) else {
            throw ProjectsAPIError.httpStatus(http.statusCode, nil)
        }
        return BoundedHTTPBody(
            data: result.data,
            statusCode: http.statusCode,
            contentType: http.value(forHTTPHeaderField: "Content-Type"),
            contentRange: http.value(forHTTPHeaderField: "Content-Range"),
            expectedContentLength: http.expectedContentLength > 0 ? Int(http.expectedContentLength) : nil,
            exceededLimit: result.exceededLimit
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
            (data, response) = try await resolveURLSession().data(for: request)
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
    /// `Content-Length` when the server sent one.
    let expectedContentLength: Int?
    /// True when the server sent more than the limit; `data` then holds `limit + 1` bytes.
    let exceededLimit: Bool
}

/// Per-task URLSession delegate that appends `didReceive data` chunks and cancels the task once
/// more than `limit` bytes arrived. A non-2xx status is returned without reading the body.
nonisolated final class BoundedBodyCollector: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    struct Result: Sendable {
        let response: HTTPURLResponse
        let data: Data
        let exceededLimit: Bool
    }

    private let limit: Int
    private let lock = NSLock()
    private var task: URLSessionDataTask?
    private var continuation: CheckedContinuation<Result, Error>?
    private var response: HTTPURLResponse?
    private var data = Data()
    private var exceeded = false
    private var cancelled = false
    private var finished = false

    private init(limit: Int) {
        self.limit = limit
    }

    static func load(_ request: URLRequest, in session: URLSession, limit: Int) async throws -> Result {
        let collector = BoundedBodyCollector(limit: limit)
        let task = session.dataTask(with: request)
        task.delegate = collector
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                collector.start(task, continuation: continuation)
            }
        } onCancel: {
            collector.cancel()
        }
    }

    private func start(_ task: URLSessionDataTask, continuation: CheckedContinuation<Result, Error>) {
        lock.lock()
        if cancelled {
            finished = true
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return
        }
        self.task = task
        self.continuation = continuation
        lock.unlock()
        task.resume()
    }

    private func cancel() {
        lock.lock()
        cancelled = true
        let task = task
        lock.unlock()
        task?.cancel()
    }

    /// Resumes the caller once; later callbacks are ignored.
    private func finish(_ outcome: Swift.Result<Result, Error>) {
        lock.lock()
        guard !finished, let continuation else {
            lock.unlock()
            return
        }
        finished = true
        self.continuation = nil
        lock.unlock()
        continuation.resume(with: outcome)
    }

    func urlSession(
        _: URLSession,
        dataTask _: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            finish(.failure(ProjectsAPIError.invalidResponse))
            return
        }
        guard (200 ..< 300).contains(http.statusCode) else {
            completionHandler(.cancel)
            finish(.success(Result(response: http, data: Data(), exceededLimit: false)))
            return
        }
        lock.lock()
        self.response = http
        if http.expectedContentLength > 0 {
            data.reserveCapacity(Int(min(Int64(limit + 1), http.expectedContentLength)))
        }
        lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        guard !finished, !exceeded, let response else {
            lock.unlock()
            return
        }
        let room = limit + 1 - data.count
        if chunk.count < room {
            data.append(chunk)
            lock.unlock()
            return
        }
        data.append(chunk.prefix(room))
        exceeded = true
        let result = Result(response: response, data: data, exceededLimit: true)
        lock.unlock()
        dataTask.cancel()
        finish(.success(result))
    }

    func urlSession(_: URLSession, task _: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let response = response
        let data = data
        let wasCancelled = cancelled
        lock.unlock()
        if wasCancelled {
            finish(.failure(CancellationError()))
        } else if let error {
            finish(.failure(error))
        } else if let response {
            finish(.success(Result(response: response, data: data, exceededLimit: false)))
        } else {
            finish(.failure(ProjectsAPIError.invalidResponse))
        }
    }
}
