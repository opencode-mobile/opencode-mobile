import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct EventDomainAdapterTests {
    @Test func isServerConnectedFrame_readsV1WrappedAndV2Shapes() {
        let adapter = EventDomainAdapter()
        #expect(
            adapter.isServerConnectedFrame(
                #"{"payload":{"type":"server.connected","properties":{}}}"#
            )
        )
        #expect(
            adapter.isServerConnectedFrame(
                #"{"directory":"/tmp","payload":{"type":"server.connected","properties":{}}}"#
            )
        )
        #expect(adapter.isServerConnectedFrame(#"{"type":"server.connected","data":{}}"#))
        #expect(
            adapter.isServerConnectedFrame(
                #"{"payload":{"type":"server.heartbeat","properties":{}}}"#
            ) == false
        )
    }

    @Test func parseEvent_readsWrappedPayload() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"payload":{"type":"message.updated","properties":{"sessionID":"ses-1"}}}"#)
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsEnvelopeIdFromPayloadAndRaw() {
        let adapter = EventDomainAdapter()
        let wrapped = adapter.parseEvent(
            dataLine: #"{"directory":"/tmp","payload":{"id":"evt_1","type":"message.part.delta","properties":{"sessionID":"ses-1"}}}"#
        )
        #expect(wrapped?.id == "evt_1")
        let instance = adapter.parseEvent(
            dataLine: #"{"id":"evt_2","type":"message.part.delta","properties":{"sessionID":"ses-1"}}"#
        )
        #expect(instance?.id == "evt_2")
    }

    @Test func parseEvent_readsSessionIDFromMessageInfo() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"message.updated","properties":{"info":{"sessionID":"ses-1","id":"msg-1","role":"user"}}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsSessionIDFromMessagePart() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"message.part.updated","properties":{"part":{"sessionID":"ses-1","id":"prt-1","type":"text"}}}}"#
        )
        #expect(event?.type == "message.part.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsMessagePartDelta() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"message.part.delta","properties":{"sessionID":"ses-1","messageID":"msg-1","partID":"prt-1","field":"text","delta":"Hi"}}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvent_readsServerConnected() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"server.connected","properties":{}}}"#
        )
        #expect(event?.type == "server.connected")
        #expect(event?.sessionID == nil)
    }

    @Test func parseEvent_readsDirectoryWrappedEnvelope() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"directory":"/repo","payload":{"type":"message.updated","properties":{"info":{"sessionID":"ses-1"}}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsV2NativeEnvelope() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(dataLine: #"{"id":"evt_1","type":"server.connected","data":{}}"#)
        #expect(event?.type == "server.connected")
        #expect(event?.sessionID == nil)
    }

    @Test func parseEvent_projectsV2TextDeltaIntoMessagePartDelta() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"id":"evt_2","type":"session.next.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","textID":"prt-1","delta":"Hi"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["messageID"]?.stringValue == "msg-2")
        #expect(event?.rawProperties?["partID"]?.stringValue == "prt-1")
        #expect(event?.rawProperties?["field"]?.stringValue == "text")
        #expect(event?.rawProperties?["delta"]?.stringValue == "Hi")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvents_projectsStepStartedIntoAssistantMessageAndBusyStatus() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "session.status"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "assistant")
        #expect(events[1].rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue == "busy")
    }

    @Test func parseEvent_projectsPromptAdmittedIntoUserMessageUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.next.prompt.admitted","data":{"sessionID":"ses-1","messageID":"msg-1"}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "user")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["id"]?.stringValue == "msg-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["waiting"]?.boolValue == true)
    }

    @Test func parseEvents_projectsPromptedIntoUserMessageAndTextPart() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "user")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["waiting"]?.boolValue == false)
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["delivery"]?.stringValue == "steer")
        let part = events[1].rawProperties?["part"]?.dictionaryValue
        #expect(part?["type"]?.stringValue == "text")
        #expect(part?["text"]?.stringValue == "Hello from CLI")
        #expect(part?["messageID"]?.stringValue == "msg-1")
        #expect(part?["id"]?.stringValue == "msg-1:text:0")
    }

    @Test func parseEvents_projectsVersionedPromptedType() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.prompted.1","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from CLI")
    }

    @Test func parseEvents_projectsPromptedFilesIntoFileParts() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"See this","files":[{"uri":"data:image/png;base64,AA==","mime":"image/png","name":"a.png"}]},"delivery":"steer"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated", "message.part.updated"])
        let file = events[2].rawProperties?["part"]?.dictionaryValue
        #expect(file?["type"]?.stringValue == "file")
        #expect(file?["url"]?.stringValue == "data:image/png;base64,AA==")
        #expect(file?["mime"]?.stringValue == "image/png")
        #expect(file?["filename"]?.stringValue == "a.png")
    }

    @Test func parseEvents_projectsPromptedFilesWithoutMimeFromDataURL() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"What","files":[{"uri":"data:image/jpeg;base64,AA==","name":"Photo 1.jpeg"}]},"delivery":"steer"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated", "message.part.updated"])
        let file = events[2].rawProperties?["part"]?.dictionaryValue
        #expect(file?["type"]?.stringValue == "file")
        #expect(file?["url"]?.stringValue == "data:image/jpeg;base64,AA==")
        #expect(file?["mime"]?.stringValue == "image/jpeg")
        #expect(file?["filename"]?.stringValue == "Photo 1.jpeg")
    }

    @Test func parseEvents_projectsPromptedInlineFileDataAsDataURL() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"What","files":[{"data":"AA==","mime":"image/jpeg","source":{"type":"inline"},"name":"Photo 1.png"}]},"delivery":"steer"}}}"#
        )
        let file = events[2].rawProperties?["part"]?.dictionaryValue
        #expect(file?["url"]?.stringValue == "data:image/jpeg;base64,AA==")
        #expect(file?["mime"]?.stringValue == "image/jpeg")
        #expect(file?["filename"]?.stringValue == "Photo 1.png")
    }

    @Test func parseEvents_projectsPromptedFilesWithoutURI() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"What","files":[{"mime":"image/jpeg","name":"Photo 1.jpeg"}]},"delivery":"steer"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated", "message.part.updated"])
        let file = events[2].rawProperties?["part"]?.dictionaryValue
        #expect(file?["type"]?.stringValue == "file")
        #expect(file?["url"] == nil)
        #expect(file?["mime"]?.stringValue == "image/jpeg")
        #expect(file?["filename"]?.stringValue == "Photo 1.jpeg")
    }

    @Test func parseEvents_projectsGlobalBusPromptedPayload() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"directory":"/repo","payload":{"id":"evt_1","type":"session.next.prompted","properties":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from CLI")
        #expect(events[0].sessionID == "ses-1")
    }

    @Test func parseEvents_projectsFlattenedPromptedFields() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.prompted","sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"delivery":"steer"}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from CLI")
    }

    @Test func parseEvents_projectsSyncSiblingPrompted() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"payload":{"type":"sync","syncEvent":{"type":"session.next.prompted.1","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"}}}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from CLI")
    }

    @Test func parseEvents_keepsPromptWhenNestedDataAlsoPresent() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"Hello from CLI"},"data":{"ignored":true}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from CLI")
    }

    @Test func parseEvents_projectsPromptedJsonStringPrompt() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":"{\"text\":\"Hello from CLI\"}"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from CLI")
    }

    @Test func parseEvent_projectsToolInputDeltaIntoPartDelta() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.tool.input.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","delta":"path"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.rawProperties?["field"]?.stringValue == "input")
        #expect(event?.rawProperties?["partID"]?.stringValue == "prt-1")
    }

    @Test func parseEvents_projectsLiveToolEnvelopeCreatedIntoStateTimeStart() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.tool.input.started","created":1710000000000,"data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","name":"task"}}"#
        )
        #expect(event?.type == "message.part.updated")
        let state = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["id"]?.stringValue == "prt-1")
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "task")
        #expect(state?["status"]?.stringValue == "pending")
        #expect(state?["time"]?.dictionaryValue?["start"]?.int64Value == 1_710_000_000_000)
    }

    @Test func parseEvent_parsesToolInputJsonString() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.tool.called","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","callID":"prt-1","name":"task","input":"{\"agent\":\"explore\",\"description\":\"Find auth\"}"}}"#
        )
        let input = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue?["input"]?.dictionaryValue
        #expect(input?["agent"]?.stringValue == "explore")
        #expect(input?["description"]?.stringValue == "Find auth")
    }

    @Test func parseEvent_projectsInboxCancelledIntoMessageRemoved() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.inbox.cancelled","data":{"inboxID":"msg-2","sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "message.removed")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["messageID"]?.stringValue == "msg-2")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvents_marksInboxSyntheticItemTextAsSynthetic() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-3","sessionID":"ses-1","item":{"type":"synthetic","payload":{"text":"<system-reminder>Plan mode</system-reminder>"},"delivery":"steer"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["synthetic"]?.boolValue == true)
    }

    @Test func parseEvents_inboxUserItemTextIsNotSynthetic() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello"},"delivery":"steer"}}}"#
        )
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["synthetic"] == nil)
    }

    @Test func parseEvents_projectsInboxEnqueuedItemPayloadIntoUserText() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello from inbox","files":[]},"delivery":"steer"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["id"]?.stringValue == "msg-2")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["waiting"]?.boolValue == true)
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["delivery"]?.stringValue == "steer")
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "Hello from inbox")
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["messageID"]?.stringValue == "msg-2")
    }

    @Test func parseEvent_projectsInboxDeliveryChangedAsStillWaiting() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.inbox.delivery.changed","data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"delivery":"queue"}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["waiting"]?.boolValue == true)
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["delivery"]?.stringValue == "queue")
    }

    @Test func parseEvents_projectsInboxEnqueuedCreatedIntoUserInfoTime() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","created":1710000000000,"data":{"inboxID":"msg-2","sessionID":"ses-1","item":{"type":"user","payload":{"text":"Hello from inbox","files":[]},"delivery":"steer"}}}"#
        )
        #expect(events[0].type == "message.updated")
        #expect(
            events[0].rawProperties?["info"]?.dictionaryValue?["time"]?.dictionaryValue?["created"]?.int64Value
                == 1_710_000_000_000
        )
    }

    @Test func parseEvents_projectsStepFailedIntoSessionErrorWithV2Message() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.failed","data":{"sessionID":"ses-1","error":{"type":"ProviderError","message":"model overloaded"}}}"#
        )
        #expect(events.map(\.type) == ["session.error", "session.idle"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].sessionErrorMessage == "model overloaded")
    }

    @Test func parseEvent_projectsLiveExecutionStartedIntoBusyStatus() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"id":"evt_3","type":"session.execution.started","data":{"sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "session.status")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue == "busy")
        #expect(event?.triggersStatusRefresh == true)
    }

    @Test func parseEvent_projectsLiveExecutionSucceededIntoIdle() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.execution.succeeded","data":{"sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "session.idle")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.triggersStatusRefresh == true)
    }

    @Test func parseEvents_projectsReasoningStartedIntoAssistantAndReasoningPart() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.reasoning.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"r-1"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["type"]?.stringValue == "reasoning")
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["id"]?.stringValue == "r-1")
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["messageID"]?.stringValue == "msg-2")
    }

    @Test func parseEvent_projectsReasoningDeltaIntoPartDelta() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.reasoning.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"r-1","delta":"hmm"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.rawProperties?["field"]?.stringValue == "reasoning")
        #expect(event?.rawProperties?["delta"]?.stringValue == "hmm")
        #expect(event?.rawProperties?["partID"]?.stringValue == "r-1")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvent_doesNotTreatLiveStepEndedAsIdle() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["message.updated"])
        #expect(events[0].sessionID == "ses-1")
        #expect(!events.contains(where: { $0.type == "session.idle" }))
        #expect(!events.contains(where: { $0.type == "session.active.snapshot" }))
    }

    @Test func parseEvents_projectsLiveShellStartedIntoBusyAndBashPart() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.shell.started","data":{"sessionID":"ses-1","callID":"sh-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["session.status", "message.part.updated"])
        #expect(events[0].rawProperties?["status"]?.dictionaryValue?["type"]?.stringValue == "busy")
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "bash")
    }

    @Test func parseEvents_v2ShellStartedProjectsTheRestShellItemWithoutTouchingStatus() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_sh1","type":"session.shell.started","data":{"sessionID":"ses-1","shell":{"id":"sh_1","status":"running","command":"git status","time":{"started":100}}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated", "message.updated", "message.part.updated"])
        #expect(!events.contains(where: { $0.type == "session.status" }))
        let user = events[0].rawProperties?["info"]?.dictionaryValue
        #expect(user?["id"]?.stringValue == "msg_sh1")
        #expect(user?["role"]?.stringValue == "user")
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "git status")
        #expect(events[2].rawProperties?["info"]?.dictionaryValue?["id"]?.stringValue == "msg_sh1:assistant")
        let tool = events[3].rawProperties?["part"]?.dictionaryValue
        #expect(tool?["id"]?.stringValue == "msg_sh1:tool")
        #expect(tool?["messageID"]?.stringValue == "msg_sh1:assistant")
        #expect(tool?["tool"]?.stringValue == "bash")
        let state = tool?["state"]?.dictionaryValue
        #expect(state?["status"]?.stringValue == "running")
        #expect(state?["input"]?.dictionaryValue?["command"]?.stringValue == "git status")
    }

    @Test func parseEvents_v2ShellEndedCompletesTheStartedItemWithOutputAndExit() {
        let adapter = EventDomainAdapter()
        _ = adapter.parseEvents(
            dataLine: #"{"id":"evt_sh1","type":"session.shell.started","data":{"sessionID":"ses-1","shell":{"id":"sh_1","status":"running","command":"false","time":{"started":100}}}}"#
        )
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_sh2","type":"session.shell.ended","data":{"sessionID":"ses-1","shell":{"id":"sh_1","status":"exited","command":"false","exit":1,"time":{"started":100,"completed":150}},"output":{"output":"boom","truncated":false}}}"#
        )
        let tool = events.last?.rawProperties?["part"]?.dictionaryValue
        #expect(tool?["id"]?.stringValue == "msg_sh1:tool")
        let state = tool?["state"]?.dictionaryValue
        #expect(state?["status"]?.stringValue == "completed")
        #expect(state?["output"]?.stringValue == "boom")
        #expect(state?["metadata"]?.dictionaryValue?["exit"]?.int64Value == 1)
        #expect(!events.contains(where: { $0.type == "session.status" || $0.type == "session.idle" }))
    }

    @Test func parseEvents_v2ShellEndedWithoutAStartIsLeftToHistory() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_sh2","type":"session.shell.ended","data":{"sessionID":"ses-1","shell":{"id":"sh_unknown","status":"exited","exit":0}}}"#
        )
        #expect(events.isEmpty)
    }

    @Test func parseEvents_syntheticInboxItemIsNotAWaitingPrompt() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.inbox.enqueued","data":{"sessionID":"ses-1","inboxID":"msg_s","item":{"type":"synthetic","payload":{"text":"The following shell command was executed by the user"}},"delivery":"steer"}}"#
        )
        let info = events.first?.rawProperties?["info"]?.dictionaryValue
        #expect(info?["waiting"]?.boolValue == false)
        #expect(info?["delivery"] == nil)
    }

    @Test func parseEvents_projectsLiveCompactionEndedIntoPartNotIdle() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.compaction.ended","data":{"sessionID":"ses-1","messageID":"msg-c"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(!events.contains(where: { $0.type == "session.idle" }))
        #expect(!events.contains(where: { $0.type == "session.active.snapshot" }))
    }

    @Test func parseEvents_keysLiveCompactionToTheStartedItemAndCarriesTheSummary() {
        let adapter = EventDomainAdapter()
        func part(_ line: String) -> [String: AnySendable]? {
            adapter.parseEvents(dataLine: line)
                .first { $0.type == "message.part.updated" }?
                .rawProperties?["part"]?.dictionaryValue
        }
        // Captured v2 shapes: `started` carries the inbox `inputID`, `ended` no id at all.
        let started = part(
            #"{"id":"evt_0f9026d76001","created":1790884081021,"type":"session.compaction.started","#
                + #""data":{"sessionID":"ses-1","reason":"manual","recent":"","inputID":"msg_0f9026d76001"}}"#
        )
        let ended = part(
            #"{"id":"evt_0f9027b10001","created":1790884084400,"type":"session.compaction.ended","#
                + ###""data":{"sessionID":"ses-1","reason":"manual","text":"## Objective\n- Hi.","recent":""}}"###
        )
        let autoEnded = part(
            #"{"id":"evt_0f9030000001","created":1790884090000,"type":"session.compaction.ended","#
                + ###""data":{"sessionID":"ses-1","reason":"auto","text":"## Objective","recent":""}}"###
        )

        #expect(started?["messageID"]?.stringValue == "msg_0f9026d76001")
        #expect(started?["completed"] == .bool(false))
        #expect(ended?["messageID"]?.stringValue == "msg_0f9026d76001")
        #expect(ended?["completed"] == .bool(true))
        #expect(ended?["summary"]?.stringValue == "## Objective\n- Hi.")
        // Without a started item the server appends one keyed by the ended event.
        #expect(autoEnded?["messageID"]?.stringValue == "msg_0f9030000001")
        #expect(adapter.parseEvents(
            dataLine: ###"{"type":"session.compaction.delta","data":{"sessionID":"ses-1","text":"## Obj"}}"###
        ).isEmpty)
    }

    @Test func parseEvents_projectsV2RevertCommittedWithItsRevertPoint() {
        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"id":"evt_00d055e78001","created":1786924850808,"type":"session.revert.committed","#
                + #""data":{"sessionID":"ses-1","to":"msg_00cf91b85001"}}"#
        )
        #expect(events.map(\.type) == ["session.revert.committed"])
        #expect(events.first?.sessionID == "ses-1")
        #expect(events.first?.rawProperties?["messageID"]?.stringValue == "msg_00cf91b85001")
    }

    @Test func parseEvents_toastsStructuredCompactionFailureWhenNoRowCanBeBuilt() {
        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"type":"session.compaction.failed","data":{"sessionID":"ses-1","error":{"type":"compaction.failed","message":"Could not compact"}}}"#
        )
        #expect(events.map(\.type) == ["session.error", "session.idle"])
    }

    @Test func parseEvents_skipsCompactionToastWhenTheRowCarriesTheError() {
        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"type":"session.compaction.failed","data":{"sessionID":"ses-1","messageID":"msg-c","error":{"type":"compaction.failed","message":"Could not compact"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated", "session.idle"])
        let data = events[0].rawProperties?["info"]?.dictionaryValue?["error"]?.dictionaryValue?["data"]?.dictionaryValue
        #expect(data?["message"]?.stringValue == "Could not compact")
    }

    @Test func parseEvents_clearsCompactionErrorWhenTheRetryEnds() {
        let events = EventDomainAdapter().parseEvents(
            dataLine: #"{"type":"session.compaction.ended","data":{"sessionID":"ses-1","messageID":"msg-c"}}"#
        )
        let updated = events.first { $0.type == "message.updated" }
        #expect(updated?.rawProperties?["info"]?.dictionaryValue?["error"] == .null)
    }

    @Test func parseEvents_projectsLiveCompactionFailedIntoPartErrorAndIdle() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.compaction.failed","data":{"sessionID":"ses-1","messageID":"msg-c"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated", "session.error", "session.idle"])
        #expect(events.last?.triggersStatusRefresh == true)
    }

    @Test func parseEvents_projectsStepEndedIntoAssistantMessageAndActiveSnapshot() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["message.updated"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "assistant")
        #expect(!events.contains(where: { $0.type == "session.active.snapshot" }))
    }

    @Test func parseEvents_promptedTextPartSurvivesDeduper() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_prompt","type":"session.next.prompted","data":{"sessionID":"ses-1","messageID":"msg-1","prompt":{"text":"What are you up to?"}}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "message.part.updated"])
        #expect(events.map(\.id) == ["evt_prompt:0", "evt_prompt:1"])
        let deduper = SseEventDeduper()
        #expect(events.allSatisfy { deduper.accept($0.id) })
        #expect(events[1].rawProperties?["part"]?.dictionaryValue?["text"]?.stringValue == "What are you up to?")
    }

    @Test func parseEvents_stampsDistinctIdsOnProjectedSiblings() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_shell","type":"session.shell.started","data":{"sessionID":"ses-1","callID":"sh-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.id) == ["evt_shell:0", "evt_shell:1"])
        let deduper = SseEventDeduper()
        #expect(events.allSatisfy { deduper.accept($0.id) })
    }

    @Test func parseEvents_projectsReasoningEndedWithEndOnly() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.reasoning.ended","created":2000,"data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"r-1","text":"hmm"}}"#
        )
        let time = events.first?.rawProperties?["part"]?.dictionaryValue?["time"]?.dictionaryValue
        #expect(time?["start"] == nil)
        #expect(time?["end"]?.int64Value == 2000)
    }

    @Test func parseEvent_readsOfficialSSEEnvelopeWithEventAndJSONStringData() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"id":"evt_9","event":"session.next.tool.called","data":"{\"sessionID\":\"ses-1\",\"assistantMessageID\":\"msg-2\",\"callID\":\"call-1\",\"tool\":\"bash\",\"input\":{}}"}"#
        )
        #expect(event?.type == "message.part.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "bash")
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue?["status"]?.stringValue == "running")
    }

    @Test func parseEvent_toolCalledWithoutNameDoesNotInventPlaceholderTool() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.next.tool.called","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{}}}"#
        )
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "")
    }

    @Test func parseEvent_infersQuestionToolFromInputQuestions() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.next.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{"questions":[{"question":"One"},{"question":"Two"},{"question":"Three"}]}}}"#
        )
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "question")
    }

    @Test func parseEvent_toolProgressMetadataCarriesSubagentSessionID() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.tool.progress","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","metadata":{"sessionID":"child-1","status":"running"}}}"#
        )
        let state = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue
        #expect(state?["status"]?.stringValue == "running")
        #expect(state?["metadata"]?.dictionaryValue?["sessionID"]?.stringValue == "child-1")
    }

    @Test func parseEvent_toolProgressMetadataTitleBecomesTheLiveTitle() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.tool.progress","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","metadata":{"title":"Run migrations · step 3/6","step":3}}}"#
        )
        let state = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue
        #expect(state?["title"]?.stringValue == "Run migrations · step 3/6")
    }

    @Test func parseEvent_toolSuccessMergesStructuredAndMetadata() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","structured":{"count":2},"metadata":{"sessionID":"child-1"}}}"#
        )
        let metadata = event?.rawProperties?["part"]?.dictionaryValue?["state"]?.dictionaryValue?["metadata"]?.dictionaryValue
        #expect(metadata?["sessionID"]?.stringValue == "child-1")
        #expect(metadata?["count"]?.int64Value == 2)
    }

    @Test func parseEvent_infersWebsearchToolFromInputQuery() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.next.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{"query":"opencode mobile"}}}"#
        )
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "websearch")
    }

    @Test func parseEvents_scopesV2OrdinalPartIDsToTheirAssistantMessage() {
        // Captured v2 shape: each step's first text and reasoning are ordinal 0
        // and carry no part id, so the id must include the assistant message.
        let adapter = EventDomainAdapter()
        func partID(_ line: String) -> String? {
            adapter.parseEvents(dataLine: line).lazy.compactMap { event in
                event.rawProperties?["part"]?.dictionaryValue?["id"]?.stringValue
                    ?? event.rawProperties?["partID"]?.stringValue
            }.first
        }
        let ids = [
            #"{"type":"session.text.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-a","ordinal":0}}"#,
            #"{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-a","ordinal":0,"delta":"Look"}}"#,
            #"{"type":"session.text.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-b","ordinal":0,"text":"Done"}}"#,
            #"{"type":"session.reasoning.started","data":{"sessionID":"ses-1","assistantMessageID":"msg-a","ordinal":0,"state":{}}}"#,
            #"{"type":"session.reasoning.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-b","ordinal":1,"delta":"Hm"}}"#,
        ].map(partID)
        #expect(ids == ["msg-a:text:0", "msg-a:text:0", "msg-b:text:0", "msg-a:reasoning:0", "msg-b:reasoning:1"])
    }

    @Test func parseEvents_stepStartedStampsAssistantCreatedTime() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"id":"evt_1","created":1786924698081,"type":"session.step.started","data":{"sessionID":"ses-1","agent":"build","assistantMessageID":"msg-a"}}"#
        )
        let info = events.first { $0.type == "message.updated" }?.rawProperties?["info"]?.dictionaryValue
        #expect(info?["time"]?.dictionaryValue?["created"]?.int64Value == 1786924698081)
    }

    @Test func parseEvent_projectsLiveTextDeltaUsingOrdinalPartID() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","ordinal":0,"delta":"Hi"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["messageID"]?.stringValue == "msg-2")
        #expect(event?.rawProperties?["partID"]?.stringValue == "msg-2:text:0")
        #expect(event?.rawProperties?["delta"]?.stringValue == "Hi")
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvent_projectsUsageUpdatedIntoAssistantTokens() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.usage.updated","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","tokens":{"input":120,"output":4,"reasoning":0}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["id"]?.stringValue == "msg-2")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["tokens"]?.dictionaryValue?["input"]?.int64Value == 120)
        #expect(event?.triggersMessageRefresh == true)
    }

    @Test func parseEvent_dropsUsageUpdatedWithoutAssistantId() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.usage.updated","data":{"sessionID":"ses-1","tokens":{"input":120,"output":4}}}"#
        )
        #expect(event == nil)
    }

    @Test func parse_garbageJson_isUnrecognized() {
        let adapter = EventDomainAdapter()
        guard case .unrecognized = adapter.parse("not-json") else {
            Issue.record("expected unrecognized")
            return
        }
    }

    @Test func parse_objectWithoutType_isUnrecognized() {
        let adapter = EventDomainAdapter()
        guard case .unrecognized = adapter.parse(#"{"foo":1}"#) else {
            Issue.record("expected unrecognized")
            return
        }
    }

    @Test func parse_usageUpdatedWithoutAssistantId_isDropped() {
        let adapter = EventDomainAdapter()
        let outcome = adapter.parse(
            #"{"type":"session.usage.updated","data":{"sessionID":"ses-1","tokens":{"input":120,"output":4}}}"#
        )
        guard case .dropped(let type) = outcome else {
            Issue.record("expected dropped")
            return
        }
        #expect(type == "session.usage.updated")
    }

    @Test func parse_usageUpdatedWithAssistantId_emitsMessageUpdated() {
        let adapter = EventDomainAdapter()
        let outcome = adapter.parse(
            #"{"type":"session.usage.updated","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","tokens":{"input":120,"output":4,"reasoning":0}}}"#
        )
        guard case .events(let events) = outcome else {
            Issue.record("expected events")
            return
        }
        #expect(events.map(\.type) == ["message.updated"])
    }

    @Test func parse_syncWithoutSyncEvent_isUnrecognized() {
        let adapter = EventDomainAdapter()
        guard case .unrecognized = adapter.parse(#"{"payload":{"type":"sync","properties":{}}}"#) else {
            Issue.record("expected unrecognized")
            return
        }
    }

    @Test func parseEvent_projectsLiveRenamedIntoSessionUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.renamed","data":{"sessionID":"ses-1","title":"Testing conversation"}}"#
        )
        #expect(event?.type == "session.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["title"]?.stringValue == "Testing conversation")
    }

    @Test func parseEvent_projectsForkedIntoTopLevelSessionCreated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.forked","data":{"sessionID":"ses_fork","parentID":"ses_src","boundary":{"type":"through","messageID":"msg_1"}}}"#
        )
        #expect(event?.type == "session.created")
        #expect(event?.sessionID == "ses_fork")
        #expect(event?.triggersHomeRefresh == true)
        #expect(event?.rawProperties?["parentID"] == nil)
        #expect(event?.rawProperties?["forkedFromSessionID"]?.stringValue == "ses_src")
    }

    @Test func parseEvent_sessionArchiveEventsRefreshHomeLists() {
        let adapter = EventDomainAdapter()
        let archived = adapter.parseEvent(
            dataLine: #"{"id":"evt_1","type":"session.archived","data":{"sessionID":"ses-1"},"durable":{"aggregateID":"ses-1","seq":4,"version":1}}"#
        )
        let unarchived = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"sync","syncEvent":{"id":"evt_2","type":"session.unarchived.1","data":{"sessionID":"ses-2"}}}}"#
        )
        #expect(archived?.type == "session.archived")
        #expect(archived?.sessionID == "ses-1")
        #expect(archived?.triggersHomeRefresh == true)
        #expect(unarchived?.type == "session.unarchived")
        #expect(unarchived?.sessionID == "ses-2")
        #expect(unarchived?.triggersHomeRefresh == true)
    }

    @Test func parseEvent_remapsPermissionV2Asked() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"permission.v2.asked","data":{"sessionID":"ses-1"}}"#
        )
        #expect(event?.type == "permission.asked")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_projectsFormCreatedIntoQuestionAsked() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"form.created","data":{"form":{"id":"frm_1","sessionID":"ses-1","title":"Questions"}}}"#
        )
        #expect(event?.type == "question.asked")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_projectsFormRepliedIntoQuestionReplied() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"form.replied","data":{"id":"frm_1","sessionID":"ses-1","answer":{"q0":"Wait"}}}"#
        )
        #expect(event?.type == "question.replied")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func parseEvent_readsV2SameNamedLegacyMessageUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"message.updated","data":{"sessionID":"ses-1","info":{"id":"msg-1","role":"user"}}}"#
        )
        #expect(event?.type == "message.updated")
        #expect(event?.sessionID == "ses-1")
    }

    @Test func statusLabel_mapsBusyAndRetry() {
        let adapter = EventDomainAdapter()
        let busy = adapter.statusLabel(from: SessionStatus(sessionID: "1", raw: ["type": "busy"]))
        let retry = adapter.statusLabel(from: SessionStatus(sessionID: "1", raw: ["type": "retry"]))
        let idle = adapter.statusLabel(from: SessionStatus(sessionID: "1", raw: ["type": "idle"]))
        #expect(busy == "Working")
        #expect(retry == "Retrying")
        #expect(idle == nil)
    }

    @Test func sessionStatus_readsBusyPayload() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}}"#
        )
        let status = adapter.sessionStatus(from: event!)
        #expect(status?.sessionID == "ses-1")
        #expect(status?.raw["type"] == "busy")
        #expect(adapter.statusLabel(from: status!) == "Working")
        #expect(adapter.sessionActivity(from: event!)?.activity == .running)
    }

    @Test func parseEvents_projectsRetryScheduledIntoRetryingActivity() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.retry.scheduled","data":{"sessionID":"ses-1","attempt":3,"next":5000,"message":"rate limited"}}"#
        )
        #expect(event?.type == "session.status")
        let parsed = adapter.sessionActivity(from: event!)
        #expect(parsed?.sessionID == "ses-1")
        if case let .retrying(attempt, nextAtMs, message, _, _, _) = parsed?.activity {
            #expect(attempt == 3)
            #expect(nextAtMs == 5_000)
            #expect(message == "rate limited")
        } else {
            Issue.record("expected retrying")
        }
        #expect(adapter.statusLabel(from: adapter.sessionStatus(from: event!)!) == "Retrying · 3")
        #expect(parsed?.activity.chatStatusLabel == "Retrying · 3 · rate limited")
    }

    @Test func sessionActivity_readsV2RetryAtAndErrorMessage() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.retry.scheduled","data":{"sessionID":"ses-1","attempt":3,"at":9000,"error":{"type":"provider.rate-limit","message":"{\"type\":\"message\",\"message\":\"Slow down\"}"}}}"#
        )
        if case let .retrying(attempt, nextAtMs, message, errorType, _, _) = adapter.sessionActivity(from: event!)?.activity {
            #expect(attempt == 3)
            #expect(nextAtMs == 9_000)
            #expect(message == "Slow down")
            #expect(errorType == "provider.rate-limit")
        } else {
            Issue.record("expected retrying")
        }
    }

    @Test func sessionActivity_readsRetryAction() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.retry.scheduled","data":{"sessionID":"ses-1","attempt":1,"at":4000,"error":{"type":"provider.quota","message":"Out of quota"},"action":{"label":"Upgrade","link":"https://example.com"}}}"#
        )
        guard case let .retrying(_, _, _, errorType, actionLabel, actionLink) = adapter.sessionActivity(from: event!)?.activity else {
            Issue.record("expected retrying")
            return
        }
        #expect(errorType == "provider.quota")
        #expect(actionLabel == "Upgrade")
        #expect(actionLink == "https://example.com")
    }

    @Test func stepFailed_keepsRetryAfterOnTheTranslatedError() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.step.failed","data":{"sessionID":"ses-1","assistantMessageID":"a1","error":{"type":"provider.free-limit","message":"Free usage limit reached.","status":429,"retryAfterMs":3600000}}}"#
        )
        let data = events.first?.rawProperties?["info"]?.dictionaryValue?["error"]?.dictionaryValue?["data"]?.dictionaryValue
        #expect(data?["retryAfterMs"]?.int64Value == 3_600_000)
    }

    @Test func stepFailed_withAssistantId_putsTheErrorOnTheMessageAndSkipsTheSnackbarEvent() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.step.failed","data":{"sessionID":"ses-1","assistantMessageID":"a1","error":{"type":"provider.auth","message":"Sign in","status":401,"response":{"body":"denied"}}}}"#
        )
        #expect(events.map(\.type) == ["message.updated"])
        let info = events[0].rawProperties?["info"]?.dictionaryValue
        let error = info?["error"]?.dictionaryValue
        let data = error?["data"]?.dictionaryValue
        #expect(error?["name"]?.stringValue == "provider.auth")
        #expect(data?["message"]?.stringValue == "Sign in")
        #expect(data?["statusCode"]?.int64Value == 401)
        #expect(data?["responseBody"]?.stringValue == "denied")
    }

    @Test func sessionTitleUpdate_readsInfoTitle() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1","title":"Live title"}}}}"#
        )
        let update = adapter.sessionTitleUpdate(from: event!)
        #expect(update?.sessionID == "ses-1")
        #expect(update?.title == "Live title")
    }

    @Test func sessionDeletedID_readsV1InfoIDAndV2SessionID() {
        let adapter = EventDomainAdapter()
        let v1 = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.deleted","properties":{"info":{"id":"ses-1","title":"Gone"}}}}"#
        )
        #expect(adapter.sessionDeletedID(from: v1!) == "ses-1")
        let v2 = adapter.parseEvent(
            dataLine: #"{"id":"evt_1","type":"session.deleted","data":{"sessionID":"ses-2"},"durable":{"aggregateID":"ses-2","seq":18,"version":2}}"#
        )
        #expect(adapter.sessionDeletedID(from: v2!) == "ses-2")
        let updated = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1"}}}}"#
        )
        #expect(adapter.sessionDeletedID(from: updated!) == nil)
    }

    @Test func sessionRevertUpdate_readsV1SessionAndV2RevertEvents() {
        let adapter = EventDomainAdapter()
        func update(_ line: String) -> SessionRevertUpdate? {
            adapter.sessionRevertUpdate(from: adapter.parseEvent(dataLine: line)!)
        }
        #expect(
            update(#"{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1","time":{"created":1},"revert":{"messageID":"msg-2"}}}}}"#)
                == SessionRevertUpdate(sessionID: "ses-1", messageID: "msg-2")
        )
        #expect(
            update(#"{"payload":{"type":"session.updated","properties":{"info":{"id":"ses-1","title":"T","time":{"created":1}}}}}"#)
                == SessionRevertUpdate(sessionID: "ses-1", messageID: nil)
        )
        #expect(
            update(#"{"type":"session.revert.staged","data":{"sessionID":"ses-2","revert":{"messageID":"msg-5"}}}"#)
                == SessionRevertUpdate(sessionID: "ses-2", messageID: "msg-5")
        )
        #expect(
            update(#"{"type":"session.revert.cleared","data":{"sessionID":"ses-2"}}"#)
                == SessionRevertUpdate(sessionID: "ses-2", messageID: nil)
        )
        #expect(
            update(#"{"type":"session.revert.committed","data":{"sessionID":"ses-2","to":"msg-5"}}"#)
                == SessionRevertUpdate(sessionID: "ses-2", messageID: nil)
        )
        // v2 rename is projected as a partial `session.updated`; it must not clear a staged revert.
        #expect(update(#"{"type":"session.renamed","data":{"sessionID":"ses-2","title":"New"}}"#) == nil)
    }

    @Test func sessionTitleUpdate_ignoresNonUpdatedEvents() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"payload":{"type":"session.created","properties":{"info":{"id":"ses-1","title":"New"}}}}"#
        )
        #expect(adapter.sessionTitleUpdate(from: event!) == nil)
    }
}

