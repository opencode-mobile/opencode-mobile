import Foundation

/// Read-only File Browser access to one project worktree. Paths are relative to the worktree.
protocol ProjectFilesRepository: Sendable {
    /// Children of `relativeDirectory` (empty for the worktree root), unsorted and unfiltered.
    func listEntries(worktree: String, relativeDirectory: String) async throws -> [ProjectEntry]
    /// Server fuzzy find, files only.
    func findFiles(worktree: String, query: String, limit: Int) async throws -> [ProjectEntry]
    func readFile(worktree: String, relativePath: String) async throws -> ProjectFileContent
}

/// Routes v1 (`file`, `file/content`, `find/file`) and v2 (`api/fs/*`) behind one product API.
/// The worktree stays the directory/location on every call so v1 keeps one server instance.
nonisolated struct DefaultProjectFilesRepository: ProjectFilesRepository, Sendable {
    private let api: FilesystemAPIClient
    private let adapter: FilesystemDomainAdapter
    private let protocolProvider: @Sendable () async -> ServerProtocol

    init(
        api: FilesystemAPIClient,
        adapter: FilesystemDomainAdapter,
        activeConnectionManager: ActiveConnectionManager?
    ) {
        self.api = api
        self.adapter = adapter
        self.protocolProvider = {
            await activeConnectionManager?.currentProtocolOrProbe() ?? .v1
        }
    }

    func listEntries(worktree: String, relativeDirectory: String) async throws -> [ProjectEntry] {
        let directory = ProjectPaths.normalize(relativeDirectory)
        return try await mapErrors {
            if await protocolProvider() == .v2 {
                return try await api.listProjectEntriesV2(worktree: worktree, relativeDirectory: directory)
                    .map { adapter.projectEntry(from: $0, listingDirectory: directory) }
            }
            return try await api.listProjectEntries(worktree: worktree, relativeDirectory: directory)
                .map { adapter.projectEntry(from: $0, listingDirectory: directory) }
        }
    }

    func findFiles(worktree: String, query: String, limit: Int) async throws -> [ProjectEntry] {
        try await mapErrors {
            if await protocolProvider() == .v2 {
                return try await api.findProjectFilesV2(worktree: worktree, query: query, limit: limit)
                    .filter { $0.type != "directory" && !$0.path.hasSuffix("/") }
                    .map { adapter.projectEntry(fromFoundPath: $0.path) }
            }
            return try await api.findProjectFiles(worktree: worktree, query: query, limit: limit)
                .filter { !$0.hasSuffix("/") }
                .map { adapter.projectEntry(fromFoundPath: $0) }
        }
    }

    func readFile(worktree: String, relativePath: String) async throws -> ProjectFileContent {
        let path = ProjectPaths.normalize(relativePath)
        let name = ProjectPaths.name(of: path)
        let api = api
        let protocolProvider = protocolProvider
        // Streaming and decoding stay off the main actor.
        return try await Task.detached(priority: .userInitiated) {
            try await Self.mapErrors {
                if await protocolProvider() == .v2 {
                    return try await Self.readV2(api: api, worktree: worktree, path: path, name: name)
                }
                let body = try await api.readProjectFile(
                    worktree: worktree,
                    relativePath: path,
                    limit: ProjectFileLimits.maxV1ResponseBytes
                )
                if body.exceededLimit { return .binary(mimeType: nil, byteCount: nil) }
                let dto = try JSONDecoder().decode(FileContentDTO.self, from: body.data)
                return ProjectFileContentDecoder.content(fromV1: dto, fileName: name)
            }
        }.value
    }

    private static func readV2(
        api: FilesystemAPIClient,
        worktree: String,
        path: String,
        name: String
    ) async throws -> ProjectFileContent {
        if ProjectPaths.isImage(name: name) {
            let body = try await api.readProjectFileV2(
                worktree: worktree,
                relativePath: path,
                rangeEnd: nil,
                limit: ProjectFileLimits.maxImageBytes
            )
            return ProjectFileContentDecoder.image(
                fromBytes: body.data,
                exceededLimit: body.exceededLimit,
                mimeType: ProjectFileContentDecoder.normalizedMimeType(body.contentType),
                fileName: name
            )
        }
        let limit = ProjectFileLimits.maxTextBytes
        let body = try await api.readProjectFileV2(
            worktree: worktree,
            relativePath: path,
            rangeEnd: limit - 1,
            limit: limit
        )
        let isTruncated: Bool
        let totalBytes: Int?
        if body.statusCode == 206 {
            totalBytes = ProjectFileContentDecoder.totalBytes(fromContentRange: body.contentRange)
            isTruncated = body.exceededLimit || (totalBytes.map { $0 > body.data.count } ?? false)
        } else {
            isTruncated = body.exceededLimit
            totalBytes = body.exceededLimit ? nil : body.data.count
        }
        return ProjectFileContentDecoder.content(
            fromBytes: body.data,
            isTruncated: isTruncated,
            totalBytes: totalBytes,
            mimeType: ProjectFileContentDecoder.normalizedMimeType(body.contentType)
        )
    }

    private func mapErrors<T>(_ operation: () async throws -> T) async throws -> T {
        try await Self.mapErrors(operation)
    }

    private static func mapErrors<T>(_ operation: () async throws -> T) async throws -> T {
        do {
            return try await operation()
        } catch let error as ProjectsAPIError {
            switch error {
            case .noActiveConnection: throw ProjectFilesError.noConnection
            case .invalidResponse: throw ProjectFilesError.invalidResponse
            case let .httpStatus(code, _):
                throw code == 404 ? ProjectFilesError.notFound : ProjectFilesError.server(statusCode: code)
            }
        } catch is DecodingError {
            throw ProjectFilesError.invalidResponse
        } catch let error as ProjectFilesError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            throw ProjectFilesError.transport
        }
    }
}
