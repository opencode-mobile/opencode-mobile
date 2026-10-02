import Foundation
import Testing
@testable import OpenCode_Mobile

struct ModelDomainAdapterTests {
    private let adapter = ModelDomainAdapter()
    private let decoder = JSONDecoder()

    @Test func catalog_mapsProvidersModelsVariantsAndDefault() throws {
        let data =
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
            """.data(using: .utf8)!

        let catalog = adapter.catalog(from: try decoder.decode(ConfigProvidersResponseDTO.self, from: data))

        #expect(catalog.options.count == 1)
        #expect(catalog.options[0].providerID == "anthropic")
        #expect(catalog.options[0].modelID == "claude-sonnet")
        #expect(catalog.options[0].variants == ["low", "high"])
        #expect(catalog.serverDefault == catalog.options[0])
        #expect(catalog.options[0].supportsImageInput)
        #expect(catalog.options[0].supportsPdfInput)
    }

    @Test func catalog_v2KeepsServerVariantOrder() throws {
        let data =
            """
            {"id":"scripted","providerID":"sim","name":"Scripted model",
             "variants":[{"id":"low"},{"id":"medium"},{"id":"high"}]}
            """.data(using: .utf8)!
        let model = try decoder.decode(V2ModelInfoDTO.self, from: data)

        let catalog = adapter.catalog(providers: [], models: [model])

        #expect(catalog.options.first?.variants == ["low", "medium", "high"])
    }

    @Test func sortedByEffort_ordersKnownEffortsThenByName() {
        #expect(
            ModelVariants.sortedByEffort(["thinking", "high", "low", "fast", "xhigh", "medium", "minimal"])
                == ["minimal", "low", "medium", "high", "xhigh", "fast", "thinking"]
        )
    }

    @Test func isDefault_treatsServerDefaultAsNoVariantUnlessTheModelNamesIt() {
        #expect(ModelVariants.isDefault("default", available: ["low", "high"]))
        #expect(ModelVariants.isDefault(nil, available: ["low"]))
        #expect(ModelVariants.isDefault("", available: ["low"]))
        #expect(!ModelVariants.isDefault("default", available: ["default", "fast"]))
        #expect(!ModelVariants.isDefault("max", available: ["low"]))
    }

    @Test func catalog_mapsInputCapabilities() throws {
        let data =
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
            """.data(using: .utf8)!

        let catalog = adapter.catalog(from: try decoder.decode(ConfigProvidersResponseDTO.self, from: data))
        let textOnly = try #require(catalog.options.first { $0.modelID == "text-only" })
        let vision = try #require(catalog.options.first { $0.modelID == "vision" })

        #expect(textOnly.canAttach == false)
        #expect(textOnly.canAttachPhotos == false)
        #expect(vision.canAttach)
        #expect(vision.canAttachPhotos)
        #expect(vision.supportsPdfInput == false)
        #expect(vision.canAttachFiles)
    }
}
