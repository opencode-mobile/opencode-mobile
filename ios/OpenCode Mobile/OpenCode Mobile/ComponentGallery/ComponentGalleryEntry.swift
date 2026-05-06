import Foundation

enum ComponentGalleryCategory: String {
    case foundation = "Foundations"
    case components = "Components"
}

enum ComponentGalleryEntryID: String, Hashable {
    case colors
    case typography
    case listItem
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
        id: .listItem,
        title: "List Item",
        description: "Reusable project and session row treatment.",
        category: .components
    ),
]
