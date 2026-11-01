import ImageIO
import SwiftUI
import UIKit

/// Phone File Viewer pushed from the Files tree.
struct FileViewerScreen: View {
    let viewModel: FilesViewModel
    let relativePath: String
    @Environment(\.dismiss) private var dismiss
    @State private var menuPresented = false

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeTopBarOverflowHost(
                isPresented: $menuPresented,
                entries: FileViewerMenu.entries(
                    viewModel: viewModel,
                    relativePath: relativePath,
                    dismissMenu: { menuPresented = false }
                )
            ) {
                OpenCodeTopBar(
                    title: ProjectPaths.name(of: relativePath),
                    subtitle: relativePath,
                    leading: .back(action: {
                        FileViewerMenu.log(ProductAnalytics.Action.filesViewerBack)
                        dismiss()
                    }),
                    actions: [
                        .icon(.moreVert, accessibilityLabel: "More options") { menuPresented = true },
                    ],
                    showDivider: true
                )
            }
            FileViewerContent(viewModel: viewModel, relativePath: relativePath)
        }
        .background(OpenCodeColors.backgroundBase)
        .toolbar(.hidden, for: .navigationBar)
        .overlay {
            OpenCodeSnackbarHost(message: viewModel.state.snackbar, onDismissed: viewModel.consumeSnackbar)
        }
        .onAppear {
            ProductAnalytics.logScreenView(
                screenId: ProductAnalytics.Screen.filesViewer,
                presentation: ProductAnalytics.Presentation.route
            )
        }
    }
}

enum FileViewerMenu {
    @MainActor
    static func entries(
        viewModel: FilesViewModel,
        relativePath: String,
        dismissMenu: @escaping () -> Void
    ) -> [OpenCodeOverflowMenuEntry] {
        let wraps = viewModel.state.wrapLines
        let entries = [
            OpenCodeOverflowMenuEntry(
                id: "files-copy-path",
                title: "Copy path",
                icon: .contentCopy,
                action: {
                    log(ProductAnalytics.Action.filesViewerCopyPath)
                    dismissMenu()
                    viewModel.copyPath(relativePath)
                }
            ),
            OpenCodeOverflowMenuEntry(
                id: "files-wrap-lines",
                title: "Wrap lines",
                icon: wraps ? .check : nil,
                accessibilityLabel: wraps ? "Wrap lines, on" : "Wrap lines, off",
                action: {
                    log(ProductAnalytics.Action.filesViewerToggleWrap)
                    dismissMenu()
                    viewModel.toggleWrap()
                }
            ),
        ]
        #if HIGHLIGHT_LAB
        guard let document = viewModel.state.viewers[relativePath]?.textDocument else { return entries }
        return entries + [
            OpenCodeOverflowMenuEntry(
                id: "files-open-in-highlighting-lab",
                title: "Open in Highlighting Lab",
                icon: .code,
                action: {
                    dismissMenu()
                    HighlightLabLauncher.shared.open(relativePath: relativePath, document: document)
                }
            ),
        ]
        #else
        return entries
        #endif
    }

    /// The viewed file path is never reported.
    @MainActor
    static func log(_ actionId: String) {
        ProductAnalytics.logUserAction(screenId: ProductAnalytics.Screen.filesViewer, actionId: actionId)
    }
}

/// Viewer body for one open file, shared by the phone screen and the iPad tab pane.
struct FileViewerContent: View {
    let viewModel: FilesViewModel
    let relativePath: String

    private static let previewTabID = "preview"
    private static let sourceTabID = "source"

