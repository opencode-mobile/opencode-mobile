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
    case attachment
    case outgoingMessage
    case toolCallCard
    case topBar
    case overflowMenu
    case bottomSheet
    case dialog
    case snackbar
    case listItem
    case listGroup
    case choiceBadge
    case filterTabs
    case actionPill
    case reviewChangesPill
    case hunkNavigationPill
    case unifiedDiff
    case button
    case textInput
    case projectSessionSelector
    case sessionList
    case sessionListDock
    case sessionStatusBar
    case sessionComposer
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
        description: "Material Icons catalog synced from icons/manifest.yaml.",
        category: .foundations
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
        description: "Single-line entry with in-field hint for text, URLs, usernames, and passwords.",
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
        id: .projectSessionSelector,
        title: "Project Session Selector",
        description: "Project-scoped session picker with project tiles and session actions.",
        category: .session
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
        description: "Floating filter chips and Add project / New session actions.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .sessionComposer,
        title: "Session Composer",
        description: "Prompt entry and blocking permission states in one persistent session surface.",
        category: .session
    ),
    ComponentGalleryEntry(
        id: .composerSuggestionOverlay,
        title: "Composer Suggestion Overlay",
        description: "Compact suggestion list anchored above the Session Chat composer for / commands.",
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
        id: .unifiedDiff,
        title: "Unified Diff",
        description: "Read-only file changes with stable old and new line gutters.",
        category: .diffs
    ),
]
