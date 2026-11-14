package dev.opencode.mobile.domain.model

interface ModelRepository {
    suspend fun listModelOptions(directory: String? = null): ModelSelectionCatalog
}
