import OpenCodeMarkdownCore
import SwiftUI
import UIKit

/// What a tap on assistant text landed on.
enum OpenCodeMessageTapTarget: Equatable {
    case text
    case codeBlock(String)
}

private struct MarkdownCodeBlockTapActionKey: EnvironmentKey {
    static let defaultValue: ((String) -> Void)? = nil
}

extension EnvironmentValues {
    /// Set by a message that offers actions: tapping or long-pressing a code
    /// or diff block calls it with the block's text.
    var markdownCodeBlockTapAction: ((String) -> Void)? {
        get { self[MarkdownCodeBlockTapActionKey.self] }
        set { self[MarkdownCodeBlockTapActionKey.self] = newValue }
    }
}

/// Bounds of the code and diff blocks in a message that offers actions, so a
/// secondary click on the message can tell which block it landed on.
private struct MarkdownCodeBlockBoundsKey: PreferenceKey {
    static let defaultValue: [(code: String, bounds: Anchor<CGRect>)] = []

    static func reduce(
        value: inout [(code: String, bounds: Anchor<CGRect>)],
        nextValue: () -> [(code: String, bounds: Anchor<CGRect>)]
    ) {
        value.append(contentsOf: nextValue())
    }
}

extension View {
    /// A secondary click on a rendered message opens its actions with what the
    /// pointer is over: a code or diff block offers its own copy, like a tap on it.
    func openCodeMessageSecondaryClick(_ action: @escaping (OpenCodeMessageTapTarget) -> Void) -> some View {
        overlayPreferenceValue(MarkdownCodeBlockBoundsKey.self) { blocks in
            GeometryReader { proxy in
                let frames = blocks.map { (code: $0.code, frame: proxy[$0.bounds]) }
                Color.clear.openCodeSecondaryClick(at: { point in
                    let block = frames.last { $0.frame.contains(point) }
                    action(block.map { .codeBlock($0.code) } ?? .text)
                })
            }
        }
    }
}

struct OpenCodeRenderedMarkdown: View {
    let document: NativeMarkdownDocument
    let variant: OpenCodeRenderedMarkdownVariant
    let isStreaming: Bool
    let initialHeight: CGFloat
    let measuresHeight: Bool
    let onHeightChange: (CGFloat) -> Void
    let onLinkTap: (URL) -> Void

    @State private var measuredHeight: CGFloat

    init(
        document: NativeMarkdownDocument,
        variant: OpenCodeRenderedMarkdownVariant,
        isStreaming: Bool = false,
        initialHeight: CGFloat = 1,
        measuresHeight: Bool = false,
        onHeightChange: @escaping (CGFloat) -> Void = { _ in },
        onLinkTap: @escaping (URL) -> Void = { url in UIApplication.shared.open(url) }
    ) {
        self.document = document
        self.variant = variant
        self.isStreaming = isStreaming
        self.initialHeight = max(1, initialHeight)
        self.measuresHeight = measuresHeight
        self.onHeightChange = onHeightChange
        self.onLinkTap = onLinkTap
        _measuredHeight = State(initialValue: max(1, initialHeight))
    }

