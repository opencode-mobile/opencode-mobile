package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.dto.ConfigProvidersResponseDto
import dev.opencode.mobile.data.remote.dto.V2ModelInfoDto
import dev.opencode.mobile.data.remote.dto.V2ModelVariantDto
import dev.opencode.mobile.data.remote.dto.V2ProviderInfoDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDomainAdapterTest {
    private val adapter = ModelDomainAdapter()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun toCatalog_mapsProvidersModelsVariantsAndDefault() {
        val raw =
            """
            {
              "providers": [
                {
                  "id": "anthropic",
                  "name": "Anthropic",
                  "models": {
                    "claude-sonnet": {
                      "id": "claude-sonnet",
                      "name": "Claude Sonnet",
                      "status": "active",
                      "variants": {
                        "low": {},
                        "high": {}
                      }
                    },
                    "old": {
                      "id": "old",
                      "name": "Old",
                      "status": "deprecated"
                    }
                  }
                }
              ],
              "default": {
                "anthropic": "claude-sonnet"
              }
            }
            """.trimIndent()

        val catalog = adapter.toCatalog(json.decodeFromString<ConfigProvidersResponseDto>(raw))

        assertEquals(1, catalog.options.size)
        assertEquals("anthropic", catalog.options.single().providerId)
        assertEquals("claude-sonnet", catalog.options.single().modelId)
        assertEquals(listOf("low", "high"), catalog.options.single().variants)
        assertEquals(catalog.options.single(), catalog.serverDefault)
        assertEquals(true, catalog.options.single().supportsImageInput)
        assertEquals(true, catalog.options.single().supportsPdfInput)
    }

    @Test
    fun toCatalog_v2KeepsServerVariantOrder() {
        val catalog =
            adapter.toCatalog(
                providers = listOf(V2ProviderInfoDto(id = "sim")),
                models =
                    listOf(
                        V2ModelInfoDto(
                            id = "scripted",
                            providerId = "sim",
                            variants = listOf("low", "medium", "high").map { V2ModelVariantDto(id = it) },
                        ),
                    ),
            )

        assertEquals(listOf("low", "medium", "high"), catalog.options.single().variants)
    }

    @Test
    fun sortedByEffort_ordersKnownEffortsThenByName() {
        assertEquals(
            listOf("minimal", "low", "medium", "high", "xhigh", "fast", "thinking"),
            ModelVariants.sortedByEffort(listOf("thinking", "high", "low", "fast", "xhigh", "medium", "minimal")),
        )
    }

    @Test
    fun isDefault_treatsServerDefaultAsNoVariantUnlessTheModelNamesIt() {
        assertEquals(true, ModelVariants.isDefault("default", listOf("low", "high")))
        assertEquals(true, ModelVariants.isDefault(null, listOf("low")))
        assertEquals(true, ModelVariants.isDefault("", listOf("low")))
        assertEquals(false, ModelVariants.isDefault("default", listOf("default", "fast")))
        assertEquals(false, ModelVariants.isDefault("max", listOf("low")))
    }

    @Test
    fun toCatalog_mapsInputCapabilities() {
        val raw =
            """
            {
              "providers": [
                {
                  "id": "openai",
                  "name": "OpenAI",
                  "models": {
                    "text-only": {
                      "id": "text-only",
                      "name": "Text Only",
                      "capabilities": {
                        "attachment": false,
                        "input": { "text": true, "image": false, "pdf": false }
                      }
                    },
                    "vision": {
                      "id": "vision",
                      "name": "Vision",
                      "capabilities": {
                        "attachment": true,
                        "input": { "text": true, "image": true, "pdf": false }
                      }
                    }
                  }
                }
              ],
              "default": {}
            }
            """.trimIndent()

        val catalog = adapter.toCatalog(json.decodeFromString<ConfigProvidersResponseDto>(raw))
        val textOnly = catalog.options.first { it.modelId == "text-only" }
        val vision = catalog.options.first { it.modelId == "vision" }

        assertEquals(false, textOnly.canAttach)
        assertEquals(false, textOnly.canAttachPhotos)
        assertEquals(true, vision.canAttach)
        assertEquals(true, vision.canAttachPhotos)
        assertEquals(false, vision.supportsPdfInput)
        assertEquals(true, vision.canAttachFiles)
    }
}
