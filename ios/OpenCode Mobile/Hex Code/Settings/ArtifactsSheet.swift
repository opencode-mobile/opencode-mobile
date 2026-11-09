import OpenCodeKit
import SwiftUI

/// The enable and turn-off sheet (app plan, "Artifacts (M8)", variant A): one `HexSheet` that
/// explains, names the file, shows the exact prompt and where it goes, then turns into a step list:
/// Sent → Agent working → Approval → Checking → On. The approval takes its place for a moment;
/// "See what the agent says" opens the setup session's replies. Hiding it keeps the flow running.
struct ArtifactsSheet: View {
    let artifacts: ArtifactsModel
    let toasts: HexToaster

    var body: some View {
        Group {
            if let flow = artifacts.flow {
                if flow.showsApproval, let item = flow.pendingApproval, let request = item.approval {
                    ArtifactsApprovalView(artifacts: artifacts, request: request, path: flow.target?.path)
                } else {
                    ArtifactsFlowView(artifacts: artifacts, flow: flow)
                }
            }
        }
        .hexToastHost(toasts, sheet: true)
        .hexSheet(isPresented: Binding(get: { artifacts.readerShown }, set: { artifacts.readerShown = $0 })) {
            ArtifactsRepliesView(messages: artifacts.transcript) { artifacts.readerShown = false }
        }
    }
}

/// The steps, the explanation before them, and the result after.
private struct ArtifactsFlowView: View {
    let artifacts: ArtifactsModel
    let flow: ArtifactsFlow

    private var on: Bool { flow.mode == .enable }
    private var name: String { artifacts.serverName() }

    var body: some View {
        HexSheet(
            request: .init(
                title: Text(verbatim: title),
                count: nil,
                hideLabel: Text(LocalizedStringResource("needs.hide", defaultValue: "Hide")),
                onHide: { artifacts.close() }
            )
        ) {
            VStack(alignment: .leading, spacing: 10) {
                switch flow.stage {
                case .preparing:
                    HStack {
                        Spacer()
                        HexSpinner(size: 22)
                        Spacer()
                    }
                    .padding(.vertical, 40)
                case .explain:
                    explain
                case let .cantSend(reason):
                    cantSend(reason)
                case .running, .done, .fallback, .broken, .restoring:
                    steps
                }
            }
            .padding(.bottom, 14)
        }
    }

    private var title: String {
        switch flow.stage {
        case .running, .restoring:
            on
                ? String(localized: "artifacts.flow.turning_on", defaultValue: "Turning on artifacts…")
                : String(localized: "artifacts.flow.turning_off", defaultValue: "Turning off artifacts…")
        default:
            on
                ? String(localized: "artifacts.sheet.turn_on", defaultValue: "Turn on artifacts")
                : String(localized: "artifacts.sheet.turn_off", defaultValue: "Turn off artifacts")
        }
    }

    // MARK: Before sending

    @ViewBuilder
    private var explain: some View {
        if on {
            HStack(alignment: .top, spacing: 12) {
                HexLinkTile(
                    icon: .compass,
                    label: String(localized: "artifacts.sheet.example", defaultValue: "Login page preview"),
                    detail: String(localized: "home.tile.artifact", defaultValue: "Artifact"),
                    detailIcon: .compass
                ) {}
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                SummaryText(
                    text: String(localized: "artifacts.sheet.intro", defaultValue: "Artifacts are small pages the agent publishes, like a mock-up or a chart. The newest shows on the card. They’re off on \(name), so Hex Code asks the agent to turn them on."),
                    style: .caption
                )
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
            }
        } else {
            Text(LocalizedStringResource("artifacts.sheet.intro_off", defaultValue: "Hex Code asks the agent to take the flag out again. The agent then can’t make pages, and the card shows no artifact tile. Pages it made stay on the computer."))
                .hexFont(.body)
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
        }
        if let target = flow.target {
            willEdit(target)
        }
        if let prompt = flow.prompt {
            promptBox(prompt)
        }
        whereItGoes
        HStack(spacing: 8) {
            HexButton(LocalizedStringResource("common.cancel", defaultValue: "Cancel"), size: .regular) { artifacts.close() }
            Spacer(minLength: 0)
            HexButton(LocalizedStringResource("artifacts.sheet.send", defaultValue: "Send to the agent"), size: .regular, style: .primary) {
                Task { await artifacts.send() }
            }
        }
        .padding(.top, 4)
    }

