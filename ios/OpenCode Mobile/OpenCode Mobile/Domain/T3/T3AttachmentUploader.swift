import Foundation

/// A user-facing reason an outbound attachment could not be sent to a T3 thread.
nonisolated enum T3AttachmentError: Error, Equatable, LocalizedError {
    case unsupported(filename: String)
    case oversize(filename: String)
    case unreadable(filename: String)
    case transfer(filename: String, detail: String)

    var errorDescription: String? {
        switch self {
        case let .unsupported(filename):
            "'\(filename)' is not a supported image. T3 threads accept PNG, JPEG, GIF, or WebP images only."
        case let .oversize(filename):
            "'\(filename)' exceeds the 10 MB image limit."
        case let .unreadable(filename):
            "Could not read '\(filename)'."
        case let .transfer(filename, detail):
            "Uploading '\(filename)' failed: \(detail)"
        }
    }
}

/// Wire shapes for the T3 attachment upload RPCs (`assets.ts` / `chatAttachment.ts`).
nonisolated enum T3AttachmentUpload {
    static let createUploadURLTag = "attachments.createUploadUrl"
    static let deleteTag = "attachments.delete"
    /// `PROVIDER_SEND_TURN_SUPPORTED_IMAGE_MIME_TYPES`.
    static let supportedImageMimes: Set<String> = ["image/gif", "image/jpeg", "image/png", "image/webp"]

    struct Target: Equatable, Sendable {
        let attachmentID: String
        let relativeURL: String
    }

    static func createUploadURLPayload(name: String, mimeType: String, sizeBytes: Int) -> [String: Any] {
        ["type": "image", "name": name, "mimeType": mimeType, "sizeBytes": sizeBytes]
    }

    static func deletePayload(attachmentID: String) -> [String: Any] {
        ["attachmentId": attachmentID]
    }

    /// `AttachmentCreateUploadUrlResult`; nil when either field is missing.
    static func target(from value: Any?) -> Target? {
        guard let object = T3JSON.object(value),
              let attachmentID = T3JSON.string(object, "attachmentId")?.trimmingCharacters(in: .whitespaces),
              !attachmentID.isEmpty,
              let relative = T3Assets.relativeURL(from: value)
        else { return nil }
        return Target(attachmentID: attachmentID, relativeURL: relative)
    }
}

/// The slice of `T3EnvironmentClient` an upload needs: one RPC round trip and
/// the environment origin plus bearer for the signed POST.
nonisolated protocol T3AttachmentUploadClient: Sendable {
    func rpcRequest(tag: String, payload: [String: Any]) async throws -> Any?
    func credentials() throws -> (baseURL: URL, bearer: String)
}

extension T3EnvironmentClient: T3AttachmentUploadClient {}

/// Moves attachment bytes to a signed T3 upload URL; returns the HTTP status code.
nonisolated protocol T3AttachmentByteTransport: Sendable {
    func upload(url: URL, data: Data, mimeType: String, bearer: String) async throws -> Int
}

nonisolated struct URLSessionT3AttachmentByteTransport: T3AttachmentByteTransport {
    private let urlSession: URLSession

    init(urlSession: URLSession = OpenCodeURLSessions.rest) {
        self.urlSession = urlSession
    }

    func upload(url: URL, data: Data, mimeType: String, bearer: String) async throws -> Int {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue(String(data.count), forHTTPHeaderField: "Content-Length")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 120
        let (_, response) = try await urlSession.upload(for: request, from: data)
        return (response as? HTTPURLResponse)?.statusCode ?? 0
    }
}

