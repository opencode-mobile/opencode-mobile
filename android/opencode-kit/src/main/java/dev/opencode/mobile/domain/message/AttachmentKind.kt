package dev.opencode.mobile.domain.message

enum class AttachmentKind {
    Image,
    Pdf,
    Text,

    /** Any other file. Only streamed uploads carry it; the data-URL path never sends one. */
    File,
}
