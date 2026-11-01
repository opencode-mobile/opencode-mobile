import SwiftUI
import UIKit

enum SessionFailureSheetAction: Equatable {
    case pickModel
    case compact
    case copyCaption
}

struct SessionChatToolDetailTarget: Equatable {
    let title: String
    let body: String
    /// Render `body` as markdown (a compaction summary) instead of plain tool output.
    var bodyIsMarkdown: Bool = false
    /// Provider response: monospace, with a copy button.
    var monospace: Bool = false
    var sheetAction: SessionFailureSheetAction? = nil
    var sheetActionLabel: String? = nil
    /// Live retry link, shown when this sheet was opened from the composer status.
    var sheetLinkLabel: String? = nil
    var sheetLink: String? = nil
    /// Typed inspector body. Failure sheets and compaction summaries leave this nil.
    var presentation: ToolResultPresentation? = nil

    /// Inspector layout, unless this sheet is a markdown compaction summary.
    var inspectorPresentation: ToolResultPresentation? {
        bodyIsMarkdown ? nil : presentation
    }

    /// Sheet for a failure mark. Copy of a provider body stays on the monospace body.
    /// Copy with no body, Compact, and Pick a model are the sheet action.
    static func failure(
        text: String,
        providerBody: String?,
        action: FailureAction?,
        actionLabel: String?,
        linkLabel: String? = nil,
        link: String? = nil
    ) -> SessionChatToolDetailTarget? {
        let body = nonEmptyTrimmed(providerBody)
        let label = nonEmptyTrimmed(actionLabel)
        let sheetAction: SessionFailureSheetAction? = {
            guard let action, label != nil else { return nil }
            if action == .copy, body != nil { return nil }
            switch action {
            case .copy: return .copyCaption
            case .pickModel: return .pickModel
            case .compact: return .compact
            }
        }()
        let resolvedLink = nonEmptyTrimmed(link)
        let resolvedLinkLabel = nonEmptyTrimmed(linkLabel).flatMap { resolvedLink == nil ? nil : $0 }
        if body == nil, sheetAction == nil, resolvedLinkLabel == nil { return nil }
        return SessionChatToolDetailTarget(
            title: body == nil ? text : "Provider response",
            body: body ?? "",
            monospace: body != nil,
            sheetAction: sheetAction,
            sheetActionLabel: sheetAction == nil ? nil : label,
            sheetLinkLabel: resolvedLinkLabel,
            sheetLink: resolvedLinkLabel == nil ? nil : resolvedLink
        )
    }
}

private func nonEmptyTrimmed(_ value: String?) -> String? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
    return trimmed
}

struct SessionChatToolDetailSheetModifier: ViewModifier {
    @Binding var target: SessionChatToolDetailTarget?
    var onFailureAction: (FailureAction) -> Void = { _ in }
    var onOpenLink: (URL) -> Void = { _ in }

    func body(content: Content) -> some View {
        content
            .openCodeBottomSheet(
                isPresented: sheetPresentedBinding,
                title: .unlocalized(sheetTitle),
                pinnedTop: {
                    if let presentation = inspector {
                        ToolResultPinnedHeader(presentation: presentation)
                            .openCodeBottomSheetContentInset()
                    }
                },
                content: {
                    SessionChatToolDetailSheetBody(target: target)
                },
                pinnedBottom: {
                    if showsCopy, let presentation = inspector {
                        ToolResultCopyFooter(raw: presentation.raw)
                    } else {
                        SessionChatToolDetailSheetActions(
                            target: target,
                            onFailureAction: { action in
                                if action != .copy {
                                    target = nil
                                }
                                onFailureAction(action)
                            },
                            onOpenLink: onOpenLink
                        )
                    }
                }
            )
    }

    private var inspector: ToolResultPresentation? {
        target?.inspectorPresentation
    }

    private var sheetTitle: String {
        inspector?.title ?? target?.title ?? "Tool"
    }

    /// Copy stays off a failure sheet so Compact, Pick a model, and the caption copy are unchanged.
    private var showsCopy: Bool {
        guard inspector?.copiesOriginal == true else { return false }
        let label = target?.sheetActionLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasAction = target?.sheetAction != nil && label?.isEmpty == false
        let link = target?.sheetLinkLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasLink = link?.isEmpty == false && target?.sheetLink?.isEmpty == false
        return !hasAction && !hasLink
    }

