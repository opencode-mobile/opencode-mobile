import SwiftUI

/// A Details section (the prototype's `.dsec`): a head with the name in `caption` medium `text3`,
/// an optional quiet suffix in `text4` and an optional count on the right in `mono`, over a panel
/// of `HexDetailRow`s. 14 pt below each section.
struct HexDetailsSection<Rows: View>: View {
    let title: Text
    var suffix: Text?
    var count: String?
    @ViewBuilder let rows: Rows

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 0) {
                (suffix.map { title + Text(verbatim: " ") + $0.foregroundStyle(HexColor.text4) } ?? title)
                    .hexFont(HexTextStyle.caption.weight(.medium))
                    .foregroundStyle(HexColor.text3)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                if let count {
                    Text(verbatim: count)
                        .hexFont(.mono)
                        .foregroundStyle(HexColor.text3)
                }
            }
            .frame(minHeight: 22)
            .padding(.horizontal, 2)
            VStack(spacing: 0) {
                rows
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HexColor.panel, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous).strokeBorder(HexColor.line, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
        }
        .padding(.bottom, 14)
    }
}

/// One Details row (the prototype's `.drow`): at least 40 pt, padding 7 × 14, a leading 16 pt icon
/// or dot, the title (`row`, or `code` for paths and commands, one line, ellipsised), an optional
/// second line in `footnote` `text2`, and a trailing slot. The done variant has its title in `text3`.
struct HexDetailRow<Trailing: View>: View {
    enum Leading {
        case icon(HexIcon, Color)
        case dot(HexDot.Tone)
        case todo(HexTodoIcon)
        case none
    }

    var leading: Leading = .none
    /// The title; already formatted when it carries inline code.
    let title: Text
    var code = false
    /// Wraps instead of cutting (to-do items, never cut).
    var wraps = false
    var detail: Text?
    var detailLines = 1
    var done = false
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(alignment: wraps ? .firstTextBaseline : .center, spacing: 10) {
            leadingView
            VStack(alignment: .leading, spacing: 1) {
                title
                    .hexFont(code ? .code : HexTextStyle.row.weight(.medium))
                    .foregroundStyle(done ? HexColor.text3 : HexColor.text1)
                    .lineLimit(wraps ? nil : 1)
                    .truncationMode(.tail)
                    .fixedSize(horizontal: false, vertical: wraps)
                if let detail {
                    detail
                        .hexFont(.footnote)
                        .foregroundStyle(HexColor.text2)
                        .lineLimit(detailLines)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .frame(minHeight: 40)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var leadingView: some View {
        switch leading {
        case let .icon(icon, color):
            HexIconView(icon, size: 14)
                .foregroundStyle(color)
                .frame(width: 16)
        case let .dot(tone):
            HexDot(tone: tone)
                .frame(width: 16)
        case let .todo(icon):
            HexTodoIconView(icon)
        case .none:
            EmptyView()
        }
    }
}

extension HexDetailRow where Trailing == EmptyView {
    init(leading: Leading = .none, title: Text, code: Bool = false, wraps: Bool = false, detail: Text? = nil, detailLines: Int = 1, done: Bool = false) {
        self.init(leading: leading, title: title, code: code, wraps: wraps, detail: detail, detailLines: detailLines, done: done) { EmptyView() }
    }
}

/// A row's trailing time or count (`mono`, `text3`).
struct HexDetailTime: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .hexFont(.mono)
            .foregroundStyle(HexColor.text3)
            .monospacedDigit()
            .lineLimit(1)
    }
}

/// A row's trailing ›.
struct HexDetailChevron: View {
    var body: some View {
        HexIconView(.chevR, size: HexIconSize.small)
            .foregroundStyle(HexColor.text3)
    }
}

/// The to-do status icons (the prototype's `SI.todo`, `SI.doing`, `SI.done`), 16 × 16: an empty
/// `text3` ring, a `solid` ring half filled, and a `text2` disc with a check.
enum HexTodoIcon: String, CaseIterable, Sendable {
    case todoPending
    case todoDoing
    case todoDone
}

struct HexTodoIconView: View {
    let icon: HexTodoIcon

    init(_ icon: HexTodoIcon) {
        self.icon = icon
    }

    var body: some View {
        Canvas { context, size in
            let scale = size.width / 16
            func circle(_ radius: CGFloat) -> Path {
                Path(ellipseIn: CGRect(x: (8 - radius) * scale, y: (8 - radius) * scale, width: radius * 2 * scale, height: radius * 2 * scale))
            }
            switch icon {
            case .todoPending:
                context.stroke(circle(6), with: .color(HexColor.text3), lineWidth: 1.5 * scale)
            case .todoDoing:
                context.stroke(circle(6), with: .color(HexColor.solid), lineWidth: 1.5 * scale)
                var half = Path()
                half.move(to: CGPoint(x: 8 * scale, y: 4.5 * scale))
                half.addArc(center: CGPoint(x: 8 * scale, y: 8 * scale), radius: 3.5 * scale, startAngle: .degrees(-90), endAngle: .degrees(90), clockwise: false)
                half.closeSubpath()
                context.fill(half, with: .color(HexColor.solid))
            case .todoDone:
                context.fill(circle(7), with: .color(HexColor.text2))
                var check = Path()
                check.move(to: CGPoint(x: 5.2 * scale, y: 8.2 * scale))
                check.addLine(to: CGPoint(x: 7.1 * scale, y: 10.1 * scale))
                check.addLine(to: CGPoint(x: 10.9 * scale, y: 6.1 * scale))
                context.stroke(check, with: .color(HexColor.background), style: StrokeStyle(lineWidth: 1.6 * scale, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(width: 16, height: 16)
        .accessibilityHidden(true)
    }
}
