import Foundation

nonisolated enum MessageSnapshotAuthority {
    static func isAheadOrEqual(remote: [Message], local: [Message]) -> Bool {
        let remoteScore = score(remote)
        let localScore = score(local)
        if remoteScore.textCharacters < localScore.textCharacters {
            return false
        }
        if remoteScore.textCharacters > localScore.textCharacters {
            return true
        }
        if remoteScore.partCount > localScore.partCount {
            return true
        }
        if remoteScore.messageCount > localScore.messageCount {
            return true
        }
        if remoteScore.streamingCount < localScore.streamingCount {
            return true
        }
        if remoteScore.completedTools > localScore.completedTools {
            return true
        }
        return true
    }

    private struct Score {
        var textCharacters: Int
        var partCount: Int
        var messageCount: Int
        var streamingCount: Int
        var completedTools: Int
    }

    private static func score(_ messages: [Message]) -> Score {
        var textCharacters = 0
        var partCount = 0
        var streamingCount = 0
        var completedTools = 0
        for message in messages {
            partCount += message.parts.count
            if message.parts.isEmpty {
                textCharacters += message.text.count
                continue
            }
            for part in message.parts {
                switch part {
                case .text(let text):
                    textCharacters += text.text.count
                    if text.isStreaming {
                        streamingCount += 1
                    }
                case .tool(let tool):
                    if tool.status == .completed {
                        completedTools += 1
                    }
                case .file, .compaction, .reasoning, .notice:
                    break
                }
            }
        }
        return Score(
            textCharacters: textCharacters,
            partCount: partCount,
            messageCount: messages.count,
            streamingCount: streamingCount,
            completedTools: completedTools
        )
    }
}
