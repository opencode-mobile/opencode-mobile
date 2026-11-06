import SwiftUI

/// Details (app plan, "Details"): Needs you, Waiting to send, Running now, the to-do list and
/// Recent, each left out when empty, updating live while open. Opened from "Details ›", the to-do
/// row and the waiting line.
struct DetailsSheet: View {
    let session: TodaySession
    let home: HomeModel
    let onDone: () -> Void

    @State private var cancelling: Set<String> = []

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("details.title", defaultValue: "Details")),
            leading: { EmptyView() },
            trailing: {
                HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), action: onDone)
            }
        ) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let running = session.running(at: context.date)
                let sections = DetailsSection.shown(.init(
                    needs: session.pendingItems.count,
                    waiting: session.undeliveredMessages.count,
                    running: running.total,
                    todo: session.todos.count,
                    recent: session.recentSteps.count
                ))
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if sections.isEmpty {
                            Text(LocalizedStringResource("details.empty", defaultValue: "Nothing running right now."))
                                .hexFont(.footnote)
                                .foregroundStyle(HexColor.text3)
                                .padding(.horizontal, 2)
                        }
                        ForEach(sections, id: \.self) { section in
                            view(section, running: running, at: context.date)
                        }
                    }
                    .padding(.bottom, 24)
                    .animation(HexMotion.ease(HexMotion.fade), value: sections)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .hexToastHost(home.toasts, sheet: true)
    }

    @ViewBuilder
    private func view(_ section: DetailsSection, running: (rows: [RunningRow], more: Int, total: Int), at date: Date) -> some View {
        switch section {
        case .needs: needs
        case .waiting: waiting
        case .asked: EmptyView()
        case .running: runningNow(running, at: date)
        case .todo: todo
        case .recent: recent(at: date)
        }
    }

    // MARK: Needs you

    private var needs: some View {
        HexDetailsSection(title: Text(LocalizedStringResource("details.needs", defaultValue: "Needs you"))) {
            ForEach(session.pendingItems) { item in
                Button { session.openFromDetails(item.id) } label: {
                    HexDetailRow(
                        leading: .icon(.alert, HexColor.amber),
                        title: Text(item.kind == .approval
                            ? LocalizedStringResource("details.needs.approval", defaultValue: "Approval needed")
                            : LocalizedStringResource("details.needs.questions", defaultValue: "Questions from the agent")),
                        detail: Text(verbatim: item.text)
                    ) { HexDetailChevron() }
                }
                .buttonStyle(HexRowPressStyle())
            }
        }
    }

    // MARK: Waiting to send

    private var waiting: some View {
        HexDetailsSection(
            title: Text(LocalizedStringResource("details.waiting", defaultValue: "Waiting to send")),
            count: "\(session.undeliveredMessages.count)"
        ) {
            ForEach(session.undeliveredMessages) { message in
                HStack(alignment: .center, spacing: 10) {
                    HexIconView(.send, size: 14)
                        .foregroundStyle(HexColor.text3)
                        .frame(width: 16)
                    VStack(alignment: .leading, spacing: 4) {
                        if !message.text.isEmpty {
                            Text(verbatim: message.text)
                                .hexFont(HexTextStyle.row.weight(.medium))
                                .foregroundStyle(HexColor.text1)
                                .lineLimit(2)
                        }
                        WaitingImages(thumbnails: session.waitingThumbnails[message.id], count: message.imageCount)
                        Text(message.delivery == "queue"
                            ? LocalizedStringResource("details.waiting.queue", defaultValue: "Queue · after this turn")
                            : LocalizedStringResource("details.waiting.steer", defaultValue: "Steer · lands at the next step"))
                            .hexFont(.footnote)
                            .foregroundStyle(HexColor.text2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    HexButton(
                        LocalizedStringResource("home.waiting.cancel", defaultValue: "Cancel"),
                        loading: cancelling.contains(message.id)
                    ) { cancel(message.id) }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .frame(minHeight: 40)
                .transition(.opacity)
            }
        }
    }

    private func cancel(_ id: String) {
        cancelling.insert(id)
        Task {
            try? await session.cancelWaiting(id)
            cancelling.remove(id)
        }
    }

    // MARK: Running now

    private func runningNow(_ running: (rows: [RunningRow], more: Int, total: Int), at date: Date) -> some View {
        HexDetailsSection(
            title: Text(LocalizedStringResource("details.running", defaultValue: "Running now")),
            count: "\(running.total)"
        ) {
            ForEach(running.rows) { row in
                switch row.kind {
                case let .subagent(agent):
                    HexDetailRow(
                        leading: .dot(.green),
                        title: Text(verbatim: row.title),
                        detail: Text(verbatim: String(localized: "details.running.subagent", defaultValue: "Subagent · \(agent)"))
                    ) { HexDetailTime(text: AgentState.clock(date.timeIntervalSince(row.since))) }
                case .shell:
                    HexDetailRow(
                        leading: .dot(.neutral),
                        title: Text(verbatim: row.title),
                        code: true,
                        detail: Text(LocalizedStringResource("details.running.shell", defaultValue: "Shell command"))
                    ) { HexDetailTime(text: AgentState.clock(date.timeIntervalSince(row.since))) }
                }
            }
            if running.more > 0 {
                HexDetailRow(title: Text(verbatim: String(localized: "details.more", defaultValue: "+\(running.more) more")), done: true)
            }
        }
    }

    // MARK: To-do list

    private var todo: some View {
        let items = session.todos
        let done = items.filter { $0.status == .completed }.count
        return HexDetailsSection(
            title: Text(LocalizedStringResource("details.todo", defaultValue: "To-do list")),
            suffix: Text(LocalizedStringResource("details.todo.suffix", defaultValue: "· from the agent")),
            count: "\(done)/\(items.count)"
        ) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                HexDetailRow(
                    leading: .todo(item.status == .completed ? .todoDone : item.status == .inProgress ? .todoDoing : .todoPending),
                    title: SummaryText.text(item.content, style: HexTextStyle.row.weight(.medium), scale: scale),
                    wraps: true,
                    done: item.status == .completed
                )
                .hexInlineCode()
                .accessibilityLabel(Text(verbatim: InlineMarkdown.plain(item.content)))
            }
        }
    }

    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 100

    // MARK: Recent

    private func recent(at date: Date) -> some View {
        HexDetailsSection(title: Text(LocalizedStringResource("details.recent", defaultValue: "Recent"))) {
            ForEach(session.recentSteps) { step in
                HexDetailRow(
                    leading: .icon(icon(step.kind), HexColor.text3),
                    title: Text(verbatim: step.title),
                    code: step.titleIsCode,
                    detail: step.detail.map { Text(verbatim: $0) }
                ) {
                    if let at = step.at {
                        HexDetailTime(text: RecentSteps.age(of: at, at: date))
                    }
                }
            }
        }
    }

    private func icon(_ kind: RecentStep.Kind) -> HexIcon {
        switch kind {
        case .edit: .pencil
        case .command: .term
        case .subagent: .spark
        }
    }
}

/// A waiting message's images: the phone's own thumbnails (24 pt, at most 4), or for one sent
/// from elsewhere their count.
struct WaitingImages: View {
    let thumbnails: [UIImage]?
    let count: Int

    var body: some View {
        if let thumbnails, !thumbnails.isEmpty {
            HStack(spacing: 6) {
                ForEach(thumbnails.indices.prefix(DictationDraft.maxImages), id: \.self) { index in
                    HexThumbnail(image: thumbnails[index], size: .small)
                }
            }
        } else if count > 0 {
            Text(verbatim: HomeModel.imageCountText(count))
                .hexFont(.caption)
                .foregroundStyle(HexColor.text3)
        }
    }
}
