import Foundation
import Testing
@testable import OpenCode_Mobile

struct CommandInvocationCollapserTests {
    @Test func collapse_slashForm_formatsTokenAndArgs() {
        let commands = [ServerCommand(name: "prototype", source: .skill)]
        let display = CommandInvocationCollapser.collapse(
            text: "/prototype ship the home page",
            commands: commands
        )
        #expect(display?.commandName == "prototype")
        #expect(display?.arguments == "ship the home page")
        #expect(display?.text == "[/prototype] ship the home page")
        #expect(display?.highlightRange == 0 ..< "[/prototype]".utf16.count)
    }

    @Test func collapse_expandedTemplate_withTrailingArgs() {
        let template = """
        # Prototype

        Throwaway code that answers a question.

        Base directory for this skill: /skills/prototype
        """
        let commands = [
            ServerCommand(name: "prototype", source: .skill, template: template),
        ]
        let message = """
        \(template)

        me some examples of artifacts home page
        """
        let display = CommandInvocationCollapser.collapse(text: message, commands: commands)
        #expect(display?.text == "[/prototype] me some examples of artifacts home page")
        #expect(display?.arguments == "me some examples of artifacts home page")
    }

    @Test func collapse_expandedTemplate_withoutArgs() {
        let template = "# Restart\n\nForce restart the server."
        let commands = [
            ServerCommand(name: "restart-opencode-server", source: .skill, template: template),
        ]
        let display = CommandInvocationCollapser.collapse(text: template, commands: commands)
        #expect(display?.text == "[/restart-opencode-server]")
        #expect(display?.arguments == "")
    }

    @Test func collapse_unknownText_returnsNil() {
        let display = CommandInvocationCollapser.collapse(
            text: "hello world",
            commands: [ServerCommand(name: "prototype", template: "# Prototype")]
        )
        #expect(display == nil)
    }
}
