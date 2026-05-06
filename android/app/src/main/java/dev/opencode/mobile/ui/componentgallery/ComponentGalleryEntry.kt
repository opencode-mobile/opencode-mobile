package dev.opencode.mobile.ui.componentgallery

import kotlinx.serialization.Serializable

enum class ComponentGalleryCategory(val label: String) {
  Foundation("Foundations"),
}

@Serializable
enum class ComponentGalleryEntryId {
  Colors,
  Typography,
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
  )
