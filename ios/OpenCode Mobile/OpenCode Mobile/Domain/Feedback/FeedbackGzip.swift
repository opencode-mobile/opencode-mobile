import Compression
import Foundation

/// Gzip (RFC 1952) framing around the Compression framework's raw DEFLATE
/// output, for `logs.jsonl.gz`.
nonisolated enum FeedbackGzip {
    enum GzipError: Error {
        case compressionFailed
    }

    static func compress(_ data: Data) throws -> Data {
        var output = Data([0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03])
        output.append(try deflate(data))
        appendLittleEndian(crc32(data), to: &output)
        appendLittleEndian(UInt32(truncatingIfNeeded: data.count), to: &output)
        return output
    }

    /// Raw DEFLATE (RFC 1951) of `data`.
    static func deflate(_ data: Data) throws -> Data {
        if data.isEmpty {
            // Final stored block with no bytes.
            return Data([0x03, 0x00])
        }
        // Worst case DEFLATE growth is a few bytes per 64 KB block.
        let capacity = data.count + data.count / 1_000 + 64
        var destination = Data(count: capacity)
        let written = destination.withUnsafeMutableBytes { destinationBuffer in
            data.withUnsafeBytes { sourceBuffer in
                compression_encode_buffer(
                    destinationBuffer.bindMemory(to: UInt8.self).baseAddress!,
                    capacity,
                    sourceBuffer.bindMemory(to: UInt8.self).baseAddress!,
                    data.count,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
        }
        guard written > 0 else { throw GzipError.compressionFailed }
        return destination.prefix(written)
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }

    private static let table: [UInt32] = (0..<256).map { index in
        var value = UInt32(index)
        for _ in 0..<8 {
            value = (value & 1) == 1 ? (0xEDB8_8320 ^ (value >> 1)) : (value >> 1)
        }
        return value
    }

    private static func appendLittleEndian(_ value: UInt32, to data: inout Data) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
}
