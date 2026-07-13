package dev.opencode.mobile.ui.componentgallery

import kotlinx.serialization.Serializable

enum class ComponentGalleryCategory(
    val label: String,
) {
    Foundation("Foundations"),
    Components("Components"),
}

@Serializable
enum class ComponentGalleryEntryId {
    Colors,
    Typography,
    Icons,
    OutgoingMessage,
    TopBar,
    OverflowMenu,
    BottomSheet,
    ListItem,
    ChoiceBadge,
    ActionPill,
    Button,
    TextInput,
    ProjectSessionSelector,
    SessionStatusBar,
    SessionComposer,
    RenderedMarkdown,
    MessageList,
}

data class ComponentGalleryEntry(
    val id: ComponentGalleryEntryId,
    val title: String,
    val description: String,
    val category: ComponentGalleryCategory,
)

val ComponentGalleryEntries =
    listOf(
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.Colors,
            title = "Colors",
            description = "Review semantic light and dark color tokens.",
            category = ComponentGalleryCategory.Foundation,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.Typography,
            title = "Typography",
            description = "Review shared type roles and platform mappings.",
            category = ComponentGalleryCategory.Foundation,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.Icons,
            title = "Icons",
            description = "Material Icons catalog synced from icons/manifest.yaml.",
            category = ComponentGalleryCategory.Foundation,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.OutgoingMessage,
            title = "Outgoing Message",
            description = "Trailing prompt card that renders sent prompts at full height.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.TopBar,
            title = "Top Bar",
            description = "Page-level navigation bar with leading affordance and actions.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.OverflowMenu,
            title = "Overflow Menu",
            description = "Compact anchored popup menu with icon and label entries.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.BottomSheet,
            title = "Bottom Sheet",
            description = "Modal action sheet with title, close affordance, and entries.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.ListItem,
            title = "List Item",
            description = "Reusable project and session row treatment.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.ChoiceBadge,
            title = "Choice Badge",
            description = "Selectable pill for compact single-choice controls.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.ActionPill,
            title = "Action Pill",
            description = "Floating pill for contextual overlay actions such as scroll to end.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.Button,
            title = "Button",
            description = "Reusable action component with text and icon variants.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.TextInput,
            title = "Text Input",
            description = "Labeled single-line entry for text, URLs, usernames, and passwords.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.ProjectSessionSelector,
            title = "Project Session Selector",
            description = "Project-scoped session picker with project tiles and session actions.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.SessionStatusBar,
            title = "Session Status Bar",
            description = "Compact session id, live-update status, and context usage strip.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.SessionComposer,
            title = "Session Composer",
            description = "Prompt entry and blocking permission states in one persistent session surface.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.RenderedMarkdown,
            title = "Rendered Markdown",
            description = "Safe markdown, code, and fenced diff rendering for message text.",
            category = ComponentGalleryCategory.Components,
        ),
        ComponentGalleryEntry(
            id = ComponentGalleryEntryId.MessageList,
            title = "Message List",
            description = "Scrollable outgoing and incoming message list with scroll-to-end pill.",
            category = ComponentGalleryCategory.Components,
        ),
    )
