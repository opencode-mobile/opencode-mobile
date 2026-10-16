import Foundation

nonisolated enum PromptJSONBodyWriter {
    static func writeV1Prompt(_ body: SendMessageRequestDTO, attachments: [Attachment]) throws -> URL {
        try writeBody(to: newFile()) { writer in
            try writer.beginObject()
            try writer.key("parts")
            try writer.beginArray()
            var fileIndex = 0
            for part in body.parts {
                try writer.beginObject()
                try writer.key("type")
                try writer.writeString(part.type)
                if let text = part.text {
                    try writer.key("text")
                    try writer.writeString(text)
                }
                if let mime = part.mime {
                    try writer.key("mime")
                    try writer.writeString(mime)
                }
                if let filename = part.filename {
                    try writer.key("filename")
                    try writer.writeString(filename)
                }
                if part.type == "file", part.fileSource == nil {
                    try writer.key("url")
                    try writeFileURL(attachments: attachments, index: &fileIndex, fallback: part.url, mime: part.mime, to: &writer)
                } else if let url = part.url {
                    try writer.key("url")
                    try writer.writeString(url)
                }
                if let name = part.name {
                    try writer.key("name")
                    try writer.writeString(name)
                }
                if let fileSource = part.fileSource {
                    try writer.key("source")
                    try writer.beginObject()
                    try writer.key("type")
                    try writer.writeString("file")
                    try writer.key("path")
                    try writer.writeString(fileSource.path)
                    try writer.key("text")
                    try writer.beginObject()
                    try writer.key("value")
                    try writer.writeString(fileSource.value)
                    try writer.key("start")
                    try writer.writeInt(fileSource.start)
                    try writer.key("end")
                    try writer.writeInt(fileSource.end)
                    try writer.endObject()
                    try writer.endObject()
                } else if let source = part.source {
                    try writer.key("source")
                    try writer.beginObject()
                    try writer.key("value")
                    try writer.writeString(source.value)
                    try writer.key("start")
                    try writer.writeInt(source.start)
                    try writer.key("end")
                    try writer.writeInt(source.end)
                    try writer.endObject()
                }
                try writer.endObject()
            }
            try writer.endArray()
            if let model = body.model {
                try writer.key("model")
                try writer.beginObject()
                try writer.key("providerID")
                try writer.writeString(model.providerID)
                try writer.key("modelID")
                try writer.writeString(model.modelID)
                try writer.endObject()
            }
            if let variant = body.variant {
                try writer.key("variant")
                try writer.writeString(variant)
            }
            if let agent = body.agent {
                try writer.key("agent")
                try writer.writeString(agent)
            }
            try writer.endObject()
        }
    }

    static func writeV2Prompt(_ body: V2PromptRequestDTO, attachments: [Attachment]) throws -> URL {
        try writeBody(to: newFile()) { writer in
            try writer.beginObject()
            try writer.key("text")
            try writer.writeString(body.text)
            let mentionFiles = body.fileMentions ?? []
            let attachmentFiles = body.files ?? []
            if !attachmentFiles.isEmpty || !mentionFiles.isEmpty {
                try writer.key("files")
                try writer.beginArray()
                for (index, file) in attachmentFiles.enumerated() {
                    try writer.beginObject()
                    try writer.key("uri")
                    try writeFileURI(attachments: attachments, index: index, fallback: file.uri, mime: file.mime, to: &writer)
                    if let name = file.name {
                        try writer.key("name")
                        try writer.writeString(name)
                    }
                    try writer.endObject()
                }
                for file in mentionFiles {
                    try writer.beginObject()
                    try writer.key("uri")
                    try writer.writeString(file.uri)
                    try writer.key("name")
                    try writer.writeString(file.name)
                    try writer.key("mention")
                    try writer.beginObject()
                    if let start = file.mention.start {
                        try writer.key("start")
                        try writer.writeInt(start)
                    }
                    if let end = file.mention.end {
                        try writer.key("end")
                        try writer.writeInt(end)
                    }
                    if let text = file.mention.text {
                        try writer.key("text")
                        try writer.writeString(text)
                    }
                    try writer.endObject()
                    try writer.endObject()
                }
                try writer.endArray()
            }
            if let agents = body.agents, !agents.isEmpty {
                try writer.key("agents")
                try writer.beginArray()
                for agent in agents {
                    try writer.beginObject()
                    try writer.key("name")
                    try writer.writeString(agent.name)
                    if let mention = agent.mention {
                        try writer.key("mention")
                        try writer.beginObject()
                        if let start = mention.start {
                            try writer.key("start")
                            try writer.writeInt(start)
                        }
                        if let end = mention.end {
                            try writer.key("end")
                            try writer.writeInt(end)
                        }
                        if let text = mention.text {
                            try writer.key("text")
                            try writer.writeString(text)
                        }
                        try writer.endObject()
                    }
                    try writer.endObject()
                }
                try writer.endArray()
            }
            if let skills = body.skills, !skills.isEmpty {
                try writer.key("skills")
                try writer.beginArray()
                for skill in skills {
                    try writer.beginObject()
                    try writer.key("id")
                    try writer.writeString(skill.id)
                    try writer.endObject()
                }
                try writer.endArray()
            }
            try writer.key("delivery")
            try writer.writeString(body.delivery)
            try writer.endObject()
        }
    }

    static func writeV1Command(_ body: RunCommandRequestDTO, attachments: [Attachment]) throws -> URL {
        try writeBody(to: newFile()) { writer in
            try writer.beginObject()
            try writer.key("command")
            try writer.writeString(body.command)
            try writer.key("arguments")
            try writer.writeString(body.arguments)
            if let agent = body.agent {
                try writer.key("agent")
                try writer.writeString(agent)
            }
            if let model = body.model {
                try writer.key("model")
                try writer.writeString(model)
            }
            if let variant = body.variant {
                try writer.key("variant")
                try writer.writeString(variant)
            }
            try writer.key("parts")
            try writer.beginArray()
            for (index, part) in body.parts.enumerated() {
                try writer.beginObject()
                try writer.key("type")
                try writer.writeString(part.type)
                try writer.key("mime")
                try writer.writeString(part.mime)
                try writer.key("url")
                try writeFileURI(attachments: attachments, index: index, fallback: part.url, mime: part.mime, to: &writer)
                if let filename = part.filename {
                    try writer.key("filename")
                    try writer.writeString(filename)
                }
                try writer.endObject()
            }
            try writer.endArray()
            try writer.endObject()
        }
    }

    static func writeV2Command(_ body: V2RunCommandRequestDTO, attachments: [Attachment]) throws -> URL {
        try writeBody(to: newFile()) { writer in
            try writer.beginObject()
            try writer.key("name")
            try writer.writeString(body.name)
            try writer.key("text")
            try writer.writeString(body.text)
            if let files = body.files, !files.isEmpty {
                try writer.key("files")
                try writer.beginArray()
                for (index, file) in files.enumerated() {
                    try writer.beginObject()
                    try writer.key("uri")
                    try writeFileURI(attachments: attachments, index: index, fallback: file.uri, mime: file.mime, to: &writer)
                    if let name = file.name {
                        try writer.key("name")
                        try writer.writeString(name)
                    }
                    try writer.endObject()
                }
                try writer.endArray()
            }
            try writer.key("delivery")
            try writer.writeString(body.delivery)
            try writer.endObject()
        }
    }

    private static func writeBody(
        to fileURL: URL,
        _ body: (inout JSONStreamWriter) throws -> Void
    ) throws -> URL {
        var writer = try JSONStreamWriter(fileURL: fileURL)
        do {
            try body(&writer)
            try writer.close()
            return fileURL
        } catch {
            try? writer.close()
            try? FileManager.default.removeItem(at: fileURL)
            throw error
        }
    }

    private static func newFile() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("prompt-send-\(UUID().uuidString).json")
    }

    private static func writeFileURL(
        attachments: [Attachment],
        index: inout Int,
        fallback: String?,
        mime: String?,
        to writer: inout JSONStreamWriter
    ) throws {
        let attachment = attachments.indices.contains(index) ? attachments[index] : nil
        index += 1
        try writeFileURI(
            attachments: attachment.map { [$0] } ?? [],
            index: 0,
            fallback: fallback,
            mime: mime ?? attachment?.mime,
            to: &writer
        )
    }

    private static func writeFileURI(
        attachments: [Attachment],
        index: Int,
        fallback: String?,
        mime: String?,
        to writer: inout JSONStreamWriter
    ) throws {
        let attachment = attachments.indices.contains(index) ? attachments[index] : nil
        if let fileURL = attachment?.fileURL {
            try writer.writeDataURL(mime: mime ?? attachment?.mime ?? "application/octet-stream", fileURL: fileURL)
        } else if let fallback, !fallback.isEmpty {
            try writer.writeDataURLString(fallback)
        } else if let dataURL = attachment?.dataURL, !dataURL.isEmpty {
            try writer.writeDataURLString(dataURL)
        } else {
            try writer.writeString("")
        }
    }
}
