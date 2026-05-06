package dev.opencode.mobile.data

import dev.opencode.mobile.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface DataRepository {
  val sessions: Flow<List<SessionSummary>>
  val serverBaseUrl: String
  val selectedProjectName: String
}

class DefaultDataRepository : DataRepository {
  override val serverBaseUrl: String = AppConfig.SERVER_BASE_URL
  override val selectedProjectName: String = "opencode-mobile"

  override val sessions: Flow<List<SessionSummary>> =
    flow {
      emit(
        listOf(
          SessionSummary(id = "1", title = "Importing icons explanation"),
          SessionSummary(id = "2", title = "Material Design Icons integration plan"),
          SessionSummary(id = "3", title = "iOS color palette and typography screens"),
          SessionSummary(id = "4", title = "Organize notes and docs; update agents.md"),
        )
      )
    }
}

data class SessionSummary(val id: String, val title: String)
