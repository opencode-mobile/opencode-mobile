#if DEBUG
import SwiftUI

/// Fixture for the bottom sheet UI tests (`OPENCODE_UI_TEST_BOTTOM_SHEET=1`): a
/// short and a long sheet, a dismissal counter, and rows that grow or shrink
/// the body while the sheet is up.
struct BottomSheetUITestHost: View {
    @State private var isPresented = false
    @State private var rows = 2
    @State private var dismissals = 0

    var body: some View {
        VStack(spacing: 16) {
            OpenCodeButton(variant: .fullWidthText(title: "Show short sheet")) {
                rows = 2
                isPresented = true
            }
            OpenCodeButton(variant: .fullWidthText(title: "Show long sheet")) {
                rows = 30
                isPresented = true
            }
            Text("Dismissals: \(dismissals)")
            Spacer()
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .openCodeBottomSheet(isPresented: presentedBinding, title: "Test sheet") {
            OpenCodeBottomSheetEntry(title: "Grow body") { rows = 4 }
            OpenCodeBottomSheetEntry(title: "Shrink body") { rows = 2 }
            OpenCodeBottomSheetEntry(title: "Selected row", selected: true) {}
            ForEach(0 ..< max(0, rows - 3), id: \.self) { index in
                OpenCodeBottomSheetEntry(title: "Row \(index)") {}
            }
        }
    }

    private var presentedBinding: Binding<Bool> {
        Binding(
            get: { isPresented },
            set: { presented in
                if !presented, isPresented {
                    dismissals += 1
                }
                isPresented = presented
            }
        )
    }
}
#endif