    private func willEdit(_ target: ConfigTarget.TargetFile) -> some View {
        let note: String = if !target.isGlobal {
            String(localized: "artifacts.sheet.will_edit_project", defaultValue: "Its own “experimental” block overrides the global file, so the change goes here.")
        } else if target.hasExperimental {
            String(localized: "artifacts.sheet.will_edit_global_block", defaultValue: "The global file: one change for every project, nothing in the repo.")
        } else {
            String(localized: "artifacts.sheet.will_edit_global", defaultValue: "No file sets “experimental” yet: one change for every project, nothing in the repo.")
        }
        return VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringResource("artifacts.sheet.will_edit", defaultValue: "Will edit"))
                .hexFont(.mono)
                .foregroundStyle(HexColor.text3)
            Text(verbatim: target.path)
                .hexFont(.mono)
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            Text(verbatim: note)
                .hexFont(.footnote)
                .foregroundStyle(HexColor.text3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(HexColor.panel2, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous).strokeBorder(HexColor.line, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private func promptBox(_ prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(LocalizedStringResource("artifacts.sheet.prompt", defaultValue: "Sent to the agent, as you"))
                    .foregroundStyle(HexColor.text2)
                Spacer(minLength: 8)
                Text(LocalizedStringResource("artifacts.sheet.prompt_exact", defaultValue: "exact text"))
                    .foregroundStyle(HexColor.text3)
            }
            .hexFont(.mono)
            SummaryText(text: prompt, style: .caption)
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 13)
        .padding(.vertical, 11)
        .background(HexColor.background, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous).strokeBorder(HexColor.line, lineWidth: 1))
    }

    private var whereItGoes: some View {
        HStack(spacing: 11) {
            HexCodeMark()
                .frame(width: 14, height: 14)
                .colorMultiply(HexColor.text1)
                .frame(width: 26, height: 26)
                .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(HexColor.line2, lineWidth: 1))
            VStack(alignment: .leading, spacing: 1) {
                Text(LocalizedStringResource("artifacts.sheet.where", defaultValue: "Goes to Hex Code setup"))
                    .hexFont(.callout)
                    .foregroundStyle(HexColor.text1)
                Text(LocalizedStringResource("artifacts.sheet.where_sub", defaultValue: "A separate session · today’s card and messages aren’t touched"))
                    .hexFont(.footnote)
                    .foregroundStyle(HexColor.text3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(artifacts.setupSessionID == nil
                ? LocalizedStringResource("artifacts.sheet.new", defaultValue: "New")
                : LocalizedStringResource("artifacts.sheet.reused", defaultValue: "Reused"))
                .hexFont(.badge)
                .foregroundStyle(artifacts.setupSessionID == nil ? HexColor.green : HexColor.text3)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(HexColor.panel, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous).strokeBorder(HexColor.line, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func cantSend(_ reason: ArtifactsFlow.CantSend) -> some View {
        let file = flow.target?.path ?? ""
        let text: String = switch reason {
        case .environment:
            String(localized: "artifacts.sheet.env", defaultValue: "OpenCode reads this from OPENCODE_CONFIG_CONTENT; restart it after the change.")
        case .alreadyBroken:
            String(localized: "artifacts.sheet.broken", defaultValue: "OpenCode can’t read \(file) right now, so Hex Code won’t ask the agent to change it. Fix it on the computer, or add the line yourself.")
        case .unreadable:
            String(localized: "artifacts.sheet.no_config", defaultValue: "Hex Code couldn’t read OpenCode’s config on \(name).")
        }
        Text(verbatim: text)
            .hexFont(.body)
            .foregroundStyle(HexColor.text2)
            .fixedSize(horizontal: false, vertical: true)
        fallbackLine
        HStack {
            Spacer(minLength: 0)
            HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), size: .regular, style: .primary) { artifacts.close() }
        }
        .padding(.top, 4)
    }

    // MARK: The steps

    @ViewBuilder
    private var steps: some View {
        HStack(spacing: 6) {
            HexCodeMark()
                .frame(width: 12, height: 12)
                .colorMultiply(HexColor.text3)
            Text(LocalizedStringResource("artifacts.flow.sub", defaultValue: "In Hex Code setup · today’s session isn’t touched"))
                .hexFont(.caption)
                .foregroundStyle(HexColor.text3)
        }
        .padding(.top, -6)
        HexSteps(steps: stepList)
        if artifacts.setupSessionID != nil {
            Button { artifacts.showReplies() } label: {
                HStack(spacing: 4) {
                    Text(LocalizedStringResource("artifacts.flow.replies", defaultValue: "See what the agent says"))
                        .hexFont(.small)
                        .foregroundStyle(HexColor.text2)
                    HexIconView(.chevR, size: 12)
                        .foregroundStyle(HexColor.text3)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(HexPressStyle())
            .padding(.leading, 30)
        }
        result
    }

    private var stepList: [HexStep] {
        var list: [HexStep] = []
        list.append(HexStep(
            id: "sent",
            state: Self.state(flow.sent),
            title: String(localized: "artifacts.flow.sent", defaultValue: "Sent to the agent"),
            detail: String(localized: "artifacts.flow.sent_sub", defaultValue: "Hex Code setup · a separate session")
        ))
        let workTitle: String = switch flow.work {
        case .ok: String(localized: "artifacts.flow.changed", defaultValue: "The agent changed the config")
        case .fail: String(localized: "artifacts.flow.not_changed", defaultValue: "The agent didn’t change it")
        default: String(localized: "artifacts.flow.working", defaultValue: "Agent is working")
        }
        let working = flow.work == .doing || flow.work == .dimmed
        list.append(HexStep(
            id: "work",
            state: Self.state(flow.work),
            title: workTitle,
            detail: working ? liveText : flow.target?.path,
            live: working
        ))
        if let approval = flow.approval {
            let approvalTitle: String = switch approval {
            case .ok: String(localized: "artifacts.flow.allowed", defaultValue: "You allowed the edit")
            case .fail: String(localized: "artifacts.flow.rejected", defaultValue: "You rejected the edit")
            default: String(localized: "artifacts.flow.approval", defaultValue: "Approval needed")
            }
            let fileName = flow.target?.name ?? ""
            list.append(HexStep(
                id: "approval",
                state: Self.state(approval),
                title: approvalTitle,
                detail: String(localized: "artifacts.approval.title", defaultValue: "Edit \(fileName)"),
                action: flow.pendingApproval != nil
                    ? (String(localized: "artifacts.flow.review", defaultValue: "Review"), { artifacts.showApproval() })
                    : nil
            ))
        }
        let checkTitle: String = switch flow.check {
        case .ok: String(localized: "artifacts.flow.answers", defaultValue: "\(name) answers")
        case .warn:
            on
                ? String(localized: "artifacts.flow.still_disabled", defaultValue: "\(name) still answers “disabled”")
                : String(localized: "artifacts.flow.still_on", defaultValue: "\(name) still serves artifacts")
        default: String(localized: "artifacts.flow.checking", defaultValue: "Checking \(name)")
        }
        let checkDetail: String? = switch flow.check {
        case .doing: String(localized: "artifacts.flow.checking_every", defaultValue: "Every 2 s")
        case .todo: String(localized: "artifacts.flow.checking_sub", defaultValue: "Asks the artifact route whether it answers")
        default: nil
        }
        list.append(HexStep(id: "check", state: Self.state(flow.check), title: checkTitle, detail: checkDetail, live: flow.check == .doing))
        list.append(HexStep(
            id: "end",
            state: Self.state(flow.end),
            title: on
                ? String(localized: "artifacts.sheet.now_on", defaultValue: "Artifacts are on")
                : String(localized: "artifacts.flow.now_off", defaultValue: "Artifacts are off")
        ))
        return list
    }

    private var liveText: String {
        if flow.pendingApproval != nil {
            return String(localized: "artifacts.flow.waiting_approval", defaultValue: "Waiting for your approval")
        }
        switch flow.liveLine ?? .waiting {
        case .waiting: return String(localized: "artifacts.flow.waiting", defaultValue: "Sent · waiting for the agent")
        case .thinking: return String(localized: "artifacts.flow.thinking", defaultValue: "Thinking…")
        case let .reading(file): return String(localized: "artifacts.flow.reading", defaultValue: "Reading \(file)")
        case let .editing(file): return String(localized: "artifacts.flow.editing", defaultValue: "Editing \(file)")
        case let .tool(tool): return String(localized: "artifacts.flow.tool", defaultValue: "Using \(tool)")
        }
    }

    static func state(_ step: ArtifactsFlow.Step) -> HexStepState {
        switch step {
        case .todo: .todo
        case .doing: .doing
        case .dimmed: .dimmed
        case .ok: .done
        case .warn: .warn
        case .fail: .failed
        }
    }

    // MARK: The result

    @ViewBuilder
    private var result: some View {
        switch flow.stage {
        case .done:
            resultHead(icon: .check, tint: HexColor.green, title: on
                ? String(localized: "artifacts.sheet.now_on", defaultValue: "Artifacts are on")
                : String(localized: "artifacts.flow.now_off", defaultValue: "Artifacts are off"))
            Text(on
                ? LocalizedStringResource("artifacts.flow.on_body", defaultValue: "Pages the agent makes now show as the first tile on the card.")
                : LocalizedStringResource("artifacts.flow.off_body", defaultValue: "The agent can’t make pages now. The ones it made stay on the computer."))
                .hexFont(.body)
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
            HStack(alignment: .top, spacing: 7) {
                HexIconView(.spark, size: 13)
                    .foregroundStyle(HexColor.green)
                    .padding(.top, 2)
                Text(on
                    ? LocalizedStringResource("artifacts.flow.on_rule", defaultValue: "Hex Code will ask the agent to publish pages as artifacts (a short rule for today’s session).")
                    : LocalizedStringResource("artifacts.flow.off_rule", defaultValue: "Hex Code removed its artifact rule from today’s session."))
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if on { suggestion }
            HStack {
                Spacer(minLength: 0)
                HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), size: .regular, style: .primary) { artifacts.close() }
            }
            .padding(.top, 4)
        case let .fallback(reason):
            fallback(reason)
        case let .broken(problem):
            broken(problem, restoring: false)
        case .restoring:
            broken(.broken, restoring: true)
        default:
            EmptyView()
        }
    }

    private func resultHead(icon: HexIcon, tint: Color, title: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HexIconView(icon, size: 18)
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .background(tint.opacity(0.13), in: Circle())
            Text(verbatim: title)
                .hexFont(.pageTitle)
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.top, 6)
    }

    private var suggestion: some View {
        Button { artifacts.suggestPage() } label: {
            HStack(spacing: 11) {
                HexIconView(.mic, size: 14)
                    .foregroundStyle(HexColor.onSolid)
                    .frame(width: 32, height: 32)
                    .background(HexColor.solid, in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: "“" + String(localized: "artifacts.flow.on_suggestion", defaultValue: "Make a small page that previews") + " …”")
                        .hexFont(.callout)
                        .foregroundStyle(HexColor.text1)
                    Text(LocalizedStringResource("artifacts.flow.on_suggestion_sub", defaultValue: "Tap to put it in today’s draft, or just say it"))
                        .hexFont(.footnote)
                        .foregroundStyle(HexColor.text3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                HexIconView(.chevR, size: 14)
                    .foregroundStyle(HexColor.text3)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .background(HexColor.panel2, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous).strokeBorder(HexColor.line2, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(HexPressStyle())
        .padding(.top, 4)
    }

    @ViewBuilder
    private func fallback(_ reason: ArtifactsFlow.Fallback) -> some View {
        let (icon, tint, title, body) = Self.fallbackText(reason, on: on, name: name, file: flow.target?.name ?? "")
        resultHead(icon: icon, tint: tint, title: title)
        if let body {
            SummaryText(text: body, style: .body)
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
        }
        if flow.offersLine { fallbackLine }
        HStack(spacing: 8) {
            HexButton(LocalizedStringResource("common.close", defaultValue: "Close"), size: .regular) { artifacts.close() }
            Spacer(minLength: 0)
            HexButton(LocalizedStringResource("artifacts.flow.try_again", defaultValue: "Try again"), icon: .refresh, size: .regular, style: .primary) {
                Task { await artifacts.tryAgain() }
            }
        }
        .padding(.top, 4)
    }

    static func fallbackText(_ reason: ArtifactsFlow.Fallback, on: Bool, name: String, file: String) -> (HexIcon, Color, String, String?) {
        switch reason {
        case .rejected:
            (.x, HexColor.text2,
             String(localized: "artifacts.flow.rejected_title", defaultValue: "Nothing changed"),
             String(localized: "artifacts.flow.rejected_body", defaultValue: "You rejected the edit, so the file is as it was."))
        case let .failed(reason):
            (.x, HexColor.red,
             String(localized: "artifacts.flow.not_changed", defaultValue: "The agent didn’t change it"),
             String(localized: "artifacts.flow.failed_body", defaultValue: "The agent said: \(reason)"))
        case let .freeLimit(message), let .couldntRun(message):
            (.alert, HexColor.amber,
             String(localized: "artifacts.flow.couldnt_run", defaultValue: "The agent couldn’t run"),
             String(localized: "artifacts.flow.couldnt_run_body", defaultValue: "\(message)"))
        case .timedOut:
            (.alert, HexColor.amber,
             String(localized: "artifacts.flow.timed_out", defaultValue: "No answer in time"),
             String(localized: "artifacts.flow.timed_out_body", defaultValue: "The agent didn’t finish within a minute."))
        case .stillOff:
            on
                ? (.alert, HexColor.amber,
                   String(localized: "artifacts.flow.still_off", defaultValue: "Still off on \(name)"),
                   String(localized: "artifacts.flow.still_off_body", defaultValue: "OpenCode still answers “Artifacts are disabled”. The agent may not have changed the file, or the flag comes from somewhere else."))
                : (.alert, HexColor.amber,
                   String(localized: "artifacts.flow.still_on_title", defaultValue: "Still on on \(name)"),
                   String(localized: "artifacts.flow.still_on", defaultValue: "\(name) still serves artifacts"))
        case .restored:
            (.undo, HexColor.text2,
             String(localized: "artifacts.flow.restored", defaultValue: "Restored"),
             String(localized: "artifacts.flow.restored_body", defaultValue: "\(file) is back exactly as it was before the edit."))
        }
    }

    /// The line to copy, the file, Copy; the check keeps going, so a hand edit flips it.
    @ViewBuilder
    private var fallbackLine: some View {
        if let target = flow.target {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: on
                    ? String(localized: "artifacts.flow.fallback", defaultValue: "Or add it yourself on \(name), in \(target.path):")
                    : String(localized: "artifacts.flow.fallback_off", defaultValue: "Or remove it yourself on \(name), in \(target.path):"))
                    .hexFont(.small)
                    .foregroundStyle(HexColor.text2)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(alignment: .top, spacing: 8) {
                    HexCode(lines: [HexCodeLine(on ? target.line : #""artifacts": true"#)])
                    HexButton(LocalizedStringResource("artifacts.sheet.copy", defaultValue: "Copy"), icon: .copy) { artifacts.copyLine() }
                        .fixedSize()
                }
                if on {
                    HStack(spacing: 7) {
                        HexSpinner(size: 13)
                        Text(LocalizedStringResource("artifacts.flow.fallback_watching", defaultValue: "Hex Code keeps checking and turns to On by itself."))
                            .hexFont(.footnote)
                            .foregroundStyle(HexColor.text3)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.top, 6)
        }
    }

    @ViewBuilder
    private func broken(_ problem: ConfigCheck.Result, restoring: Bool) -> some View {
        let file = flow.target?.path ?? ""
        resultHead(icon: .alert, tint: HexColor.red, title: String(localized: "artifacts.broken.title", defaultValue: "Your OpenCode config changed unexpectedly"))
        Group {
            switch problem {
            case let .changed(keys):
                let list = keys.joined(separator: ", ")
                Text(verbatim: String(localized: "artifacts.broken.missing", defaultValue: "Missing or changed in \(file): \(list)"))
            default:
                Text(verbatim: String(localized: "artifacts.broken.unreadable", defaultValue: "The edit broke \(file), so OpenCode is ignoring it, with your providers and settings."))
            }
        }
        .hexFont(.body)
        .foregroundStyle(HexColor.text2)
        .fixedSize(horizontal: false, vertical: true)
        if artifacts.canRestore {
            Text(LocalizedStringResource("artifacts.restore_body", defaultValue: "Restore writes back the text Hex Code saved before the edit, byte for byte, and checks again."))
                .hexFont(.caption)
                .foregroundStyle(HexColor.text3)
                .fixedSize(horizontal: false, vertical: true)
        }
        HStack(spacing: 8) {
            HexButton(LocalizedStringResource("common.close", defaultValue: "Close"), size: .regular, enabled: !restoring) { artifacts.close() }
            Spacer(minLength: 0)
            if artifacts.canRestore {
                HexButton(LocalizedStringResource("artifacts.restore", defaultValue: "Restore"), icon: .undo, size: .regular, style: .primary, loading: restoring) {
                    Task { await artifacts.restore() }
                }
            }
        }
        .padding(.top, 4)
    }
}

/// The setup session's approval: the diff of the edit Hex Code's prompt asked for, with Allow once
/// and Reject only ("Always" would allow every edit in the project).
private struct ArtifactsApprovalView: View {
    let artifacts: ArtifactsModel
    let request: PendingPermissionV2
    let path: String?

    @State private var sending: Bool?

    var body: some View {
        let file = request.files.first?.file ?? request.resources.first ?? path ?? ""
        // The project's own file comes as a relative path; the app knows the absolute one.
        let shown = (file.hasPrefix("/") ? file : path) ?? file
        let fileName = (shown as NSString).lastPathComponent
        HexSheet(
            request: .init(
                title: Text(verbatim: String(localized: "artifacts.approval.title", defaultValue: "Edit \(fileName)")),
                count: String(localized: "artifacts.flow.setup", defaultValue: "Hex Code setup"),
                hideLabel: Text(LocalizedStringResource("needs.hide", defaultValue: "Hide")),
                onHide: { artifacts.hideApproval() }
            ),
            amberEdge: true
        ) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: shown)
                        .hexFont(.mono)
                        .foregroundStyle(HexColor.text2)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    if let counts {
                        Text(verbatim: counts)
                            .hexFont(.mono)
                            .foregroundStyle(HexColor.text3)
                    }
                }
                let lines = ApprovalContent.code(request).filter { $0.kind != .plain || request.files.isEmpty }
                if !lines.isEmpty {
                    HexCode(lines: lines)
                }
                Text(LocalizedStringResource("artifacts.approval.from", defaultValue: "From the separate Hex Code setup session: the edit Hex Code’s prompt asked for. Today’s session isn’t waiting on this."))
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text3)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    HexButton(
                        LocalizedStringResource("artifacts.approval.reject", defaultValue: "Reject"),
                        size: .regular,
                        style: .danger,
                        enabled: sending == nil || sending == false,
                        loading: sending == false
                    ) { answer(false) }
                    Spacer(minLength: 0)
                    HexButton(
                        LocalizedStringResource("needs.approval.once", defaultValue: "Allow once"),
                        size: .regular,
                        style: .primary,
                        enabled: sending == nil || sending == true,
                        loading: sending == true
                    ) { answer(true) }
                }
                .padding(.top, 6)
            }
            .padding(.bottom, 14)
        }
    }

    /// `+1 −1` from the diff.
    private var counts: String? {
        guard let file = request.files.first, let added = file.additions, let removed = file.deletions else { return nil }
        return "+\(added) −\(removed)"
    }

    private func answer(_ allow: Bool) {
        guard sending == nil else { return }
        sending = allow
        Task {
            await artifacts.answerApproval(allow: allow)
            sending = nil
        }
    }
}

/// "See what the agent says": the setup session's messages, as a content sheet.
private struct ArtifactsRepliesView: View {
    let messages: [SetupMessage]
    let onDone: () -> Void

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("artifacts.flow.setup", defaultValue: "Hex Code setup")),
            leading: { EmptyView() },
            trailing: { HexButton(LocalizedStringResource("common.done", defaultValue: "Done"), action: onDone) }
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if messages.isEmpty {
                        Text(LocalizedStringResource("artifacts.flow.replies_none", defaultValue: "The agent hasn’t said anything yet."))
                            .hexFont(.body)
                            .foregroundStyle(HexColor.text3)
                    }
                    ForEach(messages) { message in
                        row(message)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.bottom, 20)
            }
        }
    }

    @ViewBuilder
    private func row(_ message: SetupMessage) -> some View {
        switch message.kind {
        case .you:
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringResource("artifacts.sheet.prompt", defaultValue: "Sent to the agent, as you"))
                    .hexFont(.mono)
                    .foregroundStyle(HexColor.text3)
                SummaryText(text: message.text, style: .caption)
                    .foregroundStyle(HexColor.text2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        case .agent:
            VStack(alignment: .leading, spacing: 4) {
                Text(LocalizedStringResource("artifacts.flow.agent", defaultValue: "Agent"))
                    .hexFont(.monoSmall)
                    .foregroundStyle(HexColor.text3)
                SummaryText(text: message.text, style: .body)
                    .foregroundStyle(HexColor.text1)
                    .fixedSize(horizontal: false, vertical: true)
            }
        case .tool:
            HStack(spacing: 8) {
                HexIconView(.pencil, size: 12)
                    .foregroundStyle(HexColor.text3)
                Text(verbatim: message.text)
                    .hexFont(.mono)
                    .foregroundStyle(HexColor.text2)
                    .lineLimit(2)
                    .truncationMode(.middle)
            }
        case .error:
            Text(verbatim: message.text)
                .hexFont(.body)
                .foregroundStyle(HexColor.red)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