    var body: some View {
        let entries = document.blocksWithStableIdentities
        VStack(alignment: .leading, spacing: blockSpacing) {
            ForEach(entries) { entry in
                blockView(entry.block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .modifier(RenderedMarkdownHeightReporter(isEnabled: measuresHeight) { size in
            let nextHeight = max(1, size.height)
            guard abs(measuredHeight - nextHeight) > 1 else { return }
            measuredHeight = nextHeight
            onHeightChange(nextHeight)
        })
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "http" || url.scheme == "https" else {
                return .discarded
            }
            onLinkTap(url)
            return .handled
        })
        // Keep link chrome on markdown-link tokens instead of the system accent tint.
        .tint(OpenCodeColors.markdownLinkText)
    }

    @ViewBuilder
    private func blockView(_ block: NativeMarkdownBlock) -> some View {
        switch block {
        case .paragraph(let inlines):
            inlineText(inlines, font: OpenCodeTypography.body, color: OpenCodeColors.textStrong, lineSpacing: 4)
        case .heading(let level, let inlines):
            inlineText(inlines, font: headingFont(level), color: headingColor(level))
        case .blockquote(let inlines):
            inlineText(inlines, font: OpenCodeTypography.body, color: OpenCodeColors.markdownQuoteText, lineSpacing: 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 13)
                .overlay(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(OpenCodeColors.markdownQuoteBar)
                        .frame(width: 3)
                }
        case .bulletList(let items):
            markdownList(
                markers: Array(repeating: "•", count: items.count),
                items: items
            )
        case .orderedList(let start, let items):
            markdownList(
                markers: items.indices.map { "\(start + $0)." },
                items: items
            )
        case .codeBlock(_, let code, let spans):
            codeBlock(code: code, spans: spans)
        case .diffBlock(let lines):
            diffBlock(lines: lines)
        case .table(let header, let rows):
            table(header: header, rows: rows)
        case .thematicBreak:
            Rectangle()
                .fill(OpenCodeColors.markdownHr)
                .frame(height: 1)
        }
    }

    private func markdownList(
        markers: [String],
        items: [[NativeMarkdownInline]]
    ) -> some View {
        let widestMarker = markers.max(by: { $0.count < $1.count }) ?? "•"
        return VStack(alignment: .leading, spacing: 4) {
            ForEach(items.indices, id: \.self) { index in
                listRow(
                    marker: markers[index],
                    widestMarker: widestMarker,
                    inlines: items[index]
                )
            }
        }
    }

    private func listRow(
        marker: String,
        widestMarker: String,
        inlines: [NativeMarkdownInline]
    ) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(widestMarker)
                .font(OpenCodeTypography.body.monospacedDigit())
                .hidden()
                .overlay(alignment: .trailing) {
                    Text(marker)
                        .font(OpenCodeTypography.body.monospacedDigit())
                        .foregroundStyle(OpenCodeColors.markdownListMarker)
                }
                .accessibilityHidden(true)
            inlineText(inlines, font: OpenCodeTypography.body, color: OpenCodeColors.textStrong, lineSpacing: 4)
        }
        .accessibilityElement(children: .combine)
    }

    private func inlineText(
        _ inlines: [NativeMarkdownInline],
        font: Font,
        color: Color,
        lineSpacing: CGFloat = 0
    ) -> some View {
        InlineMarkdownText(inlines: inlines, font: font, color: color, lineSpacing: lineSpacing)
    }

    @ViewBuilder
    private func codeBlock(
        code: String,
        spans: [NativeCodeSpan]
    ) -> some View {
        let forcePlain = Self.forcePlainStreamingCode(isStreaming: isStreaming, codeLength: code.count)
        ScrollView(.horizontal, showsIndicators: true) {
            DeferredHighlightedCodeText(code: code, spans: spans, forcePlain: forcePlain)
                .fixedSize(horizontal: true, vertical: false)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.codeBackground(or: OpenCodeColors.surfaceRaisedBase), in: RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
        .modifier(MarkdownCodeBlockTapTarget(code: code))
    }

    private func diffBlock(lines: [NativeDiffLine]) -> some View {
        ScrollView(.horizontal, showsIndicators: true) {
            DeferredHighlightedDiffBlock(lines: lines)
                .padding(.vertical, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.codeBackground(or: OpenCodeColors.surfaceRaisedBase), in: RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
        .modifier(MarkdownCodeBlockTapTarget(code: lines.map(\.text).joined(separator: "\n")))
    }

    private func table(
        header: [[NativeMarkdownInline]],
        rows: [[[NativeMarkdownInline]]]
    ) -> some View {
        let allRows = [header] + rows
        let columnCount = allRows.map(\.count).max() ?? 0
        let preferredWidths = Self.tableColumnWidths(rows: allRows, columnCount: columnCount)
        return MarkdownTableLayout(preferredWidths: preferredWidths) { availableWidths in
            tableRow(header, columnWidths: availableWidths, isHeader: true)
            ForEach(rows.indices, id: \.self) { index in
                tableRow(rows[index], columnWidths: availableWidths, isHeader: false)
            }
        }
    }

    private func tableRow(
        _ row: [[NativeMarkdownInline]],
        columnWidths: [CGFloat],
        isHeader: Bool
    ) -> some View {
        HStack(spacing: 0) {
            ForEach(columnWidths.indices, id: \.self) { index in
                let inlines = index < row.count ? row[index] : []
                inlineText(
                    inlines,
                    font: isHeader ? OpenCodeTypography.bodySecondary.weight(.medium) : OpenCodeTypography.bodySecondary,
                    color: OpenCodeColors.textStrong
                )
                .padding(.horizontal, Self.tableCellHorizontalPadding)
                .padding(.vertical, 8)
                .frame(width: columnWidths[index], alignment: .topLeading)
                .frame(maxHeight: .infinity, alignment: .topLeading)
                .background(isHeader ? OpenCodeColors.surfaceRaisedBase : OpenCodeColors.surfaceBase)
                .border(OpenCodeColors.borderWeakerBase, width: 0.5)
            }
        }
    }

    private static let tableCellMinWidth: CGFloat = 112
    private static let tableCellHorizontalPadding: CGFloat = 10

    private static func tableColumnWidths(
        rows: [[[NativeMarkdownInline]]],
        columnCount: Int
    ) -> [CGFloat] {
        let font = UIFont.systemFont(ofSize: 14, weight: .regular)
        return (0..<columnCount).map { column in
            let contentWidth =
                rows.map { row -> CGFloat in
                    guard column < row.count else { return 0 }
                    let plain = NativeMarkdownAttributedString.plainText(from: row[column])
                    return (plain as NSString).size(withAttributes: [.font: font]).width
                }
                .max() ?? 0
            return max(tableCellMinWidth, contentWidth + tableCellHorizontalPadding * 2)
        }
    }

    private var blockSpacing: CGFloat {
        switch variant {
        case .assistantMessage, .outgoingMessage:
            8
        case .standalone:
            12
        }
    }

    private func headingFont(_ level: Int) -> Font {
        switch level {
        case 1:
            OpenCodeTypography.title
        case 2:
            OpenCodeTypography.heading
        default:
            OpenCodeTypography.bodySecondary.weight(.medium)
        }
    }

    private func headingColor(_ level: Int) -> Color {
        switch level {
        case 1:
            OpenCodeColors.markdownH1
        case 2:
            OpenCodeColors.markdownH2
        default:
            OpenCodeColors.markdownH3
        }
    }
}

enum NativeMarkdownAttributedString {
    private struct CodeCacheKey: Hashable {
        let code: String
        let spans: [NativeCodeSpan]
        let paletteRevision: UInt64
    }

    nonisolated private final class CodeAttributedCache: @unchecked Sendable {
        private let lock = NSLock()
        private var key: CodeCacheKey?
        private var value: AttributedString?

        func value(for key: CodeCacheKey) -> AttributedString? {
            lock.lock()
            defer { lock.unlock() }
            guard self.key == key else { return nil }
            return value
        }

        func store(_ value: AttributedString, for key: CodeCacheKey) {
            lock.lock()
            defer { lock.unlock() }
            self.key = key
            self.value = value
        }
    }

    nonisolated private static let codeCache = CodeAttributedCache()

    static func plainText(from inlines: [NativeMarkdownInline]) -> String {
        var output = ""
        appendPlain(inlines, into: &output)
        return output
    }

    static func inline(
        _ inlines: [NativeMarkdownInline],
        font: Font,
        color: Color
    ) -> AttributedString {
        var output = AttributedString()
        append(inlines, into: &output, font: font, color: color)
        return output
    }

    static func code(
        _ code: String,
        spans: [NativeCodeSpan]
    ) -> AttributedString {
        let cacheKey = CodeCacheKey(
            code: code,
            spans: spans,
            paletteRevision: ThemeManager.shared.revision
        )
        if let cached = codeCache.value(for: cacheKey) {
            return cached
        }

        let output = codeUsingSwiftUI(code, spans: spans)
        codeCache.store(output, for: cacheKey)
        return output
    }

    /// Builds via UIKit colors so highlighting can leave the main actor.
    nonisolated static func code(
        _ code: String,
        spans: [NativeCodeSpan],
        palette: NativeCodeColorPalette
    ) -> AttributedString {
        let cacheKey = CodeCacheKey(
            code: code,
            spans: spans,
            paletteRevision: palette.cacheRevision
        )
        if let cached = codeCache.value(for: cacheKey) {
            return cached
        }

        let output = codeUsingUIKit(code, spans: spans, palette: palette)
        codeCache.store(output, for: cacheKey)
        return output
    }

    nonisolated private static func codeUsingUIKit(
        _ code: String,
        spans: [NativeCodeSpan],
        palette: NativeCodeColorPalette
    ) -> AttributedString {
        let font = UIFont.monospacedSystemFont(ofSize: 14, weight: .regular)
        let attributed = NSMutableAttributedString(
            string: code,
            attributes: [
                .font: font,
                .foregroundColor: palette.color(for: .plain),
            ]
        )
        let utf16Count = (code as NSString).length
        let sortedSpans = spans.sorted {
            let leftPriority = stylePriority(for: $0.role)
            let rightPriority = stylePriority(for: $1.role)
            if leftPriority != rightPriority { return leftPriority < rightPriority }
            let leftWidth = $0.end - $0.start
            let rightWidth = $1.end - $1.start
            if leftWidth != rightWidth { return leftWidth > rightWidth }
            if $0.start != $1.start { return $0.start < $1.start }
            return $0.end < $1.end
        }
        for span in sortedSpans {
            let start = max(0, min(span.start, utf16Count))
            let end = max(start, min(span.end, utf16Count))
            guard start < end else { continue }
            attributed.addAttribute(
                .foregroundColor,
                value: palette.color(for: span.role),
                range: NSRange(location: start, length: end - start)
            )
        }
        return AttributedString(attributed)
    }

    private static func codeUsingSwiftUI(
        _ code: String,
        spans: [NativeCodeSpan]
    ) -> AttributedString {
        var output = AttributedString(code)
        output.font = OpenCodeTypography.code
        output.foregroundColor = OpenCodeColors.codeText

        let sortedSpans = spans.sorted {
            let leftPriority = stylePriority(for: $0.role)
            let rightPriority = stylePriority(for: $1.role)
            if leftPriority != rightPriority { return leftPriority < rightPriority }
            let leftWidth = $0.end - $0.start
            let rightWidth = $1.end - $1.start
            if leftWidth != rightWidth { return leftWidth > rightWidth }
            if $0.start != $1.start { return $0.start < $1.start }
            return $0.end < $1.end
        }

        for span in sortedSpans {
            let start = max(0, min(span.start, code.utf16.count))
            let end = max(start, min(span.end, code.utf16.count))
            guard start < end,
                  let stringStart = stringIndex(in: code, utf16Offset: start),
                  let stringEnd = stringIndex(in: code, utf16Offset: end) else {
                continue
            }

            let startIndex = output.index(
                output.startIndex,
                offsetByCharacters: code.distance(from: code.startIndex, to: stringStart)
            )
            let endIndex = output.index(
                output.startIndex,
                offsetByCharacters: code.distance(from: code.startIndex, to: stringEnd)
            )
            output[startIndex..<endIndex].foregroundColor = color(for: span.role)
        }
        return output
    }

    private static func stringIndex(in value: String, utf16Offset: Int) -> String.Index? {
        let utf16Index = value.utf16.index(value.utf16.startIndex, offsetBy: utf16Offset)
        return String.Index(utf16Index, within: value)
    }

    private static func appendPlain(_ inlines: [NativeMarkdownInline], into output: inout String) {
        for inline in inlines {
            switch inline {
            case .text(let value), .code(let value):
                output.append(value)
            case .emphasis(let children), .strong(let children):
                appendPlain(children, into: &output)
            case .link(_, let children):
                appendPlain(children, into: &output)
            case .imageAlt(let alt):
                output.append("[image: \(alt)]")
            case .softBreak, .hardBreak:
                output.append("\n")
            }
        }
    }

    private static func append(
        _ inlines: [NativeMarkdownInline],
        into output: inout AttributedString,
        font: Font,
        color: Color
    ) {
        for inline in inlines {
            switch inline {
            case .text(let value):
                output.append(styled(value, font: font, color: color))
            case .code(let value):
                // Native attributed backgrounds have no CSS padding; side spaces
                // approximate desktop `padding: 2px 4px` horizontal inset.
                var code = AttributedString("\u{2009}\(value)\u{2009}")
                code.font = OpenCodeTypography.code.weight(.medium)
                code.foregroundColor = OpenCodeColors.markdownCodeText
                code.backgroundColor = OpenCodeColors.markdownCodeBackground
                output.append(code)
            case .emphasis(let children):
                append(children, into: &output, font: font.italic(), color: OpenCodeColors.markdownEm)
            case .strong(let children):
                append(children, into: &output, font: font.weight(.semibold), color: OpenCodeColors.markdownStrong)
            case .link(let destination, let children):
                var linkText = AttributedString()
                append(
                    children,
                    into: &linkText,
                    font: font.weight(.bold),
                    color: OpenCodeColors.markdownLinkText
                )
                let underline = Text.LineStyle(pattern: .solid, color: OpenCodeColors.markdownLinkUnderline)
                linkText.underlineStyle = underline
                if let url = URL(string: destination),
                   url.scheme == "http" || url.scheme == "https" {
                    linkText.link = url
                    // Re-assert chrome after `.link`; SwiftUI may otherwise tint accent.
                    linkText.foregroundColor = OpenCodeColors.markdownLinkText
                    linkText.underlineStyle = underline
                }
                output.append(linkText)
            case .imageAlt(let alt):
                output.append(styled("[image: \(alt)]", font: font.italic(), color: OpenCodeColors.markdownImage))
            case .softBreak, .hardBreak:
                output.append(styled("\n", font: font, color: color))
            }
        }
    }

    private static func styled(
        _ value: String,
        font: Font,
        color: Color
    ) -> AttributedString {
        var text = AttributedString(value)
        text.font = font
        text.foregroundColor = color
        return text
    }

    nonisolated private static func stylePriority(for role: NativeCodeTokenRole) -> Int {
        switch role {
        case .punctuation:
            0
        case .operatorToken:
            1
        case .plain:
            2
        case .variable:
            3
        case .property, .attribute:
            4
        case .function:
            5
        case .number:
            6
        case .constant:
            7
        case .string:
            8
        case .comment:
            9
        case .keyword:
            10
        case .type:
            11
        case .tag:
            12
        case .inserted:
            14
        case .deleted:
            15
        case .warning:
            16
        case .critical:
            17
        }
    }

    /// Every highlighted surface colors roles through `OpenCodeCodeColors`.
    private static func color(for role: NativeCodeTokenRole) -> Color {
        OpenCodeColors.code(role)
    }
}

/// UIKit colors captured on the main actor for off-main code highlighting.
struct NativeCodeColorPalette: Sendable {
    private let colors: [NativeCodeTokenRole: UIColor]
    private let plain: UIColor
    let cacheRevision: UInt64

    init(
        colors: [NativeCodeTokenRole: UIColor],
        plain: UIColor,
        cacheRevision: UInt64
    ) {
        self.colors = colors
        self.plain = plain
        self.cacheRevision = cacheRevision
    }

    @MainActor
    static func current() -> NativeCodeColorPalette {
        NativeCodeColorPalette(
            colors: Dictionary(
                uniqueKeysWithValues: NativeCodeTokenRole.allCases.map { ($0, UIColor(OpenCodeColors.code($0))) }
            ),
            plain: UIColor(OpenCodeColors.codeText),
            cacheRevision: ThemeManager.shared.revision
        )
    }

    nonisolated func color(for role: NativeCodeTokenRole) -> UIColor {
        colors[role] ?? plain
    }
}

private struct MarkdownTableLayout<Content: View>: View {
    let preferredWidths: [CGFloat]
    @ViewBuilder let content: ([CGFloat]) -> Content

    @State private var containerWidth: CGFloat = 0

    private var columnWidths: [CGFloat] {
        Self.expandedWidths(preferred: preferredWidths, available: containerWidth)
    }

    private var needsHorizontalScroll: Bool {
        guard containerWidth > 0, !preferredWidths.isEmpty else { return false }
        return preferredWidths.reduce(0, +) > containerWidth + 1
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: needsHorizontalScroll) {
            VStack(alignment: .leading, spacing: 0) {
                content(columnWidths)
            }
            .frame(minWidth: containerWidth > 0 ? containerWidth : nil, alignment: .leading)
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
            )
        }
        .background {
            GeometryReader { geo in
                Color.clear
                    .onAppear { containerWidth = geo.size.width }
                    .onChange(of: geo.size.width) { _, width in
                        containerWidth = width
                    }
            }
        }
    }

    private static func expandedWidths(preferred: [CGFloat], available: CGFloat) -> [CGFloat] {
        guard !preferred.isEmpty, available > 0 else { return preferred }
        let total = preferred.reduce(0, +)
        guard total < available else { return preferred }
        let share = (available - total) / CGFloat(preferred.count)
        return preferred.map { $0 + share }
    }
}

