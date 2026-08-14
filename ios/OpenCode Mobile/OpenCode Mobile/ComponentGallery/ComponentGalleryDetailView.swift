import SwiftUI

struct ComponentGalleryDetailView: View {
    let entry: ComponentGalleryEntry
    @Binding var themeMode: ComponentGalleryThemeMode
    @Environment(\.dismiss) private var dismiss
    @State private var messageListGalleryModel = MessageListGalleryModel()
    private let horizontalPadding: CGFloat = 16

    var body: some View {
        VStack(spacing: 0) {
            if entry.id == .messageList {
                messageListTopBar
            } else {
                OpenCodeTopBar(
                    title: entry.title,
                    leading: .back(action: { dismiss() }),
                    showDivider: true
                )
            }

            if entry.id == .messageList {
                MessageListGalleryView(model: messageListGalleryModel)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if entry.id == .sessionComposer || entry.id == .composerSuggestionOverlay {
                VStack(alignment: .leading, spacing: 20) {
                    Text(entry.description)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.textBase)
                        .padding(.horizontal, horizontalPadding)

                    ComponentGalleryControls(themeMode: $themeMode)
                        .padding(.horizontal, horizontalPadding)

                    if entry.id == .composerSuggestionOverlay {
                        content
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .padding(.horizontal, horizontalPadding)
                    } else {
                        content
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .padding(.top, 16)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(entry.description)
                            .font(OpenCodeTypography.bodySecondary)
                            .foregroundStyle(OpenCodeColors.textBase)
                            .padding(.horizontal, horizontalPadding)

                        ComponentGalleryControls(themeMode: $themeMode)
                            .padding(.horizontal, horizontalPadding)

                        if entry.id == .topBar || entry.id == .overflowMenu || entry.id == .sessionStatusBar {
                            content
                        } else {
                            content
                                .padding(.horizontal, horizontalPadding)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 16)
                }
            }
        }
        .background(OpenCodeColors.backgroundBase)
        .navigationBarHidden(true)
    }

    @ViewBuilder
    private var content: some View {
        switch entry.id {
        case .colors:
            ColorGalleryView()
        case .typography:
            TypographyGalleryView()
        case .icons:
            IconsGalleryView()
        case .attachment:
            AttachmentGalleryView()
        case .outgoingMessage:
            OutgoingMessageGalleryView()
        case .toolCallCard:
            ToolCallCardGalleryView()
        case .childSessionRow:
            ChildSessionRowGalleryView()
        case .topBar:
            TopBarGalleryView()
        case .overflowMenu:
            OverflowMenuGalleryView()
        case .bottomSheet:
            BottomSheetGalleryView()
        case .dialog:
            DialogGalleryView()
        case .snackbar:
            SnackbarGalleryView()
        case .listItem:
            ListItemGalleryView()
        case .listGroup:
            ListGroupGalleryView()
        case .choiceBadge:
            ChoiceBadgeGalleryView()
        case .filterTabs:
            FilterTabsGalleryView()
        case .actionPill:
            ActionPillGalleryView()
        case .syncingPill:
            SyncingPillGalleryView()
        case .reviewChangesPill:
            ReviewChangesPillGalleryView()
        case .sessionTodosPill:
            SessionTodosPillGalleryView()
        case .sessionTodoItem:
            SessionTodoItemGalleryView()
        case .hunkNavigationPill:
            HunkNavigationPillGalleryView()
        case .unifiedDiff:
            UnifiedDiffGalleryView()
        case .button:
            ButtonGalleryView()
        case .textInput:
            TextInputGalleryView()
        case .serverAddressInput:
            ServerAddressInputGalleryView()
        case .emptyState:
            EmptyStateGalleryView()
        case .statusStack:
            StatusStackGalleryView()
        case .sessionList:
            SessionListGalleryView()
        case .sessionListDock:
            SessionListDockGalleryView()
        case .sessionStatusBar:
            SessionStatusBarGalleryView()
        case .sessionComposer:
            SessionComposerGalleryView()
        case .composerSuggestionOverlay:
            ComposerSuggestionOverlayGalleryView()
        case .renderedMarkdown:
            RenderedMarkdownGalleryView()
        case .messageList:
            EmptyView()
        }
    }

    private var messageListTopBar: some View {
        ZStack(alignment: .trailing) {
            OpenCodeTopBar(
                title: entry.title,
                leading: .back(action: { dismiss() }),
                showDivider: true
            )

            Menu {
                Toggle(
                    "Live messages",
                    isOn: $messageListGalleryModel.liveMessagesEnabled
                )
                Toggle(
                    "Auto-follow new messages",
                    isOn: $messageListGalleryModel.autoFollowEnabled
                )
            } label: {
                OpenCodeButton(
                    variant: .iconCircle(.moreVert, accessibilityLabel: "More options"),
                    contrast: .low,
                    action: {}
                )
            }
            .padding(.trailing, 12)
            .padding(.vertical, 12)
        }
    }
}

#Preview {
    NavigationStack {
        ComponentGalleryDetailView(entry: componentGalleryEntries[0], themeMode: .constant(.system))
    }
    .openCodeTheme()
}
