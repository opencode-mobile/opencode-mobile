package dev.opencode.mobile.domain.t3

/**
 * Resolves the model selection for a new thread the way the T3 web client does:
 * the project's override, then the global settings default, then the first usable
 * harness (a `ready` one before any other usable one). A stored selection whose
 * harness is usable is kept as is; falling back to another harness takes that
 * harness's own default model so instance and model never cross providers.
 */
object T3ModelSelectionResolver {
    const val NO_HARNESS_MESSAGE = "No T3 harness is available. Configure a provider in T3 Code first."

    fun resolve(
        harnesses: List<T3Harness>,
        projectDefault: T3ModelSelection?,
        globalDefault: T3ModelSelection?,
    ): T3ModelSelection? {
        listOfNotNull(projectDefault, globalDefault).forEach { requested ->
            val harness = harnesses.firstOrNull { it.instanceId == requested.instanceId && it.isUsable } ?: return@forEach
            val model = requested.model?.takeIf { it.isNotBlank() } ?: harness.defaultModel?.slug ?: return@forEach
            return T3ModelSelection(instanceId = harness.instanceId, model = model)
        }
        val fallback =
            harnesses.firstOrNull { it.isUsable && it.status == "ready" }
                ?: harnesses.firstOrNull { it.isUsable && it.status != "error" }
                ?: return null
        val model = fallback.defaultModel ?: return null
        return T3ModelSelection(instanceId = fallback.instanceId, model = model.slug)
    }

    /**
     * An explicit picker choice when its harness is still usable (its model, or the
     * harness default when the model is gone), else the [resolve] chain.
     */
    fun resolveExplicit(
        harnesses: List<T3Harness>,
        explicit: T3ModelSelection?,
        projectDefault: T3ModelSelection?,
        globalDefault: T3ModelSelection?,
    ): T3ModelSelection? {
        if (explicit != null) {
            val harness = harnesses.firstOrNull { it.instanceId == explicit.instanceId && it.isUsable }
            if (harness != null) {
                val model = harness.model(explicit.model)?.slug ?: harness.defaultModel?.slug
                if (model != null) return T3ModelSelection(instanceId = harness.instanceId, model = model)
            }
        }
        return resolve(harnesses, projectDefault, globalDefault)
    }
}