/// Outbound T3 attachment sequence: `attachments.createUploadUrl` over the RPC
/// socket, POST the exact bytes to the signed relative URL on the environment
/// origin, then reference the stored id as a `ChatAttachment` in
/// `message.dispatch`. T3 provider turns accept images only.
nonisolated struct T3AttachmentUploader: Sendable {
    private let client: any T3AttachmentUploadClient
    private let transport: any T3AttachmentByteTransport
    private let readBytes: @Sendable (Attachment) -> Data?

    init(
        client: any T3AttachmentUploadClient,
        transport: any T3AttachmentByteTransport = URLSessionT3AttachmentByteTransport(),
        readBytes: @escaping @Sendable (Attachment) -> Data? = T3AttachmentUploader.defaultBytes
    ) {
        self.client = client
        self.transport = transport
        self.readBytes = readBytes
    }

    func upload(_ attachments: [Attachment]) async throws -> [T3Attachment] {
        guard !attachments.isEmpty else { return [] }
        // Validate everything first so a bad second file never leaves the first one stored.
        let prepared = try attachments.map { ($0, try prepare($0)) }
        let credentials = try client.credentials()
        var uploaded: [T3Attachment] = []
        for (attachment, data) in prepared {
            do {
                uploaded.append(try await uploadOne(attachment, data: data, credentials: credentials))
            } catch {
                for stored in uploaded { await discard(stored.id) }
                throw error
            }
        }
        return uploaded
    }

    /// Validates one attachment against the T3 image contract and loads its bytes.
    private func prepare(_ attachment: Attachment) throws -> Data {
        let mime = attachment.mime.trimmingCharacters(in: .whitespaces).lowercased()
        guard attachment.kind == .image, T3AttachmentUpload.supportedImageMimes.contains(mime) else {
            throw T3AttachmentError.unsupported(filename: attachment.filename)
        }
        guard attachment.byteCount <= Int64(T3Assets.maxImageBytes) else {
            throw T3AttachmentError.oversize(filename: attachment.filename)
        }
        guard let data = readBytes(attachment), !data.isEmpty else {
            throw T3AttachmentError.unreadable(filename: attachment.filename)
        }
        guard data.count <= T3Assets.maxImageBytes else {
            throw T3AttachmentError.oversize(filename: attachment.filename)
        }
        return data
    }

    private func uploadOne(
        _ attachment: Attachment,
        data: Data,
        credentials: (baseURL: URL, bearer: String)
    ) async throws -> T3Attachment {
        let mime = attachment.mime.trimmingCharacters(in: .whitespaces).lowercased()
        let name = attachment.filename.isEmpty ? "image" : attachment.filename
        let reply = try await client.rpcRequest(
            tag: T3AttachmentUpload.createUploadURLTag,
            payload: T3AttachmentUpload.createUploadURLPayload(name: name, mimeType: mime, sizeBytes: data.count)
        )
        guard let target = T3AttachmentUpload.target(from: reply) else {
            throw T3ClientError.rpc("T3 did not return an upload URL.")
        }
        guard let url = T3Assets.resolve(baseURL: credentials.baseURL, relativePath: target.relativeURL) else {
            await discard(target.attachmentID)
            throw T3ClientError.decoding("T3 returned an invalid upload URL.")
        }
        let status: Int
        do {
            status = try await transport.upload(url: url, data: data, mimeType: mime, bearer: credentials.bearer)
        } catch {
            await discard(target.attachmentID)
            throw T3AttachmentError.transfer(filename: name, detail: error.localizedDescription)
        }
        guard (200 ..< 300).contains(status) else {
            await discard(target.attachmentID)
            if status == 401 || status == 403 {
                throw T3ClientError.accessRevoked(statusCode: status, body: nil)
            }
            throw T3AttachmentError.transfer(filename: name, detail: "server replied \(status).")
        }
        return T3Attachment(id: target.attachmentID, type: "image", name: name, mimeType: mime, sizeBytes: Int64(data.count))
    }

    /// Best-effort release of a pending upload the message will never reference.
    private func discard(_ attachmentID: String) async {
        _ = try? await client.rpcRequest(
            tag: T3AttachmentUpload.deleteTag,
            payload: T3AttachmentUpload.deletePayload(attachmentID: attachmentID)
        )
    }

    @Sendable
    static func defaultBytes(_ attachment: Attachment) -> Data? {
        if let fileURL = attachment.fileURL {
            return try? Data(contentsOf: fileURL)
        }
        return AttachmentDataURL.decodedData(from: attachment.dataURL, expectedMime: attachment.mime)
    }
}
