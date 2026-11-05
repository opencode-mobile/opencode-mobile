import SwiftUI

/// What sits at the trailing edge of a row.
enum HexRowTrailing: Equatable {
    case none
    case chevron
    case check
    /// A short caption, for example "2h ago".
    case caption(String)
}

/// Settings and first-run row, 48 pt minimum: leading icon, title, optional subtitle and value,
/// and a trailing chevron or check.
struct HexRow: View {
    let icon: HexIcon?
    let title: Text
    var subtitle: String?
    var value: String?
    var trailing: HexRowTrailing = .chevron
    let action: () -> Void

    init(
        icon: HexIcon? = nil,
        _ title: LocalizedStringResource,
        subtitle: String? = nil,
        value: String? = nil,
        trailing: HexRowTrailing = .chevron,
        action: @escaping () -> Void
    ) {
        self.init(icon: icon, text: Text(title), subtitle: subtitle, value: value, trailing: trailing, action: action)
    }

    /// Text that is not translated, such as Settings' English-only Developer section.
    init(
        icon: HexIcon? = nil,
        verbatim title: String,
        subtitle: String? = nil,
        value: String? = nil,
        trailing: HexRowTrailing = .chevron,
        action: @escaping () -> Void
    ) {
        self.init(icon: icon, text: Text(verbatim: title), subtitle: subtitle, value: value, trailing: trailing, action: action)
    }

    init(
        icon: HexIcon? = nil,
        text title: Text,
        subtitle: String? = nil,
        value: String? = nil,
        trailing: HexRowTrailing = .chevron,
        action: @escaping () -> Void
    ) {
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.trailing = trailing
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon {
                    HexIconView(icon, size: HexIconSize.regular)
                        .foregroundStyle(HexColor.text2)
                        .frame(width: 24)
                }
                VStack(alignment: .leading, spacing: 1) {
                    title
                        .hexFont(HexTextStyle.row.weight(.medium))
                        .foregroundStyle(HexColor.text1)
                    if let subtitle {
                        Text(verbatim: subtitle)
                            .hexFont(.footnote)
                            .foregroundStyle(HexColor.text3)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                Spacer(minLength: 8)
                if let value {
                    Text(verbatim: value)
                        .hexFont(.row)
                        .foregroundStyle(HexColor.text2)
                        .lineLimit(1)
                        .frame(maxWidth: 160, alignment: .trailing)
                        .fixedSize(horizontal: true, vertical: false)
                }
                HexRowTrailingView(trailing: trailing)
            }
            .padding(.horizontal, HexSpacing.screen)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: HexSize.row, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(HexRowPressStyle())
    }
}

/// A project, folder or model row. Projects and folders are 62 pt with a folder icon, a bold
/// title with an optional badge, and a subtitle; models are 46 pt, the selected one bold with a ✓.
struct HexPickerRow: View {
    enum Variant {
        case project
        case model
    }

    var variant: Variant = .project
    var icon: HexIcon?
    let title: String
    var selected = false
    var badge: Text?
    var monoBadge = false
    var subtitle: String?
    /// Keeps the subtitle line while `subtitle` is nil, so the row doesn't change height when it
    /// arrives; it then fades in.
    var reservesSubtitle = false
    var trailing: HexRowTrailing = .none
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: variant == .project ? 12 : 10) {
                if let icon {
                    HexIconView(icon, size: HexIconSize.large)
                        .foregroundStyle(HexColor.text1)
                        .frame(width: 24)
                }
                VStack(alignment: .leading, spacing: variant == .project ? 2 : 0) {
                    HStack(spacing: 7) {
                        Text(verbatim: title)
                            .hexFont(titleStyle)
                            .foregroundStyle(HexColor.text1)
                            .lineLimit(1)
                        if let badge {
                            HexBadge(text: badge, mono: monoBadge)
                                .transition(.opacity)
                        }
                    }
                    if subtitle != nil || reservesSubtitle {
                        Text(verbatim: subtitle ?? " ")
                            .hexFont(variant == .project ? HexTextStyle.small.weight(.regular) : .caption)
                            .foregroundStyle(variant == .project ? HexColor.text2 : HexColor.text3)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .opacity(subtitle == nil ? 0 : 1)
                    }
                }
                Spacer(minLength: 8)
                HexRowTrailingView(trailing: trailing, strong: true)
            }
            .padding(.horizontal, variant == .project ? 12 : HexSpacing.screen)
            .padding(.vertical, variant == .project ? 8 : 0)
            .frame(maxWidth: .infinity, minHeight: variant == .project ? HexSize.pickerRow : HexSize.modelRow, alignment: .leading)
            .contentShape(Rectangle())
            .animation(HexMotion.ease(HexMotion.fade), value: subtitle)
        }
        .buttonStyle(HexRowPressStyle(radius: variant == .project ? HexRadius.l : 0))
        .padding(.horizontal, variant == .project ? HexSpacing.screen - 12 : 0)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var titleStyle: HexTextStyle {
        switch variant {
        case .project: .bodyStrong
        case .model: HexTextStyle.body.weight(selected ? .semibold : .regular)
        }
    }
}

private struct HexRowTrailingView: View {
    let trailing: HexRowTrailing
    /// Picker rows: a darker chevron and a full-colour check.
    var strong = false

    var body: some View {
        switch trailing {
        case .none:
            EmptyView()
        case .chevron:
            HexIconView(.chevR, size: HexIconSize.regular)
                .foregroundStyle(strong ? HexColor.text2 : HexColor.text3)
        case .check:
            HexIconView(.check, size: HexIconSize.regular)
                .foregroundStyle(HexColor.text1)
        case let .caption(text):
            Text(verbatim: text)
                .hexFont(.caption)
                .foregroundStyle(HexColor.text2)
                .lineLimit(1)
        }
    }
}

/// Upper-case heading over a group of rows.
struct HexSectionLabel: View {
    let title: Text

    init(_ title: LocalizedStringResource) {
        self.title = Text(title)
    }

    init(verbatim title: String) {
        self.title = Text(verbatim: title)
    }

    var body: some View {
        title
            .hexFont(.sectionLabel)
            .foregroundStyle(HexColor.text3)
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, HexSpacing.screen)
            .padding(.top, 18)
            .padding(.bottom, 6)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A small bordered label: `experiment`, `default`, `in use`; the mono form is the `git` tag.
struct HexBadge: View {
    let text: Text
    var mono = false

    init(_ text: LocalizedStringResource, mono: Bool = false) {
        self.text = Text(text)
        self.mono = mono
    }

    init(text: Text, mono: Bool = false) {
        self.text = text
        self.mono = mono
    }

    var body: some View {
        text
            .hexFont(mono ? .monoSmall : .badge)
            .foregroundStyle(HexColor.text2)
            .lineLimit(1)
            .padding(.horizontal, mono ? 5 : 7)
            .frame(height: mono ? 17 : 20)
            .overlay(
                RoundedRectangle(cornerRadius: HexRadius.xs, style: .continuous)
                    .strokeBorder(HexColor.line2, lineWidth: 1)
            )
    }
}
