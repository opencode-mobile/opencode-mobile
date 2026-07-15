import Foundation

enum ComponentGalleryCategory: String, CaseIterable {
    case foundation = "Foundations"
    case components = "Components"
}

enum ComponentGalleryEntryID: String, Hashable {
    case colors
    case typography
    case icons
    case outgoingMessage
    case topBar
    case overflowMenu
    case bottomSheet
    case listItem
    case listGroup
    case choiceBadge
    case actionPill
    case reviewChangesPill
    case hunkNavigationPill
    case unifiedDiff
    case button
    case textInput
    case projectSessionSelector
    case sessionStatusBar
    case sessionComposer
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
        category: .foundation
    ),
    ComponentGalleryEntry(
        id: .typography,
        title: "Typography",
        description: "Review shared type roles and platform mappings.",
        category: .foundation
    ),
    ComponentGalleryEntry(
        id: .icons,
        title: "Icons",
        description: "Material Icons catalog synced from icons/manifest.yaml.",
        category: .foundation
    ),
    ComponentGalleryEntry(
        id: .outgoingMessage,
        title: "Outgoing Message",
        description: "Trailing prompt card that renders sent prompts at full height.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .topBar,
        title: "Top Bar",
        description: "Page-level navigation bar with leading affordance and actions.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .overflowMenu,
        title: "Overflow Menu",
        description: "Compact anchored popup menu with icon and label entries.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .bottomSheet,
        title: "Bottom Sheet",
        description: "Modal action sheet with title, close affordance, and entries.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .listItem,
        title: "List Item",
        description: "Reusable project and session row treatment.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .listGroup,
        title: "List Group",
        description: "Bordered and separated container for related List Item rows.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .choiceBadge,
        title: "Choice Badge",
        description: "Selectable pill for compact single-choice controls.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .actionPill,
        title: "Action Pill",
        description: "Floating pill for contextual overlay actions such as scroll to end.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .reviewChangesPill,
        title: "Review Changes Pill",
        description: "Contextual shortcut to files changed by the latest completed turn.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .hunkNavigationPill,
        title: "Hunk Navigation Pill",
        description: "Floating hunk movement and context controls for unified diffs.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .unifiedDiff,
        title: "Unified Diff",
        description: "Read-only file changes with stable old and new line gutters.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .button,
        title: "Button",
        description: "Reusable action component with text and icon variants.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .textInput,
        title: "Text Input",
        description: "Labeled single-line entry for text, URLs, usernames, and passwords.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .projectSessionSelector,
        title: "Project Session Selector",
        description: "Project-scoped session picker with project tiles and session actions.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .sessionStatusBar,
        title: "Session Status Bar",
        description: "Compact session id, live-update status, and context usage strip.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .sessionComposer,
        title: "Session Composer",
        description: "Prompt entry and blocking permission states in one persistent session surface.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .renderedMarkdown,
        title: "Rendered Markdown",
        description: "Safe markdown, code, and fenced diff rendering for message text.",
        category: .components
    ),
    ComponentGalleryEntry(
        id: .messageList,
        title: "Message List",
        description: "Scrollable outgoing and incoming message list with scroll-to-end pill.",
        category: .components
    ),
]