/// Makes a code or diff block its own tap target when the message offers
/// actions: a tap or long-press flashes the block and passes its text on.
/// Inner gestures win over the bubble's long-press, and the horizontal
/// scroll still cancels a tap that turns into a swipe.
private struct MarkdownCodeBlockTapTarget: ViewModifier {
    let code: String

    @Environment(\.markdownCodeBlockTapAction) private var tapAction
    @State private var isHighlighted = false

    func body(content: Content) -> some View {
        if let tapAction {
            content
                .overlay {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(OpenCodeColors.textStrong.opacity(isHighlighted ? 0.08 : 0))
                        .allowsHitTesting(false)
                }
                .contentShape(RoundedRectangle(cornerRadius: 6))
                .anchorPreference(key: MarkdownCodeBlockBoundsKey.self, value: .bounds) { [(code, $0)] }
                .onTapGesture { open(tapAction) }
                .onLongPressGesture { open(tapAction) }
                .accessibilityAction(named: "Code block actions") { open(tapAction) }
        } else {
            content
        }
    }

    private func open(_ tapAction: (String) -> Void) {
        isHighlighted = true
        // Fade on the next turn so the highlighted frame is committed first.
        DispatchQueue.main.async {
            withAnimation(.easeOut(duration: 0.3)) { isHighlighted = false }
        }
        tapAction(code)
    }
}

