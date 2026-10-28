import SwiftUI

/// File or folder icon picked from a path's name or extension, ported from
/// opencode's `FileIcon` (`packages/ui/src/components/file-icon.tsx`).
struct OpenCodeFileIcon: View {
    enum Kind: Hashable {
        case file
        case directory
    }

    let path: String
    let kind: Kind
    let expanded: Bool
    /// Draws the icon in the current foreground style instead of its colours.
    let mono: Bool
    private let explicitSize: CGFloat?

    init(path: String, kind: Kind = .file, expanded: Bool = false, mono: Bool = false, size: CGFloat? = nil) {
        self.path = path
        self.kind = kind
        self.expanded = expanded
        self.mono = mono
        self.explicitSize = size
    }

    var body: some View {
        Image(Self.assetName(path: path, kind: kind, expanded: expanded))
            .renderingMode(mono ? .template : .original)
            .resizable()
            .scaledToFit()
            .frame(width: explicitSize, height: explicitSize)
            .accessibilityHidden(true)
    }

    nonisolated static func assetName(path: String, kind: Kind, expanded: Bool) -> String {
        let icon = iconName(path: path, kind: kind, expanded: expanded)
        return OpenCodeFileTypeIconTables.assets[icon]
            ?? OpenCodeFileTypeIconTables.assets[OpenCodeFileTypeIconTables.defaultFile]!
    }

    /// Upstream `chooseIconName`: file names, then dotted suffixes longest
    /// first, then the default. Folders try `name`, `.name`, `_name`, `__name__`.
    nonisolated static func iconName(path: String, kind: Kind, expanded: Bool) -> String {
        let base = basename(of: path).lowercased()
        switch kind {
        case .directory:
            for candidate in [base, ".\(base)", "_\(base)", "__\(base)__"] {
                if let icon = OpenCodeFileTypeIconTables.folderNames[candidate] {
                    return expanded ? openVariant(of: icon) : icon
                }
            }
            return expanded ? OpenCodeFileTypeIconTables.defaultFolderOpen : OpenCodeFileTypeIconTables.defaultFolder
        case .file:
            if let icon = OpenCodeFileTypeIconTables.fileNames[base] {
                return icon
            }
            for suffix in dottedSuffixesLongestFirst(base) {
                if let icon = OpenCodeFileTypeIconTables.fileExtensions[suffix] {
                    return icon
                }
            }
            return OpenCodeFileTypeIconTables.defaultFile
        }
    }

    private nonisolated static func basename(of path: String) -> String {
        path.replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/", omittingEmptySubsequences: true)
            .last
            .map(String.init) ?? ""
    }

    /// The whole name plus every suffix after a dot, longest first.
    private nonisolated static func dottedSuffixesLongestFirst(_ name: String) -> [String] {
        var suffixes = [name]
        var index = name.startIndex
        while let dot = name[index...].firstIndex(of: ".") {
            let next = name.index(after: dot)
            if next < name.endIndex {
                let suffix = String(name[next...])
                if !suffixes.contains(suffix) { suffixes.append(suffix) }
            }
            index = next
        }
        // Suffixes of one string are unique by length, so this order is total.
        return suffixes.sorted { $0.count > $1.count }
    }

    private nonisolated static func openVariant(of icon: String) -> String {
        guard icon.hasPrefix("Folder") else { return icon }
        if icon.hasSuffix("_light") {
            return icon.replacingOccurrences(of: "_light", with: "Open_light")
        }
        return icon.hasSuffix("Open") ? icon : icon + "Open"
    }
}

#Preview {
    VStack(alignment: .leading, spacing: 12) {
        ForEach(["Package.swift", "build.gradle.kts", "README.md", "src/app.test.ts", "unknown.zzz"], id: \.self) { path in
            HStack(spacing: 8) {
                OpenCodeFileIcon(path: path, size: 18)
                Text(path).font(OpenCodeTypography.body)
            }
        }
        HStack(spacing: 8) {
            OpenCodeFileIcon(path: "src", kind: .directory, size: 18)
            OpenCodeFileIcon(path: "src", kind: .directory, expanded: true, size: 18)
            OpenCodeFileIcon(path: "kotlin.kt", mono: true, size: 18)
                .foregroundStyle(OpenCodeColors.iconBase)
        }
    }
    .padding()
    .openCodeTheme()
}
