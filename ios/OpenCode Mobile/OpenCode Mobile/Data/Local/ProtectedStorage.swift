import Foundation

/// App-owned directories for session transcripts and logs: excluded from iCloud and
/// iTunes backups and protected with `completeUnlessOpen`, so a file that is not already
/// open becomes unreadable once the device locks. New files inherit the directory's class.
enum ProtectedStorage {
    static let rootName = "OpenCodeMobile"
    static let protection: FileProtectionType = .completeUnlessOpen

    /// `Application Support/OpenCodeMobile/<name>/`, created and protected on first use.
    static func directory(_ name: String, fileManager: FileManager = .default) throws -> URL {
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        var root = applicationSupport.appendingPathComponent(rootName, isDirectory: true)
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        try protect(directory: &root, fileManager: fileManager)
        var directory = root.appendingPathComponent(name, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try protect(directory: &directory, fileManager: fileManager)
        return directory
    }

    /// Applies the protection class to an existing file (for files written before this
    /// policy existed, or created by frameworks that ignore the directory default).
    static func protect(file url: URL, fileManager: FileManager = .default) {
        guard fileManager.fileExists(atPath: url.path) else { return }
        try? fileManager.setAttributes([.protectionKey: protection], ofItemAtPath: url.path)
    }

    private static func protect(directory url: inout URL, fileManager: FileManager) throws {
        try fileManager.setAttributes([.protectionKey: protection], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}
