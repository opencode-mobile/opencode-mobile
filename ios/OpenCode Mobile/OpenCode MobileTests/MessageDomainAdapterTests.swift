import Foundation
import Testing
@testable import OpenCode_Mobile

struct MessageDomainAdapterTests {
    private let adapter = MessageDomainAdapter()
    private let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        return decoder
    }()

    @Test func mapsAssistantErrorWhenPartsEmpty() throws {
        let data =
            """
            {
              "info": {
                "id": "msg-3",
                "role": "assistant",
                "error": {
                  "name": "APIError",
                  "data": {
                    "message": "Forbidden: unauthorized: not licensed to use Copilot",
                    "statusCode": 403
                  }
                }
              },
              "parts": []
            }
            """.data(using: .utf8)!
        let dto = try decoder.decode(MessageDTO.self, from: data)
        let message = adapter.message(from: dto)
        #expect(message.text == "Forbidden: unauthorized: not licensed to use Copilot")
    }

    @Test func sendRequest_includesModelAndNonDefaultVariant() throws {
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                variant: "high"
            )
        )

        #expect(body.parts.map(\.text) == ["Hello"])
        #expect(body.model?.providerID == "anthropic")
        #expect(body.model?.modelID == "claude-sonnet")
        #expect(body.variant == "high")
    }

    @Test func sendRequest_omitsDefaultVariant() throws {
        let body = adapter.sendRequest(
            options: PromptSendOptions(
                text: "Hello",
                model: PromptModelSelection(providerID: "anthropic", modelID: "claude-sonnet"),
                variant: nil
            )
        )

        #expect(body.model?.providerID == "anthropic")
        #expect(body.variant == nil)
    }
}
