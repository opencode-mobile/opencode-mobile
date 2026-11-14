import Foundation

/// App-owned directories for session transcripts and logs: excluded from iCloud and
/// iTunes backups and protected with `completeUnlessOpen`, so a file that is not already
/// open becomes unreadable once the device locks. New files inherit the directory's class.
public enum ProtectedStorage {
    public static let rootName = "OpenCodeMobile"
    public static let protection: FileProtectionType = .completeUnlessOpen
    /// For stores the app writes while backgrounded or locked, such as SQLite databases that
    /// reopen their `-wal`/`-shm` files. Readable from first unlock until the device restarts.
    public static let backgroundProtection: FileProtectionType = .completeUntilFirstUserAuthentication

    /// `Application Support/OpenCodeMobile/<name>/`, created and protected on first use.
    public static func directory(
        _ name: String,
        protection: FileProtectionType = Self.protection,
        fileManager: FileManager = .default
    ) throws -> URL {
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        var root = applicationSupport.appendingPathComponent(rootName, isDirectory: true)
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        try protect(directory: &root, protection: Self.protection, fileManager: fileManager)
        var directory = root.appendingPathComponent(name, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try protect(directory: &directory, protection: protection, fileManager: fileManager)
        return directory
    }

    /// Applies the protection class to an existing file (for files written before this
    /// policy existed, or created by frameworks that ignore the directory default).
    public static func protect(
        file url: URL,
        protection: FileProtectionType = Self.protection,
        fileManager: FileManager = .default
    ) {
        guard fileManager.fileExists(atPath: url.path) else { return }
        try? fileManager.setAttributes([.protectionKey: protection], ofItemAtPath: url.path)
    }

    private static func protect(
        directory url: inout URL,
        protection: FileProtectionType,
        fileManager: FileManager
    ) throws {
        try fileManager.setAttributes([.protectionKey: protection], ofItemAtPath: url.path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}
