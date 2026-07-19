package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.session.QuestionRequest
import kotlinx.coroutines.flow.Flow

interface QuestionLiveStore {
    fun observeQuestions(directory: String): Flow<List<QuestionRequest>>

    fun clearCache()
}
