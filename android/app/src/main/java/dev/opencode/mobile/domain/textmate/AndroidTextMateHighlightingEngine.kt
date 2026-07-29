package dev.opencode.mobile.domain.textmate

import dev.opencode.mobile.markdown.HighlightRequest
import dev.opencode.mobile.markdown.HighlightResult
import dev.opencode.mobile.markdown.MemoryPressure
import dev.opencode.mobile.markdown.TextMateHighlightingEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android's asynchronous platform-to-KMP adapter. The dispatcher keeps TextMate
 * off the UI executor and the mutex gives the injected KMP engine one owner even
 * when callers race during first use.
 */
class AndroidTextMateHighlightingEngine(
    private val delegateFactory: () -> TextMateHighlightingEngine,
    private val ownerDispatcher: CoroutineDispatcher,
) : TextMateHighlightingEngine {
    private val owner = Mutex()
    private var delegate: TextMateHighlightingEngine? = null

    constructor(
        delegate: TextMateHighlightingEngine,
        ownerDispatcher: CoroutineDispatcher,
    ) : this(
        delegateFactory = { delegate },
        ownerDispatcher = ownerDispatcher,
    )

    override suspend fun highlight(request: HighlightRequest): HighlightResult =
        withContext(ownerDispatcher) {
            owner.withLock {
                ownedDelegate().highlight(request)
            }
        }

    override suspend fun trim(level: MemoryPressure) {
        withContext(ownerDispatcher) {
            owner.withLock {
                ownedDelegate().trim(level)
            }
        }
    }

    private fun ownedDelegate(): TextMateHighlightingEngine = delegate ?: delegateFactory().also { created -> delegate = created }
}
