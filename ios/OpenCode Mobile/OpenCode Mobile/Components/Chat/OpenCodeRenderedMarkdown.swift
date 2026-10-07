import OpenCodeMarkdownCore
import SwiftUI
import UIKit

enum OpenCodeRenderedMarkdownVariant: String, CaseIterable, Sendable {
    case assistantMessage
    case outgoingMessage
    case standalone
}

enum OpenCodeRenderedMarkdownDetector: Sendable {
    nonisolated static func shouldRender(_ text: String) -> Bool {
        OpenCodeMarkdownDetector.shared.shouldRender(text: text)
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
    let onTextTap: (() -> Void)?

    @State private var measuredHeight: CGFloat
    @State private var linkHandledTap = false

    init(
        document: NativeMarkdownDocument,
        variant: OpenCodeRenderedMarkdownVariant,
        isStreaming: Bool = false,
        initialHeight: CGFloat = 1,
        measuresHeight: Bool = false,
        onHeightChange: @escaping (CGFloat) -> Void = { _ in },
        onLinkTap: @escaping (URL) -> Void = { url in UIApplication.shared.open(url) },
        onTextTap: (() -> Void)? = nil
    ) {
        self.document = document
        self.variant = variant
        self.isStreaming = isStreaming
        self.initialHeight = max(1, initialHeight)
        self.measuresHeight = measuresHeight
        self.onHeightChange = onHeightChange
        self.onLinkTap = onLinkTap
        self.onTextTap = onTextTap
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
        .environment(\.markdownAllowsTextSelection, variant != .outgoingMessage)
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
            linkHandledTap = true
            onLinkTap(url)
            return .handled
        })
        .modifier(MessageTextTapGesture(onTextTap: onTextTap, linkHandledTap: $linkHandledTap))
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
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
    }

    private func diffBlock(lines: [NativeDiffLine]) -> some View {
        ScrollView(.horizontal, showsIndicators: true) {
            DeferredHighlightedDiffBlock(lines: lines)
                .padding(.vertical, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
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
        output.foregroundColor = OpenCodeColors.textStrong

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

    private static func color(for role: NativeCodeTokenRole) -> Color {
        switch role {
        case .plain:
            OpenCodeColors.textStrong
        case .comment:
            OpenCodeColors.syntaxComment
        case .keyword:
            OpenCodeColors.syntaxKeyword
        case .type:
            OpenCodeColors.syntaxType
        case .variable:
            OpenCodeColors.syntaxVariable
        case .property:
            OpenCodeColors.syntaxConstant
        case .constant:
            OpenCodeColors.syntaxConstant
        case .function:
            OpenCodeColors.syntaxPrimitive
        case .number, .attribute:
            OpenCodeColors.syntaxProperty
        case .string:
            OpenCodeColors.syntaxString
        case .operatorToken:
            OpenCodeColors.syntaxOperator
        case .punctuation:
            OpenCodeColors.syntaxPunctuation
        case .tag:
            OpenCodeColors.syntaxInfo
        case .inserted:
            OpenCodeColors.diffAdd
        case .deleted:
            OpenCodeColors.diffDelete
        case .warning:
            OpenCodeColors.syntaxDiffUnknown
        case .critical:
            OpenCodeColors.syntaxCritical
        }
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
            colors: [
                .plain: UIColor(OpenCodeColors.textStrong),
                .comment: UIColor(OpenCodeColors.syntaxComment),
                .keyword: UIColor(OpenCodeColors.syntaxKeyword),
                .type: UIColor(OpenCodeColors.syntaxType),
                .variable: UIColor(OpenCodeColors.syntaxVariable),
                .property: UIColor(OpenCodeColors.syntaxConstant),
                .constant: UIColor(OpenCodeColors.syntaxConstant),
                .function: UIColor(OpenCodeColors.syntaxPrimitive),
                .number: UIColor(OpenCodeColors.syntaxProperty),
                .attribute: UIColor(OpenCodeColors.syntaxProperty),
                .string: UIColor(OpenCodeColors.syntaxString),
                .operatorToken: UIColor(OpenCodeColors.syntaxOperator),
                .punctuation: UIColor(OpenCodeColors.syntaxPunctuation),
                .tag: UIColor(OpenCodeColors.syntaxInfo),
                .inserted: UIColor(OpenCodeColors.diffAdd),
                .deleted: UIColor(OpenCodeColors.diffDelete),
                .warning: UIColor(OpenCodeColors.syntaxDiffUnknown),
                .critical: UIColor(OpenCodeColors.syntaxCritical),
            ],
            plain: UIColor(OpenCodeColors.textStrong),
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

private struct MarkdownAllowsTextSelectionKey: EnvironmentKey {
    static let defaultValue = true
}

private extension EnvironmentValues {
    var markdownAllowsTextSelection: Bool {
        get { self[MarkdownAllowsTextSelectionKey.self] }
        set { self[MarkdownAllowsTextSelectionKey.self] = newValue }
    }
}

private struct MarkdownTextSelectionModifier: ViewModifier {
    @Environment(\.markdownAllowsTextSelection) private var allowsTextSelection

    func body(content: Content) -> some View {
        content.textSelection(allowsTextSelection ? .enabled : .disabled)
    }
}

/// Quick tap opens message actions without taking the touch away from a link.
/// A long-press arms selection; the next tap only clears that selection.
private struct MessageTextTapGesture: ViewModifier {
    let onTextTap: (() -> Void)?
    @Binding var linkHandledTap: Bool

    func body(content: Content) -> some View {
        if let onTextTap {
            content.background {
                MessageTextTapInstaller(onTextTap: onTextTap, linkHandledTap: $linkHandledTap)
            }
        } else {
            content
        }
    }
}

private struct MessageTextTapInstaller: UIViewRepresentable {
    var onTextTap: () -> Void
    @Binding var linkHandledTap: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(onTextTap: onTextTap, linkHandledTap: $linkHandledTap)
    }

    func makeUIView(context: Context) -> MessageTextTapView {
        let view = MessageTextTapView()
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ uiView: MessageTextTapView, context: Context) {
        context.coordinator.onTextTap = onTextTap
        context.coordinator.linkHandledTap = $linkHandledTap
        context.coordinator.boundsView = uiView
        uiView.coordinator = context.coordinator
        uiView.installIfNeeded()
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onTextTap: () -> Void
        var linkHandledTap: Binding<Bool>
        weak var boundsView: UIView?
        var selectionArmed = false

        init(onTextTap: @escaping () -> Void, linkHandledTap: Binding<Bool>) {
            self.onTextTap = onTextTap
            self.linkHandledTap = linkHandledTap
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard contains(gesture) else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if self.linkHandledTap.wrappedValue {
                    self.linkHandledTap.wrappedValue = false
                    self.selectionArmed = false
                    return
                }
                if self.selectionArmed {
                    self.selectionArmed = false
                    return
                }
                self.onTextTap()
            }
        }

        @objc func longPressed(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began, contains(gesture) else { return }
            selectionArmed = true
        }

        private func contains(_ gesture: UIGestureRecognizer) -> Bool {
            guard let boundsView else { return false }
            return boundsView.bounds.contains(gesture.location(in: boundsView))
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }
    }
}

private final class MessageTextTapView: UIView {
    weak var coordinator: MessageTextTapInstaller.Coordinator?
    private var installed = false
    private var recognizers: [UIGestureRecognizer] = []

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        nil
    }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        installIfNeeded()
    }

    override func willMove(toSuperview newSuperview: UIView?) {
        if newSuperview == nil {
            recognizers.forEach { $0.view?.removeGestureRecognizer($0) }
            recognizers.removeAll()
            installed = false
        }
        super.willMove(toSuperview: newSuperview)
    }

    func installIfNeeded() {
        guard let superview, let coordinator, !installed else { return }
        installed = true
        coordinator.boundsView = self
        let tap = UITapGestureRecognizer(target: coordinator, action: #selector(MessageTextTapInstaller.Coordinator.tapped(_:)))
        let longPress = UILongPressGestureRecognizer(
            target: coordinator,
            action: #selector(MessageTextTapInstaller.Coordinator.longPressed(_:))
        )
        for gesture in [tap, longPress] {
            gesture.cancelsTouchesInView = false
            gesture.delaysTouchesBegan = false
            gesture.delaysTouchesEnded = false
            gesture.delegate = coordinator
            superview.addGestureRecognizer(gesture)
            recognizers.append(gesture)
        }
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
            .modifier(MarkdownTextSelectionModifier())
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
                .foregroundStyle(OpenCodeColors.textStrong)
                .modifier(MarkdownTextSelectionModifier())
        } else {
            Text(NativeMarkdownAttributedString.code(code, spans: spans))
                .font(OpenCodeTypography.code)
                .modifier(MarkdownTextSelectionModifier())
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
                    .modifier(MarkdownTextSelectionModifier())
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

nonisolated struct NativeMarkdownDocument: Decodable, Sendable {
    let renderTreeVersion: Int
    let sourceHash: Int
    let blocks: [NativeMarkdownBlock]
    let diagnostics: [NativeMarkdownDiagnostic]

    init(
        renderTreeVersion: Int = 1,
        sourceHash: Int,
        blocks: [NativeMarkdownBlock],
        diagnostics: [NativeMarkdownDiagnostic] = []
    ) {
        self.renderTreeVersion = renderTreeVersion
        self.sourceHash = sourceHash
        self.blocks = blocks
        self.diagnostics = diagnostics
    }

    var isLikelyExpandable: Bool {
        let visualLines = blocks.reduce(0) { total, block in
            switch block {
            case .codeBlock(_, let code, _):
                return total + code.components(separatedBy: "\n").count + 1
            case .diffBlock(let lines):
                return total + lines.count + 1
            case .table(_, let rows):
                return total + rows.count + 2
            case .bulletList(let items), .orderedList(_, let items):
                return total + items.count
            case .blockquote, .heading:
                return total + 2
            case .paragraph, .thematicBreak:
                return total + 1
            }
        }
        return visualLines > 6
    }
}

nonisolated enum NativeMarkdownBlock: Decodable, Sendable {
    case paragraph([NativeMarkdownInline])
    case heading(level: Int, inlines: [NativeMarkdownInline])
    case blockquote([NativeMarkdownInline])
    case bulletList([[NativeMarkdownInline]])
    case orderedList(start: Int, items: [[NativeMarkdownInline]])
    case codeBlock(language: String?, code: String, spans: [NativeCodeSpan])
    case diffBlock([NativeDiffLine])
    case table(header: [[NativeMarkdownInline]], rows: [[[NativeMarkdownInline]]])
    case thematicBreak

    private enum CodingKeys: String, CodingKey {
        case type
        case inlines
        case level
        case items
        case start
        case language
        case code
        case lines
        case spans
        case header
        case rows
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "paragraph":
            self = .paragraph(try container.decode([NativeMarkdownInline].self, forKey: .inlines))
        case "heading":
            self = .heading(
                level: try container.decode(Int.self, forKey: .level),
                inlines: try container.decode([NativeMarkdownInline].self, forKey: .inlines)
            )
        case "blockquote":
            self = .blockquote(try container.decode([NativeMarkdownInline].self, forKey: .inlines))
        case "bulletList":
            self = .bulletList(try container.decode([NativeMarkdownListItem].self, forKey: .items).map(\.inlines))
        case "orderedList":
            self = .orderedList(
                start: try container.decode(Int.self, forKey: .start),
                items: try container.decode([NativeMarkdownListItem].self, forKey: .items).map(\.inlines)
            )
        case "codeBlock":
            self = .codeBlock(
                language: try container.decodeIfPresent(String.self, forKey: .language),
                code: try container.decode(String.self, forKey: .code),
                spans: try container.decode([NativeCodeSpan].self, forKey: .spans)
            )
        case "diffBlock":
            self = .diffBlock(try container.decode([NativeDiffLine].self, forKey: .lines))
        case "table":
            let header = try container.decode(NativeMarkdownTableRow.self, forKey: .header)
            let rows = try container.decode([NativeMarkdownTableRow].self, forKey: .rows)
            self = .table(
                header: header.cells.map(\.inlines),
                rows: rows.map { row in row.cells.map(\.inlines) }
            )
        case "thematicBreak":
            self = .thematicBreak
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported markdown block type: \(type)"
            )
        }
    }
}

struct NativeMarkdownListItem: Decodable, Sendable {
    let inlines: [NativeMarkdownInline]
}

struct NativeMarkdownTableRow: Decodable, Sendable {
    let cells: [NativeMarkdownTableCell]
}

struct NativeMarkdownTableCell: Decodable, Sendable {
    let inlines: [NativeMarkdownInline]
}

nonisolated enum NativeMarkdownInline: Decodable, Sendable {
    case text(String)
    case emphasis([NativeMarkdownInline])
    case strong([NativeMarkdownInline])
    case code(String)
    case link(destination: String, children: [NativeMarkdownInline])
    case imageAlt(String)
    case softBreak
    case hardBreak

    private enum CodingKeys: String, CodingKey {
        case type
        case text
        case children
        case code
        case destination
        case alt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        switch type {
        case "text":
            self = .text(try container.decode(String.self, forKey: .text))
        case "emphasis":
            self = .emphasis(try container.decode([NativeMarkdownInline].self, forKey: .children))
        case "strong":
            self = .strong(try container.decode([NativeMarkdownInline].self, forKey: .children))
        case "code":
            self = .code(try container.decode(String.self, forKey: .code))
        case "link":
            self = .link(
                destination: try container.decode(String.self, forKey: .destination),
                children: try container.decode([NativeMarkdownInline].self, forKey: .children)
            )
        case "imageAlt":
            self = .imageAlt(try container.decode(String.self, forKey: .alt))
        case "softBreak":
            self = .softBreak
        case "hardBreak":
            self = .hardBreak
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unsupported markdown inline type: \(type)"
            )
        }
    }
}

nonisolated struct NativeCodeSpan: Decodable, Hashable, Sendable {
    let start: Int
    let end: Int
    let role: NativeCodeTokenRole
}

nonisolated enum NativeCodeTokenRole: String, Decodable, Hashable, Sendable {
    case plain = "Plain"
    case comment = "Comment"
    case keyword = "Keyword"
    case string = "String"
    case number = "Number"
    case type = "Type"
    case function = "Function"
    case property = "Property"
    case variable = "Variable"
    case constant = "Constant"
    case operatorToken = "Operator"
    case punctuation = "Punctuation"
    case tag = "Tag"
    case attribute = "Attribute"
    case inserted = "Inserted"
    case deleted = "Deleted"
    case warning = "Warning"
    case critical = "Critical"
}

nonisolated struct NativeDiffLine: Decodable, Sendable {
    let kind: NativeDiffLineKind
    let text: String
    let spans: [NativeCodeSpan]
}

nonisolated enum NativeDiffLineKind: String, Decodable, Sendable {
    case context = "Context"
    case added = "Added"
    case deleted = "Deleted"
    case header = "Header"
}

nonisolated struct NativeMarkdownDiagnostic: Decodable, Sendable {
    let kind: String
    let message: String
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