    var body: some View {
        Group {
            if let viewer = viewModel.state.viewers[relativePath] {
                content(viewer)
            } else {
                OpenCodeStatusStack(spinner: "Loading file")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private func content(_ viewer: ProjectFileViewerState) -> some View {
        switch viewer.body {
        case .loading:
            OpenCodeStatusStack(spinner: "Loading file")
        case .failed:
            OpenCodeEmptyState(
                title: "Couldn't open file",
                message: "Check the connection and try again.",
                primaryAction: OpenCodeEmptyStateAction(title: "Retry") {
                    FileViewerMenu.log(ProductAnalytics.Action.filesViewerRetry)
                    viewModel.retryFile(relativePath)
                }
            )
        case .empty:
            OpenCodeEmptyState(title: "Empty file", message: "This file has no content.", icon: .description)
        case let .binary(mimeType):
            Self.binaryPlaceholder(mimeType: mimeType)
        case let .tooLarge(byteCount):
            OpenCodeEmptyState(
                title: FileViewerCopy.tooLargeTitle,
                message: FileViewerCopy.tooLargeDetail(byteCount: byteCount),
                icon: .description
            )
        case let .image(data, mimeType):
            FileImagePreview(relativePath: relativePath, data: data, mimeType: mimeType)
        case let .text(document):
            textContent(viewer, document: document)
        }
    }

    static func binaryPlaceholder(mimeType: String?) -> some View {
        OpenCodeEmptyState(
            title: "Binary file",
            message: [mimeType, "No preview for this file type."].compactMap { $0 }.joined(separator: "\n"),
            icon: .description
        )
    }

    private func textContent(_ viewer: ProjectFileViewerState, document: FileTextDocument) -> some View {
        VStack(spacing: 0) {
            if document.isTruncated {
                OpenCodeInlineNotice(message: "Showing the first 1 MB of this file.", variant: .banner)
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
            }
            Group {
                if viewer.showsMarkdownPreview {
                    if let preview = viewer.markdownPreview {
                        ScrollView {
                            OpenCodeRenderedMarkdown(document: preview.document, variant: .standalone)
                                .padding(16)
                        }
                    } else {
                        OpenCodeStatusStack(spinner: "Rendering preview")
                    }
                } else {
                    codeFile(viewer, document: document)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            footer(viewer, document: document)
        }
    }

    private func codeFile(_ viewer: ProjectFileViewerState, document: FileTextDocument) -> some View {
        let relativePath = relativePath
        let viewModel = viewModel
        return OpenCodeCodeFile(
            lines: document.lines,
            lineSpans: viewer.lineSpans,
            wrapsLines: viewModel.state.wrapLines,
            maxLineLength: document.maxLineLength,
            onLineAppear: { viewModel.lineAppeared(relativePath, line: $0) }
        )
    }

    private func footer(_ viewer: ProjectFileViewerState, document: FileTextDocument) -> some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(OpenCodeColors.borderWeakerBase)
                .frame(height: 1)
            HStack(spacing: 8) {
                if viewer.isMarkdown {
                    OpenCodeFilterTabs(
                        tabs: [
                            OpenCodeFilterTab(id: Self.previewTabID, title: "Preview"),
                            OpenCodeFilterTab(id: Self.sourceTabID, title: "Source"),
                        ],
                        selectedTabId: viewer.showsMarkdownSource ? Self.sourceTabID : Self.previewTabID,
                        onTabSelected: { id in
                            let showsSource = id == Self.sourceTabID
                            guard showsSource != viewer.showsMarkdownSource else { return }
                            FileViewerMenu.log(ProductAnalytics.Action.filesViewerToggleMarkdownSource)
                            viewModel.setMarkdownSource(relativePath, showsSource: showsSource)
                        }
                    )
                    .fixedSize()
                }
                Spacer(minLength: 0)
                Text(document.metaText)
                    .font(OpenCodeTypography.caption)
                    .foregroundStyle(OpenCodeColors.textWeak)
                    .lineLimit(1)
                    .padding(.trailing, 16)
                    .padding(.leading, viewer.isMarkdown ? 0 : 16)
                    .padding(.vertical, viewer.isMarkdown ? 0 : 12)
            }
        }
        .background(OpenCodeColors.backgroundBase)
    }
}

/// Fitted image preview. Decodes a downsampled bitmap off the main actor; undecodable data
/// shows the binary placeholder.
private struct FileImagePreview: View {
    let relativePath: String
    let data: Data
    let mimeType: String

    @State private var image: UIImage?
    @State private var decodeFailed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .padding(16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel("Image preview")
            } else if decodeFailed {
                FileViewerContent.binaryPlaceholder(mimeType: mimeType)
            } else {
                OpenCodeStatusStack(spinner: "Loading image")
            }
        }
        // Path and size identify the bytes without comparing up to 10 MB of data per update.
        .task(id: "\(relativePath):\(data.count)") {
            let decoded = await FileImageDownsampler.image(from: data)
            guard !Task.isCancelled else { return }
            image = decoded.map { UIImage(cgImage: $0) }
            decodeFailed = decoded == nil
        }
    }
}

/// Decodes image bytes at most `maxPixelSize` on the longer side, so a huge photo never
/// becomes a full-resolution bitmap (Android samples down to the same size).
nonisolated enum FileImageDownsampler {
    static let maxPixelSize = 4096

    @concurrent
    static func image(from data: Data, maxPixelSize: Int = maxPixelSize) async -> CGImage? {
        downsample(data, maxPixelSize: maxPixelSize)
    }

    static func downsample(_ data: Data, maxPixelSize: Int = maxPixelSize) -> CGImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions),
              CGImageSourceGetCount(source) > 0
        else { return nil }
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ] as CFDictionary
        return CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions)
    }
}
