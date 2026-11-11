import SwiftUI

extension ReleaseNoteIcon {
    /// Closest catalog icon for each release-note key.
    var openCodeIcon: OpenCodeIcon {
        switch self {
        case .edit: .edit
        case .goal: .radioButtonChecked
        case .agent: .agent
        case .image: .widgets
        case .note: .rateReview
        case .search: .search
        case .keyboard: .code
        case .attach: .description
        case .sparkle: .star
        case .textSelect: .textSelect
        case .chat: .rateReview
        case .time: .restart
        case .sheet: .expandContent
        case .server: .cloud
        case .star: .star
        case .send: .arrowUpward
        case .fix: .check
        case .code: .code
        case .qr: .widgets
        case .gitBranch: .gitBranch
        case .list: .list
        case .terminal: .terminal
        case .slash: .slash
        case .lock: .lock
        case .archive: .archive
        case .folder: .folder
        case .sidebar: .sidebar
        case .mic: .mic
        case .atSign: .atSign
        case .fork: .callSplit
        case .feedback: .edit
        case .phone: .widgets
        }
    }
}

extension WhatsNewSectionHeader {
    var title: OpenCodeText {
        switch self {
        case let .inVersion(version):
            OpenCodeText(LocalizedStringResource("whats_new.in_version", defaultValue: "In \(version)"))
        case let .alsoSince(version):
            OpenCodeText(LocalizedStringResource(
                "whats_new.also_since",
                defaultValue: "Also new since your last update \u{00B7} \(version)"
            ))
        }
    }
}

/// A release's items: features, then fixes under their own heading.
struct WhatsNewReleaseItems: View {
    let section: WhatsNewReleaseSection

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(section.features.enumerated()), id: \.offset) { _, item in
                OpenCodeFeatureItem(
                    icon: item.icon.openCodeIcon,
                    title: .verbatim(item.title),
                    body: .verbatim(item.body)
                )
            }
            if !section.fixes.isEmpty {
                OpenCodeSectionHeader(title: "whats_new.fixes", inset: .flush)
                ForEach(Array(section.fixes.enumerated()), id: \.offset) { _, item in
                    OpenCodeFeatureItem(
                        icon: item.icon.openCodeIcon,
                        title: .verbatim(item.title),
                        body: .verbatim(item.body),
                        tone: .success
                    )
                }
            }
        }
    }
}

/// Eyebrow and skipped-updates line under the sheet title.
struct WhatsNewSheetIntro: View {
    let sheet: WhatsNewSheetState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                OpenCodeIconImage(icon: ReleaseNoteIcon.sparkle.openCodeIcon, size: 14)
                    .accessibilityHidden(true)
                Text(openCode: OpenCodeText(LocalizedStringResource(
                    "whats_new.version",
                    defaultValue: "Version \(sheet.installedVersion)"
                )))
                .font(OpenCodeTypography.caption.weight(.semibold))
            }
            .foregroundStyle(OpenCodeColors.textInteractiveBase)

            if sheet.skippedCount == 1 {
                Text("whats_new.skipped_one")
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textBase)
            } else if sheet.skippedCount > 1 {
                Text(openCode: OpenCodeText(LocalizedStringResource(
                    "whats_new.skipped_many",
                    defaultValue: "You skipped \(sheet.skippedCount) updates, so they\u{2019}re included below."
                )))
                .font(OpenCodeTypography.bodySecondary)
                .foregroundStyle(OpenCodeColors.textBase)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .openCodeBottomSheetContentInset()
        .padding(.bottom, 4)
    }
}

/// Scrolling body of the sheet.
struct WhatsNewSheetBody: View {
    let sheet: WhatsNewSheetState

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(sheet.sections) { section in
                if let header = section.header {
                    OpenCodeSectionHeader(title: header.title, inset: .flush)
                }
                WhatsNewReleaseItems(section: section)
            }
        }
        .openCodeBottomSheetContentInset()
        .accessibilityIdentifier("whatsNewSheetBody")
    }
}

