package dev.opencode.mobile.ui.componentgallery

import kotlinx.serialization.Serializable

enum class ComponentGalleryCategory(val label: String) {
  Foundation("Foundations"),
  Components("Components"),
}

@Serializable
enum class ComponentGalleryEntryId {
  Colors,
  Typography,
  BottomSheet,
  ListItem,
  ChoiceBadge,
  Button,
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
      id = ComponentGalleryEntryId.Button,
      title = "Button",
      description = "Reusable action component with text and icon variants.",
      category = ComponentGalleryCategory.Components,
    ),
  )
