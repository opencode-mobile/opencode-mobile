import Foundation

/// Firebase project values read from the configured `FirebaseApp` options.
nonisolated struct FirebaseFeedbackConfiguration: Equatable, Sendable {
    static let bundleIdentifier = "dev.opencode.mobile"

    let apiKey: String
    let projectID: String
    let storageBucket: String
    var bundleIdentifier: String = Self.bundleIdentifier
}

/// Release report submitter (#721): App Check token from the SDK, everything
/// else over Firebase REST. Anonymous Auth (refresh, or sign up again), one
/// Storage upload per attachment, then one Firestore `documents:commit` that
/// creates the report and stamps the per-user rate limit.
///
/// Compiled in every configuration so the stub tests cover it; only the
/// release seam constructs it with a real App Check token provider.
nonisolated final class FirebaseFeedbackRESTSubmitter: FeedbackSubmitter {
    static let failedMessage = "Couldn't send the report."
    /// Refresh errors that mean the anonymous user is gone: sign up again.
    static let signUpAgainErrors = ["USER_NOT_FOUND", "USER_DISABLED", "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN"]

    private let configuration: FirebaseFeedbackConfiguration
    private let appCheckToken: @Sendable () async throws -> String
    private let tokenStore: any FeedbackRefreshTokenStore
    private let cooldownStore: any FeedbackCooldownStore
    private let now: @Sendable () -> Date
    private let session: URLSession

    init(
        configuration: FirebaseFeedbackConfiguration,
        appCheckToken: @escaping @Sendable () async throws -> String,
        tokenStore: any FeedbackRefreshTokenStore,
        cooldownStore: any FeedbackCooldownStore = UserDefaultsFeedbackCooldownStore(),
        now: @escaping @Sendable () -> Date = { Date() },
        session: URLSession = .shared
    ) {
        self.configuration = configuration
        self.appCheckToken = appCheckToken
        self.tokenStore = tokenStore
        self.cooldownStore = cooldownStore
        self.now = now
        self.session = session
    }

    func submit(_ submission: FeedbackSubmission) async -> FeedbackSubmitResult {
        let appCheck: String
        do {
            appCheck = try await appCheckToken()
        } catch {
            DiagnosticLog.warning(.connection, "feedback app check token failed", error: error)
            return .unverified
        }
        do {
            let user = try await authenticate(appCheck: appCheck)
            for attachment in submission.attachments {
                try await upload(attachment, reportID: submission.reportID, user: user, appCheck: appCheck)
            }
            try await commit(submission, user: user, appCheck: appCheck)
            return .sent(reference: submission.reference)
        } catch let failure as Failure {
            DiagnosticLog.warning(.connection, "feedback submit failed \(failure.logDescription)")
            return failure.result(
                cooldownRemaining: FeedbackCooldown.remainingSeconds(
                    lastSentAt: cooldownStore.lastSentAt(),
                    now: now()
                )
            )
        } catch {
            return .failed(message: Self.failedMessage)
        }
    }

    // MARK: - Auth

    private struct AuthUser {
        let uid: String
        let idToken: String
    }

    private func authenticate(appCheck: String) async throws -> AuthUser {
        if let refreshToken = tokenStore.read(), !refreshToken.isEmpty {
            do {
                return try await refresh(refreshToken, appCheck: appCheck)
            } catch let Failure.http(stage, status, body) where stage == .refresh && Self.requiresSignUp(status: status, body: body) {
                tokenStore.delete()
            }
        }
        return try await signUp(appCheck: appCheck)
    }

    private func refresh(_ refreshToken: String, appCheck: String) async throws -> AuthUser {
        var request = request(
            url: "https://securetoken.googleapis.com/v1/token?key=\(Self.queryEncoded(configuration.apiKey))",
            appCheck: appCheck
        )
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(
            "grant_type=refresh_token&refresh_token=\(Self.queryEncoded(refreshToken))".utf8
        )
        let body = try await send(request, stage: .refresh)
        guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let idToken = json["id_token"] as? String,
              let uid = json["user_id"] as? String
        else {
            throw Failure.malformed(.refresh)
        }
        if let rotated = json["refresh_token"] as? String, !rotated.isEmpty, rotated != refreshToken {
            tokenStore.write(rotated)
        }
        return AuthUser(uid: uid, idToken: idToken)
    }

    private func signUp(appCheck: String) async throws -> AuthUser {
        var request = request(
            url: "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=\(Self.queryEncoded(configuration.apiKey))",
            appCheck: appCheck
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(#"{"returnSecureToken":true}"#.utf8)
        let body = try await send(request, stage: .signUp)
        guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let idToken = json["idToken"] as? String,
              let uid = json["localId"] as? String,
              let refreshToken = json["refreshToken"] as? String
        else {
            throw Failure.malformed(.signUp)
        }
        tokenStore.write(refreshToken)
        return AuthUser(uid: uid, idToken: idToken)
    }

    static func requiresSignUp(status: Int, body: Data) -> Bool {
        guard status == 400 || status == 401 || status == 403 else { return false }
        guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let error = json["error"] as? [String: Any],
              let message = error["message"] as? String
        else { return false }
        return signUpAgainErrors.contains { message.hasPrefix($0) }
    }

    // MARK: - Storage

    private func upload(
        _ attachment: FeedbackAttachmentFile,
        reportID: String,
        user: AuthUser,
        appCheck: String
    ) async throws {
        let objectName = "feedback/\(user.uid)/\(reportID)/\(attachment.name)"
        var request = request(
            url: "https://firebasestorage.googleapis.com/v0/b/\(Self.pathEncoded(configuration.storageBucket))/o?name=\(Self.queryEncoded(objectName))",
            appCheck: appCheck
        )
        request.setValue("Firebase \(user.idToken)", forHTTPHeaderField: "Authorization")
        request.setValue(attachment.contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = attachment.data
        _ = try await send(request, stage: .upload)
    }

    // MARK: - Firestore

    private func commit(_ submission: FeedbackSubmission, user: AuthUser, appCheck: String) async throws {
        var request = request(
            url: "https://firestore.googleapis.com/v1/projects/\(Self.pathEncoded(configuration.projectID))/databases/(default)/documents:commit",
            appCheck: appCheck
        )
        request.setValue("Bearer \(user.idToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: Self.commitBody(
                submission,
                uid: user.uid,
                projectID: configuration.projectID
            ),
            options: [.sortedKeys]
        )
        _ = try await send(request, stage: .commit)
    }

    /// `documents:commit` body: the report (created only if absent, `createdAt`
    /// from the server clock) and the rate-limit stamp for this user.
    static func commitBody(_ submission: FeedbackSubmission, uid: String, projectID: String) -> [String: Any] {
        let documents = "projects/\(projectID)/databases/(default)/documents"
        return [
            "writes": [
                [
                    "update": [
                        "name": "\(documents)/feedback/\(submission.reportID)",
                        "fields": FeedbackPayloadBuilder.firestoreFields(submission, uid: uid),
                    ],
                    "updateTransforms": [
                        ["fieldPath": "createdAt", "setToServerValue": "REQUEST_TIME"],
                    ],
                    "currentDocument": ["exists": false],
                ] as [String: Any],
                [
                    "update": [
                        "name": "\(documents)/rateLimits/\(uid)",
                        "fields": ["lastReportId": ["stringValue": submission.reportID]],
                    ],
                    "updateMask": ["fieldPaths": ["lastReportId"]],
                    "updateTransforms": [
                        ["fieldPath": "lastReportAt", "setToServerValue": "REQUEST_TIME"],
                    ],
                ] as [String: Any],
            ],
        ]
    }

    // MARK: - Transport

    enum Stage: String, Sendable {
        case refresh
        case signUp
        case upload
        case commit
    }

    enum Failure: Error {
        case transport(Stage, URLError)
        case http(Stage, Int, Data)
        case malformed(Stage)

        /// Error mapping from the #721 contract, as measured with App Check
        /// enforced: a commit without a valid App Check token gets the same 403
        /// as the server-side rate limit, so a 403 only means rate-limited when
        /// the last successful report is under 60 s old.
        ///
        /// - Parameter cooldownRemaining: seconds left since the last success, or nil.
        func result(cooldownRemaining: Int?) -> FeedbackSubmitResult {
            switch self {
            case .transport:
                return .offline
            case .http(.upload, 401, _), .http(.upload, 403, _):
                return .unverified
            case .http(.commit, 401, _):
                return .unverified
            case .http(.commit, 403, _):
                if let cooldownRemaining {
                    return .rateLimited(retryAfterSeconds: cooldownRemaining)
                }
                return .unverified
            case .http, .malformed:
                return .failed(message: FirebaseFeedbackRESTSubmitter.failedMessage)
            }
        }

        var logDescription: String {
            switch self {
            case let .transport(stage, error):
                "stage=\(stage.rawValue) transport=\(error.code.rawValue)"
            case let .http(stage, status, _):
                "stage=\(stage.rawValue) status=\(status)"
            case let .malformed(stage):
                "stage=\(stage.rawValue) malformed"
            }
        }
    }

    private func request(url: String, appCheck: String) -> URLRequest {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.setValue(appCheck, forHTTPHeaderField: "X-Firebase-AppCheck")
        request.setValue(configuration.bundleIdentifier, forHTTPHeaderField: "X-Ios-Bundle-Identifier")
        return request
    }

    private func send(_ request: URLRequest, stage: Stage) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            throw Failure.transport(stage, error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw Failure.malformed(stage)
        }
        guard (200..<300).contains(http.statusCode) else {
            throw Failure.http(stage, http.statusCode, data)
        }
        return data
    }

    private static let unreserved = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )

    /// Percent-encodes everything but RFC 3986 unreserved characters (so `/` is `%2F`).
    static func queryEncoded(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }

    private static func pathEncoded(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }
}
