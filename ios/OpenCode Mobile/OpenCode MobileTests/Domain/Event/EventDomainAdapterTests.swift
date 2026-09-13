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
        #expect(events.map(\.type) == ["message.updated", "session.active.snapshot"])
        #expect(events[0].sessionID == "ses-1")
        #expect(!events.contains(where: { $0.type == "session.idle" }))
        #expect(events[1].triggersStatusRefresh == true)
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

    @Test func parseEvents_projectsLiveCompactionEndedIntoPartNotIdle() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.compaction.ended","data":{"sessionID":"ses-1","messageID":"msg-c"}}"#
        )
        #expect(events.map(\.type) == ["message.part.updated", "session.active.snapshot"])
        #expect(!events.contains(where: { $0.type == "session.idle" }))
    }

    @Test func parseEvents_projectsLiveCompactionFailedIntoPartErrorAndIdle() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.compaction.failed","data":{"sessionID":"ses-1","messageID":"msg-c"}}"#
        )
        #expect(events.map(\.type) == ["message.part.updated", "session.error", "session.idle"])
        #expect(events.last?.triggersStatusRefresh == true)
    }

    @Test func parseEvents_projectsStepEndedIntoAssistantMessageAndActiveSnapshot() {
        let adapter = EventDomainAdapter()
        let events = adapter.parseEvents(
            dataLine: #"{"type":"session.next.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.type) == ["message.updated", "session.active.snapshot"])
        #expect(events[0].sessionID == "ses-1")
        #expect(events[0].rawProperties?["info"]?.dictionaryValue?["role"]?.stringValue == "assistant")
        #expect(events[1].triggersStatusRefresh == true)
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
            dataLine: #"{"id":"evt_step","type":"session.step.ended","data":{"sessionID":"ses-1","assistantMessageID":"msg-2"}}"#
        )
        #expect(events.map(\.id) == ["evt_step:0", "evt_step:1"])
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

    @Test func parseEvent_infersWebsearchToolFromInputQuery() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.next.tool.success","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","id":"prt-1","input":{"query":"opencode mobile"}}}"#
        )
        #expect(event?.rawProperties?["part"]?.dictionaryValue?["tool"]?.stringValue == "websearch")
    }

    @Test func parseEvent_projectsLiveTextDeltaUsingOrdinalPartID() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.text.delta","data":{"sessionID":"ses-1","assistantMessageID":"msg-2","ordinal":0,"delta":"Hi"}}"#
        )
        #expect(event?.type == "message.part.delta")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["messageID"]?.stringValue == "msg-2")
        #expect(event?.rawProperties?["partID"]?.stringValue == "text-0")
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

    @Test func parseEvent_projectsLiveRenamedIntoSessionUpdated() {
        let adapter = EventDomainAdapter()
        let event = adapter.parseEvent(
            dataLine: #"{"type":"session.renamed","data":{"sessionID":"ses-1","title":"Testing conversation"}}"#
        )
        #expect(event?.type == "session.updated")
        #expect(event?.sessionID == "ses-1")
        #expect(event?.rawProperties?["info"]?.dictionaryValue?["title"]?.stringValue == "Testing conversation")
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
    private var gapClock: UInt64 = 0
    private var wasConnected = false
    private var occupying = false
    private var explicitLeaseModeling = false
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

    func events() -> AsyncStream<OpencodeEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: OpencodeEvent.self)
        lock.lock()
        continuations[id] = continuation
        lock.unlock()
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
        openGapLocked(.occupancyStart)
        wasConnected = false
        lock.unlock()
        setConnectionState(.connected)
    }

    func stop() {
        lock.lock()
        occupying = false
        wasConnected = false
        _connectionState = .idle
        openGapLocked(.stopped)
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

    func openGap(_ reason: LiveGapReason = .reconnect) {
        lock.lock()
        openGapLocked(reason)
        _snapshot.connectionState = _connectionState
        let snapshotConts = Array(snapshotContinuations.values)
        let snapshot = _snapshot
        lock.unlock()
        for continuation in snapshotConts {
            continuation.yield(snapshot)
        }
    }

    func openNewerThan(_ generation: UInt64, reason: LiveGapReason = .reconnect) {
        repeat {
            openGap(reason)
        } while (snapshot.gap?.generation ?? 0) <= generation
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
            openGapLocked(.reconnect)
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

    private func openGapLocked(_ reason: LiveGapReason) {
        gapClock += 1
        _snapshot.generation = gapClock
        _snapshot.gap = LiveGapRevision(generation: gapClock, reason: reason)
    }
}
