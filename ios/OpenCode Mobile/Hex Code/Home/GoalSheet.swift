import SwiftUI

/// "Today's goal" (app plan, "The goal sheet"): the goal box, the fine print, and the buttons for
/// the goal's state. "Say the goal", Change and "New goal" turn the box into a take: hold to
/// dictate, as Say why in M4; then Set goal (or Replace goal), Again, or Cancel.
struct GoalSheet: View {
    let session: TodaySession
    let home: HomeModel

    @State private var spoken: SpokenAnswer?
    /// A take has text: the buttons are Cancel, Again and Set. While the first take is held the
    /// buttons stay as they were, so the button being held stays on screen until it's let go.
    @State private var taking = false
    @State private var sending: Action?

    private enum Action { case set, pause, resume, clear }

    var body: some View {
        HexSheet(
            title: Text(LocalizedStringResource("goal.sheet.title", defaultValue: "Today’s goal")),
            leading: { EmptyView() },
            trailing: {
                HexButton(LocalizedStringResource("common.done", defaultValue: "Done")) { session.showsGoalSheet = false }
            }
        ) {
            VStack(alignment: .leading, spacing: 0) {
                box
                    .padding(.bottom, 8)
                Text(LocalizedStringResource(
                    "goal.fine",
                    defaultValue: "With a goal the agent keeps going after each turn until a reviewer decides it’s done or blocked (OpenCode /goal, up to 50 rounds). The app sets it; the agent can’t set its own. Stop pauses it."
                ))
                .hexFont(.footnote)
                .foregroundStyle(HexColor.text3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 2)
                buttons
                    .padding(.top, 12)
            }
            .padding(.bottom, 16)
            .hexAnimation(HexMotion.ease(HexMotion.fade), value: taking)
        }
        .hexToastHost(home.toasts, sheet: true)
        .onAppear { if spoken == nil { spoken = SpokenAnswer(home: home) } }
        .onDisappear { Task { await spoken?.close() } }
    }

    // MARK: The box

    private var goal: TodayGoal? { session.goal }

    @ViewBuilder
    private var box: some View {
        if let spoken, taking || spoken.isBusy {
            HexGoalBox(
                label: goal == nil
                    ? String(localized: "goal.box.label.today", defaultValue: "today’s goal")
                    : String(localized: "goal.box.label.new", defaultValue: "new goal"),
                status: String(localized: "goal.box.on_phone", defaultValue: "On this phone"),
                content: takeContent(spoken)
            )
        } else if let goal {
            HexGoalBox(
                label: String(localized: "goal.box.label", defaultValue: "/goal"),
                status: GoalBoxStatus.text(goal, busy: session.agentBusy),
                content: .text(goal.objective),
                reason: GoalBoxStatus.reason(goal)
            )
        } else {
            HexGoalBox(
                label: String(localized: "goal.box.label", defaultValue: "/goal"),
                content: .empty(String(localized: "goal.box.empty", defaultValue: "No goal yet."))
            )
        }
    }

    private func takeContent(_ spoken: SpokenAnswer) -> HexGoalBox.Content {
        switch spoken.state {
        case .listening: .listening(String(localized: "draft.listening", defaultValue: "Listening…"))
        case .transcribing: .transcribing(String(localized: "draft.transcribing_chunk", defaultValue: "Transcribing…"))
        case .idle: .text(spoken.text)
        }
    }

    // MARK: Buttons

    @ViewBuilder
    private var buttons: some View {
        if taking {
            takeButtons
        } else {
            goalButtons
        }
    }

