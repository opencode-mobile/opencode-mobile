import Foundation

final class T3EnvironmentClient: @unchecked Sendable {
    private let connectionProvider: @Sendable () -> ServerConnection?
    private let http: T3HttpClient
    private let lock = NSLock()
    private var cachedShell: T3ShellSnapshot?
    private var rpcSession: T3RpcSession?
    private var rpcIdentity: String?
    private var connectTask: Task<T3RpcSession, Error>?
    private var connectIdentity: String?

    init(
        connectionProvider: @escaping @Sendable () -> ServerConnection?,
        urlSession: URLSession = OpenCodeURLSessions.rest
    ) {
        self.connectionProvider = connectionProvider
        self.http = T3HttpClient(urlSession: urlSession)
    }

    func credentials() throws -> (baseURL: URL, bearer: String) {
        guard let connection = connectionProvider() else {
            throw T3ClientError.notConnected
        }
        guard let bearer = connection.password, !bearer.isEmpty else {
            throw T3ClientError.notConnected
        }
        return (connection.baseURL, bearer)
    }

    func fetchShell(force: Bool = false) async throws -> T3ShellSnapshot {
        if !force, let cached = locked({ cachedShell }) {
            return cached
        }
        let credentials = try credentials()
        let snapshot = try await http.fetchShell(baseURL: credentials.baseURL, bearer: credentials.bearer)
        locked { cachedShell = snapshot }
        return snapshot
    }

    func replaceShell(_ snapshot: T3ShellSnapshot) {
        locked { cachedShell = snapshot }
    }

    func cachedThread(id: String) -> T3Thread? {
        locked { cachedShell?.thread(id: id) }
    }

    func fetchThread(id: String) async throws -> T3ThreadDetail {
        let credentials = try credentials()
        let detail = try await http.fetchThread(baseURL: credentials.baseURL, bearer: credentials.bearer, id: id)
        if let thread = detail.thread {
            locked {
                if var shell = cachedShell {
                    if let index = shell.threads.firstIndex(where: { $0.id == thread.id }) {
                        shell.threads[index] = thread
                    } else {
                        shell.threads.append(thread)
                    }
                    cachedShell = shell
                }
            }
        }
        return detail
    }

    func fetchImageBytes(id: String, fileName: String, mimeType: String) async -> Data? {
        guard mimeType.lowercased().hasPrefix("image/") else { return nil }
        do {
            let value = try await rpc().request(
                tag: T3Assets.createURLTag,
                payload: T3Assets.createURLPayload(id: id, fileName: fileName, mimeType: mimeType)
            )
            guard let relative = T3Assets.relativeURL(from: value) else { return nil }
            let credentials = try credentials()
            guard let url = T3Assets.resolve(baseURL: credentials.baseURL, relativePath: relative) else { return nil }
            return await http.fetchBytes(url: url, maxBytes: T3Assets.maxImageBytes)
        } catch {
            return nil
        }
    }

    func dispatchCommand(_ command: [String: Any]) async throws {
        _ = try await rpc().request(tag: "orchestration.dispatchCommand", payload: command)
    }

    func launchThread(title: String?) async throws -> Session {
        let shell = try await fetchShell(force: locked { cachedShell == nil })
        guard let project = shell.projects.first else {
            throw T3ClientError.decoding("This T3 environment has no projects yet.")
        }
        let selection =
            project.defaultModelSelection
            ?? shell.threads.compactMap(\.modelSelection).first
        guard let selection else {
            throw T3ClientError.decoding("This T3 environment has no model selection yet.")
        }
        let resolvedTitle = {
            let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return trimmed.isEmpty ? "New thread" : trimmed
        }()
        let payload = T3RpcCodec.launchThreadPayload(
            commandID: UUID().uuidString,
            projectID: project.id,
            title: resolvedTitle,
            selection: selection
        )
        let value = try await rpc().request(tag: "orchestration.launchThread", payload: payload)
        if let object = T3JSON.object(value) {
            let projection = object["projection"] ?? object
            if let thread = T3ShellMapper.thread(from: T3JSON.object(projection)?["thread"] as Any)
                ?? T3ShellMapper.thread(from: object["thread"] as Any)
            {
                return T3ShellMapper.session(from: thread)
            }
            if let threadID = T3JSON.string(object, "threadId") {
                let snapshot = try await fetchShell(force: true)
                if let thread = snapshot.thread(id: threadID) {
                    return T3ShellMapper.session(from: thread)
                }
                return Session(
                    id: threadID,
                    title: resolvedTitle,
                    slug: nil,
                    projectID: project.id,
                    directory: project.workspaceRoot,
                    parentID: nil,
                    updatedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1_000),
                    providerInstanceID: selection.instanceID,
                    runtimeMode: "approval-required",
                    modelName: selection.model
                )
            }
        }
        throw T3ClientError.decoding("T3 did not return the new thread.")
    }

    func subscribeThread(id: String, afterSequence: Int) -> AsyncStream<Any> {
        AsyncStream { continuation in
            let task = Task {
                do {
                    let session = try await rpc()
                    let payload: [String: Any] = [
                        "threadId": id,
                        "afterSequence": afterSequence,
                        "requestCompletionMarker": true,
                    ]
                    for await value in session.subscribe(tag: "orchestration.subscribeThread", payload: payload) {
                        continuation.yield(value)
                    }
                } catch {
                    continuation.finish()
                    return
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func reset() async {
        let session = locked { () -> T3RpcSession? in
            cachedShell = nil
            let session = rpcSession
            rpcSession = nil
            rpcIdentity = nil
            connectIdentity = nil
            connectTask?.cancel()
            connectTask = nil
            return session
        }
        await session?.close()
    }

    private func rpc() async throws -> T3RpcSession {
        let credentials = try credentials()
        let identity = "\(credentials.baseURL.absoluteString)|\(credentials.bearer)"
        if let existing = locked({ rpcIdentity == identity ? rpcSession : nil }) {
            return existing
        }
        let http = self.http
        let baseURL = credentials.baseURL
        let bearer = credentials.bearer
        let created = Task { @concurrent in
            try Task.checkCancellation()
            let ticket = try await http.issueWebsocketTicket(baseURL: baseURL, bearer: bearer)
            guard let url = T3RpcCodec.websocketURL(httpBase: baseURL, ticket: ticket) else {
                throw T3ClientError.decoding("Could not build the T3 websocket URL.")
            }
            let session = T3RpcSession()
            do {
                try await session.connect(url: url)
                try Task.checkCancellation()
                return session
            } catch {
                await session.close()
                throw error
            }
        }
        let taskToAwait: Task<T3RpcSession, Error> = locked {
            if let session = rpcSession, rpcIdentity == identity {
                created.cancel()
                return Task { session }
            }
            if let connectTask, connectIdentity == identity {
                created.cancel()
                return connectTask
            }
            connectIdentity = identity
            connectTask = created
            return created
        }
        do {
            let session = try await taskToAwait.value
            let keep = locked { () -> Bool in
                guard connectIdentity == identity else { return false }
                rpcSession = session
                rpcIdentity = identity
                connectTask = nil
                return true
            }
            if !keep {
                await session.close()
            }
            return session
        } catch {
            locked {
                if connectIdentity == identity {
                    connectTask = nil
                    connectIdentity = nil
                }
            }
            throw error
        }
    }

    @discardableResult
    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
