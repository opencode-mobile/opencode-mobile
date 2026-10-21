import Foundation

enum ComponentGalleryCategory: String, CaseIterable {
    case foundations = "Foundations"
    case controls = "Controls"
    case lists = "Lists"
    case navigation = "Navigation"
    case chat = "Chat"
    case session = "Session"
    case diffs = "Diffs"
}

enum ComponentGalleryEntryID: String, Hashable {
    case colors
    case typography
    case icons
    case fileIcon
    case skeletonIllustration
    case pageIndicator
    case bottomFade
    case actionBar
    case tag
    case keyValueRow
    case attachment
    case outgoingMessage
    case pendingPromptsStrip
    case assistantMessage
    case toolCallCard
    case toolChip
    case messageNote
    case timelineNotice
    case childSessionRow
    case stepRail
    case activityCard
    case workingIndicator
    case reasoningRow
    case topBar
    case splitPane
    case overflowMenu
    case bottomSheet
    case dialog
    case modal
    case snackbar
    case listItem
    case selectableCard
    case listGroup
    case sectionHeader
    case reorderableList
    case choiceBadge
    case filterTabs
    case actionPill
    case actionTile
    case syncingPill
    case reviewChangesPill
    case sessionTodosPill
    case sessionTodoItem
    case hunkNavigationPill
    case unifiedDiff
    case codeFile
    case treeRow
    case fileTabs
    case button
    case textInput
    case serverAddressInput
    case emptyState
    case statusStack
    case diffStat
    case inlineNotice
    case codeSnippet
    case sessionList
    case sessionListDock
    case sessionListSkeleton
    case sessionStatusBar
    case sessionBranchPill
    case sessionComposer
    case dictationCard
    case composerSuggestionOverlay
    case renderedMarkdown
    case messageList
}

struct ComponentGalleryEntry: Identifiable, Hashable {
    let id: ComponentGalleryEntryID
    let title: String
    let description: String
    let category: ComponentGalleryCategory
}