private struct InlineMarkdownText: View {
    let inlines: [NativeMarkdownInline]
    let font: Font
    let color: Color
    var lineSpacing: CGFloat = 0

    var body: some View {
        Text(NativeMarkdownAttributedString.inline(inlines, font: font, color: color))
            .lineSpacing(lineSpacing)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Applies prepared spans on first paint. Large streaming fences stay plain
/// via `forcePlain`. When colored, do not stack `.foregroundStyle` on the
/// attributed Text — SwiftUI double-paints and glyphs look corrupted.
private struct DeferredHighlightedCodeText: View {
    let code: String
    let spans: [NativeCodeSpan]
    let forcePlain: Bool

    var body: some View {
        if forcePlain {
            Text(code)
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.codeText)
        } else {
            Text(NativeMarkdownAttributedString.code(code, spans: spans))
                .font(OpenCodeTypography.code)
        }
    }
}

extension OpenCodeRenderedMarkdown {
    /// Format-always / color-later: never strip prepared spans while streaming.
    static func forcePlainStreamingCode(isStreaming _: Bool, codeLength _: Int) -> Bool {
        false
    }
}

private struct DeferredHighlightedDiffBlock: View {
    let lines: [NativeDiffLine]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(lines.indices, id: \.self) { index in
                let line = lines[index]
                Text(NativeMarkdownAttributedString.code(line.text, spans: line.spans))
                    .font(OpenCodeTypography.code)
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(minWidth: 320, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 1)
                    .background(diffLineBackground(line.kind))
            }
        }
    }

    private func diffLineBackground(_ kind: NativeDiffLineKind) -> Color {
        switch kind {
        case .added:
            OpenCodeColors.diffAdd.opacity(0.18)
        case .deleted:
            OpenCodeColors.diffDelete.opacity(0.16)
        case .header:
            OpenCodeColors.syntaxDiffUnknown.opacity(0.14)
        case .context:
            Color.clear
        }
    }
}

