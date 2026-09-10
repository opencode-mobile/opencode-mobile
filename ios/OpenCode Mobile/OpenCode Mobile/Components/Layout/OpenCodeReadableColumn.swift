import SwiftUI

extension OpenCodeLayoutWidth {
    static func resolve(sizeClass: UserInterfaceSizeClass?) -> OpenCodeLayoutWidth {
        sizeClass == .regular ? .regular : .compact
    }
}

extension View {
    func openCodeReadableColumn() -> some View {
        frame(maxWidth: OpenCodeLayout.contentMaxWidth)
            .frame(maxWidth: .infinity)
    }
}
