import Testing
@testable import OpenCode_Mobile

struct ServerPathPolicyTests {
    @Test func parentPath_posix_returnsParentUntilRoot() {
        #expect(ServerPathPolicy.parentPath("/Users/ivan/Projects") == "/Users/ivan")
        #expect(ServerPathPolicy.parentPath("/Users/ivan") == "/Users")
        #expect(ServerPathPolicy.parentPath("/Users") == "/")
        #expect(ServerPathPolicy.parentPath("/Users/ivan/") == "/Users")
        #expect(ServerPathPolicy.parentPath("/") == nil)
        #expect(ServerPathPolicy.parentPath("") == nil)
    }

    @Test func parentPath_posix_keepsBackslashesInNames() {
        #expect(ServerPathPolicy.parentPath(#"/srv/odd\name"#) == "/srv")
    }

    @Test func parentPath_windowsDrive_stopsAtDriveRoot() {
        #expect(ServerPathPolicy.parentPath(#"C:\Users\me"#) == #"C:\Users"#)
        #expect(ServerPathPolicy.parentPath(#"C:\Users"#) == #"C:\"#)
        #expect(ServerPathPolicy.parentPath("C:/Users/me") == #"C:\Users"#)
        #expect(ServerPathPolicy.parentPath(#"C:\"#) == nil)
        #expect(ServerPathPolicy.parentPath("C:") == nil)
        #expect(ServerPathPolicy.parentPath("d:/") == nil)
    }

    @Test func parentPath_unc_stopsAtShareRoot() {
        #expect(ServerPathPolicy.parentPath(#"\\server\share\code"#) == #"\\server\share"#)
        #expect(ServerPathPolicy.parentPath("//server/share/code/app") == #"\\server\share\code"#)
        #expect(ServerPathPolicy.parentPath(#"\\server\share"#) == nil)
        #expect(ServerPathPolicy.parentPath(#"\\server"#) == nil)
    }

    @Test func parentPath_relative_isNil() {
        #expect(ServerPathPolicy.parentPath(#"code\app"#) == nil)
        #expect(ServerPathPolicy.parentPath("code/app") == nil)
    }

    @Test func name_takesLastComponentForEitherSeparator() {
        #expect(ServerPathPolicy.name("/Users/me/proj") == "proj")
        #expect(ServerPathPolicy.name("/Users/me/proj/") == "proj")
        #expect(ServerPathPolicy.name(#"C:\Users\me\proj"#) == "proj")
        #expect(ServerPathPolicy.name(#"\\server\share"#) == #"\\server\share"#)
        #expect(ServerPathPolicy.name(#"C:\"#) == #"C:\"#)
        #expect(ServerPathPolicy.name("/") == "/")
    }

    @Test func isAbsolute_recognizesDrivesUncAndPosix() {
        #expect(ServerPathPolicy.isAbsolute("/Users"))
        #expect(ServerPathPolicy.isAbsolute(#"C:\Users"#))
        #expect(ServerPathPolicy.isAbsolute("D:"))
        #expect(ServerPathPolicy.isAbsolute(#"\\server\share"#))
        #expect(ServerPathPolicy.isAbsolute("//server/share"))
        #expect(!ServerPathPolicy.isAbsolute("code"))
        #expect(!ServerPathPolicy.isAbsolute(""))
    }

    @Test func resolve_joinsInTheBaseSeparators() {
        #expect(ServerPathPolicy.resolve(base: "/repo", child: "src/") == "/repo/src")
        #expect(ServerPathPolicy.resolve(base: "/", child: "etc/") == "/etc")
        #expect(ServerPathPolicy.resolve(base: "/repo", child: ".") == "/repo")
        #expect(ServerPathPolicy.resolve(base: "/srv", child: #"odd\name"#) == #"/srv/odd\name"#)
        #expect(ServerPathPolicy.resolve(base: "/srv", child: "C:odd/") == "/srv/C:odd")
        #expect(ServerPathPolicy.resolve(base: #"C:\Users\me"#, child: #"code\"#) == #"C:\Users\me\code"#)
        #expect(ServerPathPolicy.resolve(base: #"C:\Users\me"#, child: #"..\sibling\"#) == #"C:\Users\sibling"#)
        #expect(ServerPathPolicy.resolve(base: #"C:\Users\me"#, child: #"D:\archive\"#) == #"D:\archive"#)
        #expect(ServerPathPolicy.resolve(base: #"C:\"#, child: #"code\"#) == #"C:\code"#)
    }

    @Test func resolveInput_absolutePaths() {
        #expect(ServerPathPolicy.resolveInput("/Volumes/Disk/", currentPath: "/Users/me", homePath: "/Users/me")
            == "/Volumes/Disk")
        #expect(ServerPathPolicy.resolveInput("D:", currentPath: #"C:\Users\me"#, homePath: #"C:\Users\me"#)
            == #"D:\"#)
        #expect(ServerPathPolicy.resolveInput("d:/", currentPath: #"C:\Users\me"#, homePath: #"C:\Users\me"#)
            == #"D:\"#)
        #expect(ServerPathPolicy.resolveInput("D:/code//app/", currentPath: #"C:\Users\me"#, homePath: "")
            == #"D:\code\app"#)
        #expect(ServerPathPolicy.resolveInput("//nas/share", currentPath: #"C:\Users\me"#, homePath: "")
            == #"\\nas\share"#)
        #expect(ServerPathPolicy.resolveInput(#"\\nas\share\x"#, currentPath: #"C:\"#, homePath: "")
            == #"\\nas\share\x"#)
        #expect(ServerPathPolicy.resolveInput(#"  "C:\Users\me"  "#, currentPath: "/", homePath: "")
            == #"C:\Users\me"#)
    }

    @Test func resolveInput_homeAndRelative() {
        #expect(ServerPathPolicy.resolveInput("~", currentPath: "/", homePath: "/Users/me") == "/Users/me")
        #expect(ServerPathPolicy.resolveInput("~/code", currentPath: "/", homePath: "/Users/me") == "/Users/me/code")
        #expect(ServerPathPolicy.resolveInput(#"~\code"#, currentPath: #"D:\"#, homePath: #"C:\Users\me"#)
            == #"C:\Users\me\code"#)
        #expect(ServerPathPolicy.resolveInput("~/code", currentPath: "/srv", homePath: "") == "/srv/code")
        #expect(ServerPathPolicy.resolveInput("code", currentPath: "/Users/me", homePath: "/Users/me")
            == "/Users/me/code")
        #expect(ServerPathPolicy.resolveInput("..", currentPath: "/Users/me", homePath: "/Users/me") == "/Users")
        #expect(ServerPathPolicy.resolveInput("../code", currentPath: #"C:\Users\me"#, homePath: "")
            == #"C:\Users\code"#)
    }

    @Test func resolveInput_rejectsBlankAndUnanchored() {
        #expect(ServerPathPolicy.resolveInput("   ", currentPath: "/Users/me", homePath: "/Users/me") == nil)
        #expect(ServerPathPolicy.resolveInput(#""""#, currentPath: "/Users/me", homePath: "/Users/me") == nil)
        #expect(ServerPathPolicy.resolveInput("code", currentPath: "", homePath: "") == nil)
        #expect(ServerPathPolicy.resolveInput("~", currentPath: "", homePath: "") == nil)
    }
}