    private var goalButtons: some View {
        let kind = GoalSheetButtons.of(goal)
        let busy = sending != nil || (spoken?.isBusy ?? false)
        let listening = spoken?.isBusy ?? false
        return HStack(spacing: 8) {
            if kind.offersClear {
                HexButton(
                    LocalizedStringResource("goal.clear", defaultValue: "Clear"),
                    size: .regular,
                    style: .danger,
                    enabled: !busy || sending == .clear,
                    loading: sending == .clear
                ) { run(.clear) }
            }
            Spacer(minLength: 0)
            if kind.offersPause {
                HexButton(
                    LocalizedStringResource("goal.pause", defaultValue: "Pause"),
                    size: .regular,
                    enabled: !busy || sending == .pause,
                    loading: sending == .pause
                ) { run(.pause) }
            }
            if kind.offersResume {
                HexButton(
                    LocalizedStringResource("goal.resume", defaultValue: "Resume"),
                    size: .regular,
                    enabled: !busy || sending == .resume,
                    loading: sending == .resume
                ) { run(.resume) }
            }
            // The button being held stays enabled until it's let go.
            switch kind {
            case .none:
                sayButton(LocalizedStringResource("goal.say", defaultValue: "Say the goal"), style: .primary, enabled: sending == nil || listening)
            case .active, .paused:
                sayButton(LocalizedStringResource("goal.change", defaultValue: "Change"), enabled: sending == nil || listening)
            case .done:
                sayButton(LocalizedStringResource("goal.new", defaultValue: "New goal"), enabled: sending == nil || listening)
            }
        }
    }

    private var takeButtons: some View {
        let listening = spoken?.isBusy ?? false
        let text = spoken?.text ?? ""
        return HStack(spacing: 8) {
            HexButton(
                LocalizedStringResource("common.cancel", defaultValue: "Cancel"),
                size: .regular,
                enabled: sending == nil
            ) { cancelTake() }
            Spacer(minLength: 0)
            sayButton(LocalizedStringResource("goal.again", defaultValue: "Again"), enabled: sending == nil)
            HexButton(
                goal == nil
                    ? LocalizedStringResource("goal.set", defaultValue: "Set goal")
                    : LocalizedStringResource("goal.replace", defaultValue: "Replace goal"),
                size: .regular,
                style: .primary,
                enabled: !listening && !text.isEmpty && (sending == nil || sending == .set),
                loading: sending == .set
            ) { setGoal(text) }
        }
    }

    /// A button held to dictate: the take starts at the press and ends at the release.
    private func sayButton(_ title: LocalizedStringResource, style: HexButton.Style = .secondary, enabled: Bool) -> some View {
        HexButton(title, icon: .mic, size: .regular, style: style, enabled: enabled) {}
            .allowsHitTesting(false)
            .overlay {
                if enabled, let spoken {
                    HexHoldSurface(press: HexPress(
                        began: { spoken.pressBegan() },
                        ended: { Task { await endTake(spoken) } }
                    ))
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(Text(LocalizedStringResource("goal.say.hint", defaultValue: "Hold while you speak")))
            .accessibilityAction {
                // Screen readers: activate starts a take, activate again ends it.
                if spoken?.state == .listening {
                    Task { if let spoken { await endTake(spoken) } }
                } else {
                    spoken?.pressBegan()
                }
            }
    }

    /// Release: the take turns into text and the buttons become Cancel, Again and Set. A tap, or a
    /// take with no words, leaves the box as it was.
    private func endTake(_ spoken: SpokenAnswer) async {
        await spoken.pressEnded(onText: { _ in })
        if !spoken.text.isEmpty { taking = true }
    }

    private func cancelTake() {
        Task { await spoken?.close() }
        spoken = SpokenAnswer(home: home)
        taking = false
    }

    private func setGoal(_ text: String) {
        guard sending == nil else { return }
        sending = .set
        Task {
            let outcome = await session.setGoal(text)
            sending = nil
            // On success the sheet closes; otherwise the take's text is kept.
            if outcome == .done {
                spoken = SpokenAnswer(home: home)
                taking = false
            }
        }
    }

    private func run(_ action: Action) {
        guard sending == nil else { return }
        sending = action
        Task {
            switch action {
            case .pause: await session.pauseGoal()
            case .resume: await session.resumeGoal()
            case .clear: await session.clearGoal()
            case .set: break
            }
            sending = nil
        }
    }
}
