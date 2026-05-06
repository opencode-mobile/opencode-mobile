import Foundation

enum ComponentGalleryCategory: String {
    case foundation = "Foundations"
}

enum ComponentGalleryEntryID: String, Hashable {
    case colors
    case typography
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
]