final class FakeForegroundEventStream: LiveConnection, @unchecked Sendable {
    private let lock = NSLock()
    private var _connectionState: EventStreamConnectionState = .idle
    private var _snapshot = LiveConnectionSnapshot()
    private var generationClock: UInt64 = 0
    private var wasConnected = false
    private var occupying = false
    private var explicitLeaseModeling = false

    init(connected: Bool = true) {
        if connected {
            setConnectionState(.connected)
        }
    }
    var connectionState: EventStreamConnectionState {
        lock.lock()
        defer { lock.unlock() }
        return _connectionState
    }
    var snapshot: LiveConnectionSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return _snapshot
    }
    private var continuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]
    private var eventBuffer: [OpencodeEvent] = []
    private let eventBufferLimit = 64
    private var connectionStateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]
    private var snapshotContinuations: [UUID: AsyncStream<LiveConnectionSnapshot>.Continuation] = [:]

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let id = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._connectionState
            self.connectionStateContinuations[id] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.connectionStateContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot> {
        let id = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._snapshot
            self.snapshotContinuations[id] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.snapshotContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    var eventSubscriberCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return continuations.count
    }

    var hasEventSubscriber: Bool { eventSubscriberCount > 0 }

    var hasConnectionStateSubscriber: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !connectionStateContinuations.isEmpty
    }

    var hasSnapshotSubscriber: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !snapshotContinuations.isEmpty
    }

    var hasCoordinatorSubscribers: Bool {
        hasEventSubscriber && hasConnectionStateSubscriber && hasSnapshotSubscriber
    }

    func events() -> AsyncStream<OpencodeEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: OpencodeEvent.self)
        lock.lock()
        continuations[id] = continuation
        let pending = eventBuffer
        eventBuffer.removeAll()
        lock.unlock()
        for event in pending {
            continuation.yield(event)
        }
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.continuations.removeValue(forKey: id)
            self.lock.unlock()
        }
        return stream
    }

    func start() {
        lock.lock()
        if occupying || _connectionState == .connected {
            occupying = true
            lock.unlock()
            return
        }
        occupying = true
        bumpGenerationLocked()
        wasConnected = false
        lock.unlock()
        setConnectionState(.connected)
    }

    func stop() async {
        lock.lock()
        occupying = false
        wasConnected = false
        _connectionState = .idle
        bumpGenerationLocked()
        applyLeaseOccupancyLocked(.idle)
        let stateConts = Array(connectionStateContinuations.values)
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in stateConts {
            continuation.yield(.idle)
        }
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func openGap(_ reason: String? = nil) {
        lock.lock()
        bumpGenerationLocked()
        _snapshot.connectionState = _connectionState
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        let previous = _connectionState
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
        if previous == .connected {
            setConnectionState(.reconnecting)
            Task.detached { [weak self] in
                await Task.yield()
                self?.setConnectionState(.connected)
            }
        }
        _ = reason
    }

    func openNewerThan(_ generation: UInt64) {
        repeat {
            openGap()
        } while snapshot.generation <= generation
    }

    /// Traces that emit `target.open` / `target.close` own live-target occupancy.
    /// Other tests keep instant-live on Connected.
    func modelLeasesExplicitly() {
        lock.lock()
        explicitLeaseModeling = true
        lock.unlock()
    }

    func openTarget(_ target: String) {
        lock.lock()
        explicitLeaseModeling = true
        if !_snapshot.liveTargets.contains(target) {
            _snapshot.liveTargets.append(target)
        }
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func closeTarget(_ target: String) {
        lock.lock()
        explicitLeaseModeling = true
        _snapshot.liveTargets.removeAll { $0 == target }
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func setLiveTargets(_ targets: [String]) {
        lock.lock()
        _snapshot.liveTargets = targets
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func setConnectionState(_ state: EventStreamConnectionState) {
        lock.lock()
        let dropFromConnected = wasConnected && (state == .idle || state == .disconnected || state == .reconnecting || state == .authFailed)
        occupying = state != .idle && state != .disconnected
        _connectionState = state
        if dropFromConnected {
            bumpGenerationLocked()
        }
        applyLeaseOccupancyLocked(state)
        if state == .connected {
            wasConnected = true
            occupying = true
        }
        if state == .idle {
            wasConnected = false
        }
        let stateConts = Array(connectionStateContinuations.values)
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in stateConts {
            continuation.yield(state)
        }
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func applyConfig(profileID: String, protocol serverProtocol: ServerProtocol, directories: [String]) {
        lock.lock()
        let config = LiveConnectionConfig(
            profileID: profileID,
            connectionIdentity: profileID,
            serverProtocol: serverProtocol,
            directories: directories
        )
        let targets = SseSubscribeTargets.targets(protocol: serverProtocol, directories: directories).map(\.key)
        _snapshot.config = config
        _snapshot.requiredTargets = targets
        _snapshot.liveTargets = liveTargets(current: _snapshot.liveTargets, required: targets, state: _connectionState)
        _snapshot.connectionState = _connectionState
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func emit(_ event: OpencodeEvent) {
        lock.lock()
        let conts = Array(continuations.values)
        if conts.isEmpty {
            eventBuffer.append(event)
            if eventBuffer.count > eventBufferLimit {
                eventBuffer.removeFirst()
            }
            lock.unlock()
            return
        }
        lock.unlock()
        for continuation in conts {
            continuation.yield(event)
        }
    }

    /// Same generation and gap; yields even when the snapshot is equal.
    func republishSameGeneration() {
        lock.lock()
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    private func applyLeaseOccupancyLocked(_ state: EventStreamConnectionState) {
        let required = _snapshot.requiredTargets.isEmpty
            ? (state == .connected || state == .connecting || state == .reconnecting ? ["api/event"] : [])
            : _snapshot.requiredTargets
        _snapshot.connectionState = state
        _snapshot.requiredTargets = required
        _snapshot.liveTargets = liveTargets(current: _snapshot.liveTargets, required: required, state: state)
    }

    private func liveTargets(
        current: [String],
        required: [String],
        state: EventStreamConnectionState
    ) -> [String] {
        switch state {
        case .idle, .disconnected:
            []
        case .connected where !explicitLeaseModeling:
            required
        default:
            explicitLeaseModeling ? current : []
        }
    }

    private func bumpGenerationLocked() {
        generationClock += 1
        _snapshot.generation = generationClock
    }
}