let componentGalleryEntries = [
    ComponentGalleryEntry(
        id: .colors,
        title: "Colors",
        description: "Review semantic light and dark color tokens.",
        category: .foundations
    ),
    ComponentGalleryEntry(
        id: .icons,
        title: "Icons",
        description: "Lucide catalog synced from icons/manifest.yaml.",
        category: .foundations
    ),
    ComponentGalleryEntry(
        id: .fileIcon,
        title: "File Icon",
        description: "Colour file-type and folder icons picked from a path, as in opencode.",
        category: .foundations
    ),
    ComponentGalleryEntry(
        id: .skeletonIllustration,
        title: "Skeleton Illustration",
        description: "Wireframe hero with one spotlit element for onboarding, tutorials, and hints.",
        category: .foundations
    ),
    ComponentGalleryEntry(
        id: .pageIndicator,
        title: "Page Indicator",
        description: "Pager position dots under onboarding and promo carousels.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .bottomFade,
        title: "Bottom Fade",
        description: "Gradient that fades list content into the background under a dock.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .actionBar,
        title: "Action Bar",
        description: "Bottom-pinned primary action naming what it acts on; disabled titles give the reason.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .tag,
        title: "Tag",
        description: "Static capsule label.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .keyValueRow,
        title: "Key Value Row",
        description: "Label and value for detail lines and stat cells.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .typography,
        title: "Typography",
        description: "Review shared type roles and platform mappings.",
        category: .foundations
    ),
    ComponentGalleryEntry(
        id: .actionPill,
        title: "Action Pill",
        description: "Floating pill for contextual overlay actions such as scroll to end.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .actionTile,
        title: "Action Tile",
        description: "Labeled icon tile for two-column action palettes such as Commands.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .syncingPill,
        title: "Syncing Pill",
        description: "Non-interactive floating status pill for quiet cache→network sync.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .button,
        title: "Button",
        description: "Reusable action component with text and icon variants.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .choiceBadge,
        title: "Choice Badge",
        description: "Selectable pill for compact single-choice controls.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .filterTabs,
        title: "Filter Tabs",
        description: "Horizontal single-select filters with optional count badges.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .textInput,
        title: "Text Input",
        description: "Single-line entry with in-field hint for text, URLs, usernames, passwords, and filters.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .serverAddressInput,
        title: "Server Address Input",
        description: "HTTP/HTTPS prefix plus IP and port field with paste parsing.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .emptyState,
        title: "Empty State",
        description: "Centered title and message for empty screen regions.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .statusStack,
        title: "Status Stack",
        description: "Centered mid-well indeterminate status for first content load.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .diffStat,
        title: "Diff Stat",
        description: "Addition and deletion counts for changed files.",
        category: .diffs
    ),
    ComponentGalleryEntry(
        id: .inlineNotice,
        title: "Inline Notice",
        description: "Tonal inline message with an optional trailing action.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .codeSnippet,
        title: "Code Snippet",
        description: "Monospace command block.",
        category: .controls
    ),
    ComponentGalleryEntry(
        id: .listGroup,
        title: "List Group",
        description: "Bordered and separated container for related List Item rows.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .listItem,
        title: "List Item",
        description: "Reusable project and session row treatment.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .selectableCard,
        title: "Selectable Card",
        description: "Title, blurb, and preview slot that can be selected.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .sectionHeader,
        title: "Section Header",
        description: "Caption title above a group of rows with optional count or action.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .reorderableList,
        title: "Reorderable List",
        description: "Stacked rows that lift on long-press and reorder by drag.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .bottomSheet,
        title: "Bottom Sheet",
        description: "Modal action sheet with title, close affordance, and entries.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .dialog,
        title: "Dialog",
        description: "Centered popup with title, optional subtitle, two options, and optional text field.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .modal,
        title: "Modal",
        description: "Centered card with a close button and custom content for promos and short walkthroughs.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .snackbar,
        title: "Snackbar",
        description: "Auto-dismissing transient success or error feedback overlay.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .overflowMenu,
        title: "Overflow Menu",
        description: "Compact anchored popup menu with icon and label entries.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .splitPane,
        title: "Split Pane",
        description: "Tablet list and detail panes with a resizable, collapsible sidebar.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .topBar,
        title: "Top Bar",
        description: "Page-level navigation bar with leading affordance and actions.",
        category: .navigation
    ),
    ComponentGalleryEntry(
        id: .attachment,
        title: "Attachment",
        description: "Pending attachment chips and sent image, PDF, and text rows.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .messageList,
        title: "Message List",
        description: "Scrollable outgoing and incoming message list with scroll-to-end pill.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .outgoingMessage,
        title: "Outgoing Message",
        description: "Trailing prompt card that renders sent prompts at full height.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .pendingPromptsStrip,
        title: "Pending Prompts Strip",
        description: "Compact capsule for steer and queued prompts waiting while a session is working.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .assistantMessage,
        title: "Assistant Message",
        description: "Leading reply card for assistant text in Session Chat.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .renderedMarkdown,
        title: "Rendered Markdown",
        description: "Safe markdown, code, and fenced diff rendering for message text.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .toolCallCard,
        title: "Tool Call Card",
        description: "Collapsed tool-agnostic assistant tool row with status variants.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .toolChip,
        title: "Tool Chip",
        description: "Compact tool reference under a narration paragraph, by variant and status.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .messageNote,
        title: "Message Note",
        description: "Muted note under a reply that was cut off or interrupted.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .timelineNotice,
        title: "Timeline Notice",
        description: "Quiet line for a moved directory, agent or model change, instructions, or a restart.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .childSessionRow,
        title: "Child Session Row",
        description: "Quiet person/thread row for a child session in Session Chat.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .stepRail,
        title: "Step Rail",
        description: "One line per agent step on a vertical rail, live while the agent works.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .activityCard,
        title: "Activity Card",
        description: "One live card per run of agent steps, with running agents and the current tool, that settles into a Used line.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .workingIndicator,
        title: "Working Indicator",
        description: "Pulsing status row for a turn that is working, idle, or collapsed.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .reasoningRow,
        title: "Reasoning Row",
        description: "Thinking and Thought rows for a reasoning block.",
        category: .chat
    ),
    ComponentGalleryEntry(
        id: .sessionList,
        title: "Session List",
        description: "Cross-project recent sessions with status and relative time.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionListDock,
        title: "Session List Dock",
        description: "Scope control and Add project / New session actions.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionListSkeleton,
        title: "Session List Skeleton",
        description: "Shimmering session-row placeholders while a project's sessions load, with an optional slow caption.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionComposer,
        title: "Session Composer",
        description: "Prompt entry and blocking permission states in one persistent session surface.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .dictationCard,
        title: "Dictation Card",
        description: "Listening takeover for composer dictation.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .composerSuggestionOverlay,
        title: "Composer Suggestion Overlay",
        description: "Compact suggestion list anchored above the Session Chat composer for / commands.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionBranchPill,
        title: "Session Branch Pill",
        description: "Floating branch and working-tree line totals over Session Chat.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionStatusBar,
        title: "Session Status Bar",
        description: "Compact session id, live-update status, and context usage strip.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .hunkNavigationPill,
        title: "Hunk Navigation Pill",
        description: "Floating hunk movement and context controls for unified diffs.",
        category: .diffs
    ),
    ComponentGalleryEntry(
        id: .reviewChangesPill,
        title: "Review Changes Pill",
        description: "Contextual shortcut to files changed by the latest completed turn.",
        category: .diffs
    ),
    ComponentGalleryEntry(
        id: .sessionTodosPill,
        title: "Session Todos Pill",
        description: "Floating live Session Todo List progress and active preview.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionTodoItem,
        title: "Session Todo Item",
        description: "View-only Session Todo status row for the todos sheet.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .unifiedDiff,
        title: "Unified Diff",
        description: "Read-only file changes with stable old and new line gutters.",
        category: .diffs
    ),
    ComponentGalleryEntry(
        id: .codeFile,
        title: "Code File",
        description: "Read-only source file with a line-number gutter, highlighting, and wrap on or off.",
        category: .diffs
    ),
    ComponentGalleryEntry(
        id: .treeRow,
        title: "Tree Row",
        description: "File tree row with depth indent, disclosure, icon, and selected, dimmed, or loading states.",
        category: .lists
    ),
    ComponentGalleryEntry(
        id: .fileTabs,
        title: "File Tabs",
        description: "Open files in the tablet viewer, with close buttons.",
        category: .navigation
    ),
]