/// Pinned footer: Done and where to find the notes again.
struct WhatsNewSheetFooter: View {
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            OpenCodeButton(variant: .fullWidthText(title: "whats_new.done"), size: .large, action: onDone)
                .accessibilityIdentifier("whatsNewDone")
            Text("whats_new.find_again")
                .font(OpenCodeTypography.caption)
                .foregroundStyle(OpenCodeColors.textWeak)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}

extension View {
    /// The What's new sheet, at its half stop, while `model.sheet` is set.
    func whatsNewSheet(_ model: WhatsNewSheetModel) -> some View {
        openCodeBottomSheet(
            isPresented: Binding(
                get: { model.sheet != nil },
                set: { visible in if !visible { model.dismiss() } }
            ),
            title: "whats_new.title",
            height: .half,
            pinnedTop: {
                if let sheet = model.sheet { WhatsNewSheetIntro(sheet: sheet) }
            },
            content: {
                if let sheet = model.sheet { WhatsNewSheetBody(sheet: sheet) }
            },
            pinnedBottom: {
                WhatsNewSheetFooter(onDone: { model.dismiss() })
            }
        )
    }
}

/// Settings → What's new: every bundled release up to the installed one.
struct WhatsNewHistoryView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model: WhatsNewHistoryModel

    /// Set when shown as the trailing root of the tablet Settings split view.
    var detailControl: OpenCodeSplitPaneDetailControl?

    init(model: WhatsNewHistoryModel = WhatsNewHistoryModel(), detailControl: OpenCodeSplitPaneDetailControl? = nil) {
        _model = State(initialValue: model)
        self.detailControl = detailControl
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBar(
                title: "whats_new.title",
                leading: OpenCodeSplitPane.detailLeading(detailControl, back: { dismiss() }),
                showDivider: true
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(model.state.sections) { section in
                        VStack(alignment: .leading, spacing: 4) {
                            WhatsNewReleaseHeading(section: section)
                            WhatsNewReleaseItems(section: section)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 16)
            }
            .openCodeFocusGroup(.vertical)
            .openCodeReadableColumn()
            .accessibilityIdentifier("whatsNewHistory")
        }
        .navigationBarBackButtonHidden(true)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .onAppear { model.onAppear() }
    }
}

/// Version, release date and the Installed tag above a release in history.
private struct WhatsNewReleaseHeading: View {
    let section: WhatsNewReleaseSection

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(verbatim: section.version)
                .font(OpenCodeTypography.title.weight(.semibold))
                .foregroundStyle(OpenCodeColors.textStrong)
                .accessibilityAddTraits(.isHeader)
            if let date = releasedText {
                Text(verbatim: date)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
            }
            Spacer(minLength: 0)
            if section.isInstalled {
                OpenCodeTag("whats_new.installed", size: .small, tone: .accent)
            }
        }
    }

    private var releasedText: String? {
        guard let components = section.released, let date = Calendar.current.date(from: components) else { return nil }
        return date.formatted(date: .long, time: .omitted)
    }
}

#Preview("What's new sheet") {
    let releases = [
        ReleaseNotesRelease(
            version: SemanticVersion(major: 1, minor: 4, patch: 0),
            released: nil,
            items: [
                ReleaseNoteItem(kind: .new, icon: .edit, title: "Start a session as a draft", body: "Write first, then pick the project."),
                ReleaseNoteItem(kind: .fixed, icon: .fix, title: "Steadier reconnects", body: "Session Chat picks up where it left off."),
            ]
        ),
        ReleaseNotesRelease(
            version: SemanticVersion(major: 1, minor: 2, patch: 1),
            released: DateComponents(year: 2026, month: 10, day: 27),
            items: [ReleaseNoteItem(kind: .improved, icon: .server, title: "Faster connect", body: "Servers answer sooner.")]
        ),
    ]
    let defaults = UserDefaults(suiteName: "whatsNewPreview")!
    defaults.set("1.2.0", forKey: WhatsNewStore.StorageKeys.lastSeenVersion)
    let store = WhatsNewStore(defaults: defaults, installedVersion: "1.4.0", releases: releases)
    let model = WhatsNewSheetModel(store: store)
    return Color.clear
        .whatsNewSheet(model)
        .task {
            store.prepareLaunch(isUpgrade: true)
            model.homeIsIdle()
        }
        .openCodeTheme()
}
