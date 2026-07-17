package dev.opencode.mobile.domain.model

import dev.opencode.mobile.data.remote.dto.ConfigProvidersResponseDto
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
        assertEquals(listOf("high", "low"), catalog.options.single().variants)
        assertEquals(catalog.options.single(), catalog.serverDefault)
        assertEquals(true, catalog.options.single().supportsImageInput)
        assertEquals(true, catalog.options.single().supportsPdfInput)
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
