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
    }
}
