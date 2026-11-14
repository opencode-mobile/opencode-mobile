import SwiftUI

/// 40 pt search field: `tint(6)` fill (`tint(9)` while editing) and a search icon.
struct HexSearchField: View {
    let placeholder: String
    @Binding var text: String

    @FocusState private var focused: Bool

    init(_ placeholder: LocalizedStringResource, text: Binding<String>) {
        self.placeholder = String(localized: placeholder)
        _text = text
    }

    var body: some View {
        HStack(spacing: 8) {
            HexIconView(.search, size: HexIconSize.regular)
                .foregroundStyle(HexColor.text3)
            TextField(
                "",
                text: $text,
                prompt: Text(verbatim: placeholder).foregroundStyle(HexColor.text3)
            )
            .hexFont(.body)
            .foregroundStyle(HexColor.text1)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.search)
            .focused($focused)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 40)
        .background(HexColor.tint(focused ? 9 : 6), in: RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous))
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: focused)
    }
}

/// 44 pt field on `panel` with a `line2` border, radius 12. Mono for paths.
struct HexTextField: View {
    let placeholder: String
    @Binding var text: String
    var monospaced = false
    var submitLabel: SubmitLabel = .done
    var focus: FocusState<Bool>.Binding?
    var onSubmit: () -> Void = {}

    @FocusState private var ownFocus: Bool

    init(
        _ placeholder: LocalizedStringResource,
        text: Binding<String>,
        monospaced: Bool = false,
        submitLabel: SubmitLabel = .done,
        focus: FocusState<Bool>.Binding? = nil,
        onSubmit: @escaping () -> Void = {}
    ) {
        self.placeholder = String(localized: placeholder)
        _text = text
        self.monospaced = monospaced
        self.submitLabel = submitLabel
        self.focus = focus
        self.onSubmit = onSubmit
    }

    var body: some View {
        TextField(
            "",
            text: $text,
            prompt: Text(verbatim: placeholder).foregroundStyle(HexColor.text3)
        )
        .hexFont(monospaced ? HexTextStyle.row.monospaced() : .row)
        .foregroundStyle(HexColor.text1)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .submitLabel(submitLabel)
        .onSubmit(onSubmit)
        .focused(focus ?? $ownFocus)
        .padding(.horizontal, 14)
        .frame(height: HexSize.touch)
        .background(HexColor.panel, in: RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: HexRadius.l, style: .continuous)
                .strokeBorder(HexColor.line2, lineWidth: 1)
        )
    }
}

/// A segmented choice in a `tint(6)` track; the selected segment sits on `panel3`.
struct HexSegmented<Value: Hashable>: View {
    enum Size {
        /// 36 pt, `callout`: a choice on its own row.
        case regular
        /// 30 pt, `caption` medium, hugging its labels: a switch beside other buttons (the
        /// prototype's `.mode`).
        case small
    }

    let options: [Value]
    @Binding var selection: Value
    let label: (Value) -> String
    var size: Size = .regular
    /// The selected label's colour; `text1` unless an option needs to stand out (Now in `amber`).
    var selectedColor: (Value) -> Color = { _ in HexColor.text1 }

    init(
        _ options: [Value],
        selection: Binding<Value>,
        size: Size = .regular,
        selectedColor: @escaping (Value) -> Color = { _ in HexColor.text1 },
        label: @escaping (Value) -> String
    ) {
        self.options = options
        _selection = selection
        self.size = size
        self.selectedColor = selectedColor
        self.label = label
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { option in
                let selected = option == selection
                Button {
                    selection = option
                } label: {
                    Text(verbatim: label(option))
                        .hexFont(size == .small ? HexTextStyle.caption.weight(.medium) : .callout)
                        .foregroundStyle(selected ? selectedColor(option) : (size == .small ? HexColor.text3 : HexColor.text2))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.horizontal, size == .small ? 10 : 0)
                        .frame(maxWidth: size == .small ? nil : .infinity, maxHeight: .infinity)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous)
                                    .fill(HexColor.panel3)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: HexRadius.s, style: .continuous)
                                            .strokeBorder(HexColor.line, lineWidth: 1)
                                    )
                                    .shadow(color: HexColor.shadow.opacity(0.45), radius: 1, y: 1)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(size == .small ? 2 : 3)
        .frame(height: size == .small ? 30 : 36)
        .background(HexColor.tint(6), in: RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous))
        .hexAnimation(HexMotion.ease(HexMotion.fade), value: selection)
    }
}

/// The empty segment track with a note in it, when there is nothing to choose.
struct HexSegmentedPlaceholder: View {
    let note: Text

    init(_ note: LocalizedStringResource) {
        self.note = Text(note)
    }

    var body: some View {
        note
            .hexFont(HexTextStyle.small.weight(.regular))
            .foregroundStyle(HexColor.text3)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
            .background(HexColor.tint(6), in: RoundedRectangle(cornerRadius: HexRadius.m, style: .continuous))
    }
}
