import Foundation
import Observation

@MainActor
@Observable
final class ProjectListHomeViewModel {
    private(set) var state: SessionListHomeState = .loading
    private let sessionRepository: any SessionRepository
    private let adapter = SessionDomainAdapter()
    let selectedProjectName = "opencode-mobile"
    let serverBaseURL: String

    init(sessionRepository: any SessionRepository, serverBaseURL: String = AppConfig.serverBaseURL.absoluteString) {
        self.sessionRepository = sessionRepository
        self.serverBaseURL = serverBaseURL
    }

    func loadSessions() async {
        state = .loading
        do {
            let sessions = try await sessionRepository.listSessions().map(adapter.listItem)
            state = sessions.isEmpty ? .loaded([]) : .loaded(sessions)
        } catch let error as SessionError {
            state = .error(error.localizedDescription)
        } catch {
            state = .error(error.localizedDescription)
        }
    }
}
