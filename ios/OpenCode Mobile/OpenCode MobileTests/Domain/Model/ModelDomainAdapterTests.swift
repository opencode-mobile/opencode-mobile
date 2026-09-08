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
        #expect(catalog.options[0].variants == ["high", "low"])
        #expect(catalog.serverDefault == catalog.options[0])
        #expect(catalog.options[0].supportsImageInput)
        #expect(catalog.options[0].supportsPdfInput)
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