private struct NativeMarkdownBlockIdentityEntry: Identifiable {
    let identity: String
    let block: NativeMarkdownBlock

    var id: String { identity }
}

private extension NativeMarkdownDocument {
    var blocksWithStableIdentities: [NativeMarkdownBlockIdentityEntry] {
        var seenCounts: [String: Int] = [:]
        return blocks.map { block in
            let base = block.stableIdentity
            let count = seenCounts[base, default: 0]
            seenCounts[base] = count + 1
            let identity = count == 0 ? base : "\(base)-\(count)"
            return NativeMarkdownBlockIdentityEntry(identity: identity, block: block)
        }
    }
}

private extension NativeMarkdownBlock {
    var stableIdentity: String {
        switch self {
        case .paragraph(let inlines):
            return "p-\(inlines.stableCacheKey)"
        case .heading(let level, let inlines):
            return "h\(level)-\(inlines.stableCacheKey)"
        case .blockquote(let inlines):
            return "q-\(inlines.stableCacheKey)"
        case .bulletList(let items):
            return "ul-\(items.map(\.stableCacheKey).joined(separator: "|"))"
        case .orderedList(let start, let items):
            return "ol-\(start)-\(items.map(\.stableCacheKey).joined(separator: "|"))"
        case .codeBlock(let language, _, _):
            // Do not key on full code content — streaming tokens would tear down the view every publish.
            return "code-\(language ?? "")"
        case .diffBlock(let lines):
            return "diff-\(lines.map(\.text).joined().hashValue)"
        case .table(let header, let rows):
            return "table-\(header.flatMap { $0 }.stableCacheKey)-\(rows.count)"
        case .thematicBreak:
            return "hr"
        }
    }
}

