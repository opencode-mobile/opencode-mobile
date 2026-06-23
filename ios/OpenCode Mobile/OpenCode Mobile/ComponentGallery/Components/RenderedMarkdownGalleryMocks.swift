import Foundation

enum RenderedMarkdownGalleryMocks {
    struct Sample: Identifiable {
        let id: String
        let label: String
        let variant: OpenCodeRenderedMarkdownVariant
        let rawMarkdown: String
        let document: NativeMarkdownDocument
        let usesOutgoingMessage: Bool
    }

    private static let assistantMarkdown = """
    ### Release checklist

    The session is ready for review. Start with the **native render tree**,
    then verify the [session status](https://example.com/session-status).

    ```swift
    let renderer = NativeMarkdownRenderer(platform: .iOS)
    renderer.render(document)
    ```

    - Preserve raw markdown for copy and restore.
    - Keep `http` and `https` links native-owned.
    """

    private static let outgoingMarkdown = """
    Please review the markdown migration before merging. The response should
    call out any regression in `SessionChat`, confirm that `diff` blocks keep
    horizontal scrolling, and include the exact test command to rerun after
    resolving comments. This intentionally long formatted paragraph verifies
    that an outgoing inline-markdown message remains fully readable.
    """

    private static let codeMarkdown = """
    ```typescript
    export function renderSessionMarkdown(source: string): RenderTree {
      return parseMarkdown(source, { highlight: "textmate", allowHtml: false })
    }
    ```
    """

    private static let languagesMarkdown = """
    ### Syntax language sweep

    ```kotlin
    data class Session(val id: String, val active: Boolean)
    ```

    ```swift
    let status = sessions.first(where: \\.isActive)
    ```

    ```typescript
    const retry = async <T>(work: () => Promise<T>): Promise<T> => work()
    ```

    ```python
    def summarize(messages: list[str]) -> str:
        return "\\n".join(messages)
    ```

    ```json
    { "renderer": "native", "network": false }
    ```

    ```bash
    ./gradlew :app:assembleDebug
    ```

    ```yaml
    markdown:
      renderer: native
      streaming: debounce
    ```

    ```java
    public class Main {
        public static void main(String[] args) {
            System.out.println("hello");
        }
    }
    ```

    ```csharp
    public sealed class SessionService {
        public string Label(string id) => id;
    }
    ```

    ```sql
    SELECT id, label FROM sessions WHERE active = TRUE;
    ```

    ```toml
    [markdown]
    renderer = "native"
    ```

    ```makefile
    .PHONY: build test
    build:
    \t@npm run build
    test:
    \t@npm test
    ```
    """

    private static let richMarkdown = """
    ## Review decision

    > Ship the native renderer only when the message stays readable on a
    > narrow screen and links remain platform-owned.

    1. Verify **assistant** and _outgoing_ variants.
    2. Check `diff` colors in light and dark themes.
    3. Open the [release checklist](https://example.com/release-checklist).

    ![Mobile session screenshot](https://example.com/session.png)

    ---

    The image is represented as safe alt text; no remote content is loaded.
    """

    private static let tableMarkdown = """
    ### Verification matrix

    | Surface | Expected result | Owner |
    | --- | --- | --- |
    | Session Chat | Native text and code blocks | Mobile |
    | Streaming | No forced scroll after user scrolls up | Mobile |
    | Links | Only HTTPS leaves the app | Platform |
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
            rawMarkdown: assistantMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(assistantMarkdown),
            usesOutgoingMessage: false
        ),
        Sample(
            id: "request",
            label: "Request",
            variant: .outgoingMessage,
            rawMarkdown: outgoingMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(outgoingMarkdown),
            usesOutgoingMessage: true
        ),
        Sample(
            id: "code",
            label: "Code",
            variant: .assistantMessage,
            rawMarkdown: codeMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(codeMarkdown),
            usesOutgoingMessage: false
        ),
        Sample(
            id: "languages",
            label: "Languages",
            variant: .standalone,
            rawMarkdown: languagesMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(languagesMarkdown),
            usesOutgoingMessage: false
        ),
        Sample(
            id: "rich",
            label: "Rich",
            variant: .assistantMessage,
            rawMarkdown: richMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(richMarkdown),
            usesOutgoingMessage: false
        ),
        Sample(
            id: "table",
            label: "Table",
            variant: .assistantMessage,
            rawMarkdown: tableMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(tableMarkdown),
            usesOutgoingMessage: false
        ),
        Sample(
            id: "diff",
            label: "Diff",
            variant: .standalone,
            rawMarkdown: diffMarkdown,
            document: OpenCodeRenderedMarkdownParser.parse(diffMarkdown),
            usesOutgoingMessage: false
        ),
    ]
}
