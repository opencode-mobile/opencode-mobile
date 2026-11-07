import SwiftUI

/// The in-app browser's page (the prototype's `.sheet.browser`, `.sv-top`, `.sv-bar`), around a web
/// view. Top bar on `panel` with a `line` bottom border: Done (`button`, `text1`), the page title
/// (`callout`, `text1`, one line) over the host (`caption`, `text3`), and Reload; a 2 pt `text1`
/// progress line on its bottom edge while loading. Bottom bar on `panel` with a `line` top border:
/// Back and Forward (35 % when there's nowhere to go) and an optional "Open in Safari". An error
/// fills the page area: a `headline` title, a `body` `text2` detail and a regular `HexButton`. No
/// lock icon: the pages are plain HTTP.
/// What the page area shows in place of a page that failed to load.
struct HexBrowserFailure: Equatable {
    var title: String
    var detail: String
    var button: String
}

struct HexBrowser<Page: View>: View {
    let title: String
    let host: String
    var progress: Double?
    var canGoBack = false
    var canGoForward = false
    var failure: HexBrowserFailure?
    var openSystemTitle: LocalizedStringResource? = LocalizedStringResource("browser.open_system", defaultValue: "Open in Safari")
    let onDone: () -> Void
    let onReload: () -> Void
    var onBack: () -> Void = {}
    var onForward: () -> Void = {}
    var onOpenSystem: () -> Void = {}
    var onRetry: () -> Void = {}
    @ViewBuilder let page: Page

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ZStack {
                page
                if let failure {
                    error(failure)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            bottomBar
        }
        .background(HexColor.background.ignoresSafeArea())
        // The two-finger scrub closes it, as Done does.
        .accessibilityAction(.escape, onDone)
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button(action: onDone) {
                Text(LocalizedStringResource("common.done", defaultValue: "Done"))
                    .hexFont(.button)
                    .foregroundStyle(HexColor.text1)
                    .frame(minWidth: 54, minHeight: HexSize.touch, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(HexPressStyle())
            VStack(spacing: 1) {
                Text(verbatim: title)
                    .hexFont(.callout)
                    .foregroundStyle(HexColor.text1)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .accessibilityAddTraits(.isHeader)
                Text(verbatim: host)
                    .hexFont(.caption)
                    .foregroundStyle(HexColor.text3)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            HexIconButton(.refresh, label: LocalizedStringResource("browser.reload", defaultValue: "Reload"), action: onReload)
                .frame(minWidth: 54, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 2)
        .background(HexColor.panel.ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) {
            ZStack(alignment: .leading) {
                Rectangle().fill(HexColor.line).frame(height: 1)
                if let progress {
                    GeometryReader { proxy in
                        Rectangle()
                            .fill(HexColor.text1)
                            .frame(width: proxy.size.width * max(0.05, min(1, progress)), height: 2)
                    }
                    .frame(height: 2)
                    .accessibilityElement()
                    .accessibilityLabel(Text(LocalizedStringResource("browser.loading", defaultValue: "Loading")))
                }
            }
        }
    }

    private var bottomBar: some View {
        HStack(spacing: 4) {
            HexIconButton(.chevL, label: LocalizedStringResource("browser.back", defaultValue: "Back"), enabled: canGoBack, action: onBack)
            HexIconButton(.chevR, label: LocalizedStringResource("browser.forward", defaultValue: "Forward"), enabled: canGoForward, action: onForward)
            Spacer(minLength: 8)
            if let openSystemTitle {
                Button(action: onOpenSystem) {
                    HStack(spacing: 6) {
                        Text(openSystemTitle)
                            .hexFont(.button)
                        HexIconView(.out, size: HexIconSize.regular)
                    }
                    .foregroundStyle(HexColor.text2)
                    .padding(.horizontal, 12)
                    .frame(minHeight: HexSize.touch)
                    .contentShape(Rectangle())
                }
                .buttonStyle(HexPressStyle())
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 2)
        .background(HexColor.panel.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(HexColor.line).frame(height: 1) }
    }

    private func error(_ failure: HexBrowserFailure) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: failure.title)
                .hexFont(.headline)
                .foregroundStyle(HexColor.text1)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            SummaryText(text: failure.detail, style: .body)
                .foregroundStyle(HexColor.text2)
                .fixedSize(horizontal: false, vertical: true)
            HexButton(text: Text(verbatim: failure.button), icon: .refresh, size: .regular, action: onRetry)
                .fixedSize()
                .padding(.top, 10)
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(HexColor.background)
    }
}