private extension Array where Element == NativeMarkdownInline {
    var stableCacheKey: String {
        map(\.stableCacheKey).joined(separator: "\u{1f}")
    }

    var plainText: String {
        reduce(into: "") { result, inline in
            result.append(inline.plainText)
        }
    }
}

private extension NativeMarkdownInline {
    var stableCacheKey: String {
        switch self {
        case .text(let value):
            return "t:\(value)"
        case .code(let value):
            return "c:\(value)"
        case .emphasis(let children):
            return "e:\(children.stableCacheKey)"
        case .strong(let children):
            return "s:\(children.stableCacheKey)"
        case .link(let destination, let children):
            return "l:\(destination):\(children.stableCacheKey)"
        case .imageAlt(let alt):
            return "i:\(alt)"
        case .softBreak:
            return "sb"
        case .hardBreak:
            return "hb"
        }
    }

    var plainText: String {
        switch self {
        case .text(let value):
            return value
        case .code(let value):
            return value
        case .emphasis(let children):
            return children.plainText
        case .strong(let children):
            return children.plainText
        case .link(let destination, let children):
            let childText = children.plainText
            return childText.isEmpty ? destination : childText
        case .imageAlt(let alt):
            return "[image: \(alt)]"
        case .softBreak, .hardBreak:
            return "\n"
        }
    }
}

private struct RenderedMarkdownSizePreferenceKey: PreferenceKey {
    static var defaultValue: CGSize = .zero

    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

private extension View {
    func readRenderedMarkdownSize(onChange: @escaping (CGSize) -> Void) -> some View {
        background(
            GeometryReader { geometry in
                Color.clear.preference(key: RenderedMarkdownSizePreferenceKey.self, value: geometry.size)
            }
        )
        .onPreferenceChange(RenderedMarkdownSizePreferenceKey.self, perform: onChange)
    }
}

private struct RenderedMarkdownHeightReporter: ViewModifier {
    let isEnabled: Bool
    let onChange: (CGSize) -> Void

    func body(content: Content) -> some View {
        if isEnabled {
            content.readRenderedMarkdownSize(onChange: onChange)
        } else {
            content
        }
    }
}

#Preview("Rendered Markdown") {
    OpenCodeRenderedMarkdown(
        document: RenderedMarkdownGalleryMocks.samples[0].document,
        variant: .standalone
    )
    .padding()
    .openCodeTheme()
}