    private var sheetPresentedBinding: Binding<Bool> {
        Binding(
            get: { target != nil },
            set: { presented in
                if !presented {
                    ProductAnalytics.logUserAction(
                        screenId: ProductAnalytics.Screen.sessionToolDetail,
                        actionId: ProductAnalytics.Action.toolDetailDismiss
                    )
                    target = nil
                }
            }
        )
    }
}

/// Plain tool output, or rendered markdown once a markdown body is parsed
/// off the main actor.
private struct SessionChatToolDetailBody: View {
    let text: String
    let isMarkdown: Bool
    var monospace: Bool = false

    @State private var document: NativeMarkdownDocument?

    var body: some View {
        Group {
            if isMarkdown, let document {
                OpenCodeRenderedMarkdown(document: document, variant: .standalone)
            } else if monospace {
                VStack(alignment: .leading, spacing: 8) {
                    Button("Copy") {
                        UIPasteboard.general.string = text
                    }
                    .buttonStyle(.plain)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    Text(text)
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(OpenCodeColors.textStrong)
                }
            } else {
                Text(text)
                    .font(OpenCodeTypography.body)
                    .foregroundStyle(OpenCodeColors.textStrong)
            }
        }
        .task(id: isMarkdown ? text : nil) {
            guard isMarkdown, !text.isEmpty else {
                document = nil
                return
            }
            let source = text
            document = await Task.detached(priority: .userInitiated) {
                OpenCodeRenderedMarkdownParser.parse(source, variant: .standalone)
            }.value
        }
    }
}

/// A detail's body inside a bottom sheet: markdown, monospace provider body,
/// or plain text, inset like sheet content. Shared by the tool detail sheet
/// and the activity sheet's detail mode.
struct SessionChatToolDetailSheetBody: View {
    let target: SessionChatToolDetailTarget?

    var body: some View {
        if let presentation = target?.inspectorPresentation {
            ToolResultBody(presentation: presentation)
                .padding(.vertical, 12)
                .modifier(ToolResultBodyInset(fullBleed: presentation.isFullBleed))
        } else {
            let text = target?.body ?? ""
            let showsMonospace = target?.monospace == true && !text.isEmpty
            let showsBody = target?.bodyIsMarkdown == true || showsMonospace || !text.isEmpty
            Group {
                if showsBody {
                    SessionChatToolDetailBody(
                        text: text,
                        isMarkdown: target?.bodyIsMarkdown ?? false,
                        monospace: showsMonospace
                    )
                    .padding(.vertical, 12)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .openCodeBottomSheetContentInset()
        }
    }
}

/// Failure action and retry link, pinned in the footer so they stay visible
/// above a long provider response. Renders nothing when there are none.
private struct SessionChatToolDetailSheetActions: View {
    let target: SessionChatToolDetailTarget?
    let onFailureAction: (FailureAction) -> Void
    let onOpenLink: (URL) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let action = target?.sheetAction,
               let label = target?.sheetActionLabel?.trimmingCharacters(in: .whitespacesAndNewlines),
               !label.isEmpty
            {
                OpenCodeButton(variant: .fullWidthText(title: .unlocalized(label))) {
                    switch action {
                    case .copyCaption:
                        UIPasteboard.general.string = target?.title
                    case .pickModel:
                        onFailureAction(.pickModel)
                    case .compact:
                        onFailureAction(.compact)
                    }
                }
            }
            if let label = target?.sheetLinkLabel?.trimmingCharacters(in: .whitespacesAndNewlines),
               !label.isEmpty,
               let url = ExternalLinkPolicy.openableURL(target?.sheetLink)
            {
                OpenCodeButton(variant: .fullWidthText(title: .unlocalized(label))) {
                    onOpenLink(url)
                }
            }
        }
    }
}

extension View {
    func sessionChatToolDetailSheet(
        target: Binding<SessionChatToolDetailTarget?>,
        onFailureAction: @escaping (FailureAction) -> Void = { _ in },
        onOpenLink: @escaping (URL) -> Void = { _ in }
    ) -> some View {
        modifier(
            SessionChatToolDetailSheetModifier(
                target: target,
                onFailureAction: onFailureAction,
                onOpenLink: onOpenLink
            )
        )
    }
}
