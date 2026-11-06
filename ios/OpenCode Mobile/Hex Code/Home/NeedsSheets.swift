import OpenCodeKit
import SwiftUI

/// The sheet for a question or approval the agent waits for (app plan, "Approvals" and
/// "Questions"). One sheet at a time, the oldest first; Hide leaves it unanswered.
struct NeedsSheet: View {
    let item: PendingItem
    let session: TodaySession
    let home: HomeModel

    var body: some View {
        Group {
            if let approval = item.approval {
                ApprovalSheet(item: item, request: approval, session: session, home: home)
            } else if let question = item.question {
                QuestionSheet(item: item, form: question, session: session, home: home)
            }
        }
        .hexToastHost(home.toasts, sheet: true)
    }

    /// "1 of 2" while more are waiting: this item's place in the queue.
    static func count(of item: PendingItem, in pending: [PendingItem]) -> String? {
        guard pending.count > 1 else { return nil }
        let position = (pending.firstIndex { $0.id == item.id } ?? 0) + 1
        return String(localized: "needs.count", defaultValue: "\(position) of \(pending.count)")
    }

    static var hideLabel: Text { Text(LocalizedStringResource("needs.hide", defaultValue: "Hide")) }

    /// The spoken row's second line while a take runs.
    static func live(_ state: SpokenAnswer.State) -> HexOption.Live? {
        switch state {
        case .idle: nil
        case .listening: .listening(String(localized: "needs.listening", defaultValue: "Listening… · release to finish"))
        case .transcribing: .transcribing(String(localized: "needs.transcribing", defaultValue: "Transcribing…"))
        }
    }
}

/// "Permission requested": what the agent wants to do, the command or diff, what Always would
/// allow, "Say what to do instead", and Deny / Always / Allow once.
private struct ApprovalSheet: View {
    let item: PendingItem
    let request: PendingPermissionV2
    let session: TodaySession
    let home: HomeModel

    @State private var sending: SessionStateAPIClient.PermissionDecision?
    @State private var spoken: SpokenAnswer?

    var body: some View {
        let content = ApprovalContent(request, childTitle: session.subagentTitle(of: item), projectID: session.projectID)
        HexSheet(
            request: .init(
                title: Text(LocalizedStringResource("needs.approval.title", defaultValue: "Permission requested")),
                count: NeedsSheet.count(of: item, in: session.pendingItems),
                hideLabel: NeedsSheet.hideLabel,
                onHide: { session.hideNeed() }
            ),
            amberEdge: true
        ) {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: content.what)
                        .hexFont(.body)
                        .foregroundStyle(HexColor.text2)
                    if let from = content.fromSubagent {
                        Text(verbatim: from)
                            .hexFont(.caption)
                            .foregroundStyle(HexColor.text3)
                    }
                }
                .padding(.bottom, 8)
                if let message = content.message {
                    Text(verbatim: message)
                        .hexFont(.body)
                        .foregroundStyle(HexColor.text2)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, 8)
                }
                if !content.code.isEmpty {
                    HexCode(lines: content.code)
                }
                if let always = content.alwaysLine {
                    SummaryText(text: always, style: .caption)
                        .foregroundStyle(HexColor.text3)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 10)
                }
                sayWhy
                    .padding(.top, 12)
                buttons(offersAlways: content.offersAlways)
                    .padding(.top, 16)
            }
            .padding(.bottom, 12)
        }
        .onAppear { if spoken == nil { spoken = SpokenAnswer(home: home) } }
        .onDisappear { Task { await spoken?.close() } }
    }

    /// Hold to say what the agent should do instead; Deny then sends it as its note.
    @ViewBuilder
    private var sayWhy: some View {
        if let spoken {
            HexOption(
                title: String(localized: "needs.approval.say_why", defaultValue: "Say what to do instead"),
                detail: spoken.text.isEmpty ? nil : spoken.text,
                mark: .none,
                enabled: sending == nil,
                live: NeedsSheet.live(spoken.state),
                press: HexPress(
                    began: { spoken.pressBegan() },
                    ended: { Task { await spoken.pressEnded(onText: { _ in }) } }
                )
            )
        }
    }

    private func buttons(offersAlways: Bool) -> some View {
        let busy = sending != nil
        // Deny waits for a take still listening or transcribing, so it never goes without its text.
        let taking = spoken?.isBusy ?? false
        return HStack(spacing: 8) {
            HexButton(
                LocalizedStringResource("needs.approval.deny", defaultValue: "Deny"),
                size: .regular,
                style: .danger,
                enabled: (!busy && !taking) || sending == .reject,
                loading: sending == .reject
            ) { reply(.reject) }
            Spacer(minLength: 0)
            if offersAlways {
                HexButton(
                    LocalizedStringResource("needs.approval.always", defaultValue: "Always"),
                    size: .regular,
                    enabled: !busy || sending == .always,
                    loading: sending == .always
                ) { reply(.always) }
            }
            HexButton(
                LocalizedStringResource("needs.approval.once", defaultValue: "Allow once"),
                size: .regular,
                style: .primary,
                enabled: !busy || sending == .once,
                loading: sending == .once
            ) { reply(.once) }
        }
    }

    private func reply(_ decision: SessionStateAPIClient.PermissionDecision) {
        guard sending == nil else { return }
        sending = decision
        let note = spoken?.text
        Task {
            _ = await session.reply(to: item, decision, note: note)
            sending = nil
        }
    }
}

