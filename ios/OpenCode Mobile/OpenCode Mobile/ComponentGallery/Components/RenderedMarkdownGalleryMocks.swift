import Foundation

enum RenderedMarkdownGalleryMocks {
    struct Sample: Identifiable {
        let id: String
        let label: String
        let variant: OpenCodeRenderedMarkdownVariant
        let document: NativeMarkdownDocument
    }

    private static let assistantMarkdown = """
    ### Plan

    - Keep the chat native.
    - Use the renderer only for formatted text.

    ```swift
    let renderer = "native SwiftUI"
    ```
    """

    private static let outgoingMarkdown = """
    Please compare `main` against my branch and summarize:

    | Area | Need |
    | --- | --- |
    | Tests | focused |
    | Risk | low |
    """

    private static let diffMarkdown = """
    ```diff
    @@ SessionChatView.swift
    -Text(message.text)
    +OpenCodeRenderedMarkdown(document: message.renderedMarkdown)
    ```
    """

    static let samples: [Sample] = [
        Sample(
            id: "assistant",
            label: "Assistant",
            variant: .assistantMessage,
            document: OpenCodeRenderedMarkdownParser.parse(assistantMarkdown)
        ),
        Sample(
            id: "outgoing",
            label: "Outgoing",
            variant: .outgoingMessage,
            document: OpenCodeRenderedMarkdownParser.parse(outgoingMarkdown)
        ),
        Sample(
            id: "diff",
            label: "Diff",
            variant: .standalone,
            document: OpenCodeRenderedMarkdownParser.parse(diffMarkdown)
        ),
    ]
}