/// A question form, one field per page: the question, "Select one" or "Select any", the options,
/// "Say your own answer", and Dismiss / Back / Next or Submit. Any other form is answered on the
/// computer, with Dismiss only.
private struct QuestionSheet: View {
    let item: PendingItem
    let session: TodaySession
    let home: HomeModel

    @State private var form: QuestionForm
    @State private var sending: Action?
    @State private var spoken: SpokenAnswer?

    private enum Action { case submit, dismiss }

    init(item: PendingItem, form: PendingFormV2, session: TodaySession, home: HomeModel) {
        self.item = item
        self.session = session
        self.home = home
        _form = State(initialValue: QuestionForm(form))
    }

    var body: some View {
        HexSheet(
            request: .init(
                title: Text(verbatim: form.isSupported ? form.title : (form.form.title ?? form.title)),
                count: form.isSupported && form.pageCount > 1
                    ? String(localized: "needs.count", defaultValue: "\(form.page + 1) of \(form.pageCount)")
                    : nil,
                hideLabel: NeedsSheet.hideLabel,
                onHide: { session.hideNeed() }
            )
        ) {
            VStack(alignment: .leading, spacing: 0) {
                if form.isSupported {
                    page
                } else {
                    Text(LocalizedStringResource("needs.question.unsupported", defaultValue: "Answer this one on your computer"))
                        .hexFont(.body)
                        .foregroundStyle(HexColor.text2)
                }
                footer
                    .padding(.top, 16)
            }
            .padding(.bottom, 12)
        }
        .onAppear { if spoken == nil { spoken = SpokenAnswer(home: home) } }
        .onDisappear { Task { await spoken?.close() } }
    }

    @ViewBuilder
    private var page: some View {
        let field = form.field
        if let question = field.description ?? field.title, !question.isEmpty {
            Text(verbatim: question)
                .hexFont(.body)
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 4)
        }
        Text(form.isMultiple
            ? LocalizedStringResource("needs.question.select_any", defaultValue: "Select any")
            : LocalizedStringResource("needs.question.select_one", defaultValue: "Select one"))
            .hexFont(.caption)
            .foregroundStyle(HexColor.text2)
            .padding(.bottom, 12)
        VStack(spacing: 8) {
            ForEach(field.options, id: \.value) { option in
                HexOption(
                    title: option.label,
                    detail: option.description,
                    mark: form.isMultiple ? .checkbox : .radio,
                    selected: form.answer.chosen.contains(option.value),
                    enabled: sending == nil && !(spoken?.isBusy ?? false)
                ) { form.choose(option.value) }
            }
            if field.custom, let spoken {
                HexOption(
                    title: String(localized: "needs.question.own", defaultValue: "Say your own answer"),
                    detail: form.answer.own.isEmpty ? nil : form.answer.own,
                    mark: form.isMultiple ? .checkbox : .radio,
                    selected: form.answer.ownChosen,
                    enabled: sending == nil,
                    live: NeedsSheet.live(spoken.state),
                    press: HexPress(
                        began: { spoken.pressBegan() },
                        ended: {
                            Task {
                                await spoken.pressEnded(onText: { form.setOwn($0) }, onTap: { form.toggleOwn() })
                            }
                        }
                    )
                )
            }
        }
        .animation(HexMotion.ease(HexMotion.press), value: form)
    }

    private var footer: some View {
        let busy = sending != nil || (spoken?.isBusy ?? false)
        return HStack(spacing: 8) {
            HexButton(
                LocalizedStringResource("needs.question.dismiss", defaultValue: "Dismiss"),
                size: .regular,
                enabled: !busy || sending == .dismiss,
                loading: sending == .dismiss
            ) { send(.dismiss) }
            Spacer(minLength: 0)
            if form.isSupported {
                if form.page > 0 {
                    HexButton(LocalizedStringResource("common.back", defaultValue: "Back"), size: .regular, enabled: !busy) {
                        form.back()
                    }
                }
                if form.isLastPage {
                    HexButton(
                        LocalizedStringResource("needs.question.submit", defaultValue: "Submit"),
                        size: .regular,
                        style: .primary,
                        enabled: form.hasAnswer && (!busy || sending == .submit),
                        loading: sending == .submit
                    ) { send(.submit) }
                } else {
                    HexButton(
                        LocalizedStringResource("needs.question.next", defaultValue: "Next"),
                        size: .regular,
                        style: .primary,
                        enabled: form.hasAnswer && !busy
                    ) { form.next() }
                }
            }
        }
    }

    private func send(_ action: Action) {
        guard sending == nil else { return }
        sending = action
        let answer = form.answerBody
        Task {
            switch action {
            case .submit: _ = await session.submit(item, answer: answer)
            case .dismiss: _ = await session.dismiss(item)
            }
            sending = nil
        }
    }
}
