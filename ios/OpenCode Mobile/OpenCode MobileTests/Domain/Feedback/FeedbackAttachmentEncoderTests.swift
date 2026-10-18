import Compression
import Foundation
import Testing
import UIKit
@testable import OpenCode_Mobile

struct FeedbackAttachmentEncoderTests {
    @Test func gzip_hasHeaderTrailerAndInflatesBack() throws {
        let input = Data(String(repeating: #"{"type":"entry","message":"hello"}\#n"#, count: 200).utf8)

        let gzipped = try FeedbackGzip.compress(input)

        #expect(Array(gzipped.prefix(3)) == [0x1F, 0x8B, 0x08])
        let trailer = Array(gzipped.suffix(8))
        let crc = UInt32(trailer[0]) | UInt32(trailer[1]) << 8 | UInt32(trailer[2]) << 16 | UInt32(trailer[3]) << 24
        let size = UInt32(trailer[4]) | UInt32(trailer[5]) << 8 | UInt32(trailer[6]) << 16 | UInt32(trailer[7]) << 24
        #expect(crc == FeedbackGzip.crc32(input))
        #expect(Int(size) == input.count)
        #expect(gzipped.count < input.count)
        #expect(Self.inflate(gzipped.dropFirst(10).dropLast(8), capacity: input.count) == input)
    }

    @Test func crc32_matchesKnownValue() {
        #expect(FeedbackGzip.crc32(Data("123456789".utf8)) == 0xCBF4_3926)
    }

    @Test func logEncoder_gzipsTheRedactedExport() throws {
        let buffer = DiagnosticLogBuffer(persistDelayMilliseconds: nil)
        buffer.append(area: .connection, level: .info, message: "Authorization: Basic b3BlbmNvZGU6c2VjcmV0")

        let data = try #require(FeedbackLogEncoder.encode(buffer: buffer, window: .fifteenMinutes))
        let jsonl = try #require(Self.inflate(data.dropFirst(10).dropLast(8), capacity: 64_000))
        let text = String(decoding: jsonl, as: UTF8.self)

        #expect(text.contains(#""windowMinutes":15"#))
        #expect(text.contains("[REDACTED]"))
        #expect(!text.contains("b3BlbmNvZGU6c2VjcmV0"))
    }

    @Test func logEncoder_returnsNilWhenEvenTheShortestWindowIsTooBig() {
        let buffer = DiagnosticLogBuffer(persistDelayMilliseconds: nil)
        buffer.append(area: .sse, level: .debug, message: "event")

        #expect(FeedbackLogEncoder.encode(buffer: buffer, window: .sixtyMinutes, maxBytes: 10) == nil)
    }

    @Test func screenshot_downscalesTo1080AndFitsInOneMegabyte() throws {
        let screenshot = FeedbackScreenshot(imageData: Self.noiseImage(width: 1_290, height: 2_796).pngData()!)

        let data = try #require(FeedbackScreenshotEncoder.encode(screenshot))
        let image = try #require(UIImage(data: data))

        #expect(data.count <= 1_000_000)
        #expect(max(image.size.width * image.scale, image.size.height * image.scale) <= 1_080)
        #expect(Array(data.prefix(2)) == [0xFF, 0xD8])
    }

    @Test func screenshot_lowersQualityUntilItFits() throws {
        let screenshot = FeedbackScreenshot(imageData: Self.noiseImage(width: 1_080, height: 1_080).pngData()!)
        let unconstrained = try #require(FeedbackScreenshotEncoder.encode(screenshot, maxBytes: .max))

        let constrained = try #require(FeedbackScreenshotEncoder.encode(screenshot, maxBytes: unconstrained.count / 2))

        #expect(constrained.count <= unconstrained.count / 2)
    }

    @Test func screenshot_burnsHiddenAreasIntoThePixels() throws {
        let white = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 400), format: Self.pixelFormat).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 200, height: 400))
        }
        let screenshot = FeedbackScreenshot(
            imageData: white.pngData()!,
            hiddenRects: [CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.25)]
        )

        let rendered = FeedbackScreenshotEncoder.render(white, hiddenRects: screenshot.hiddenRects)

        #expect(Self.brightness(of: rendered, at: CGPoint(x: 100, y: 150)) < 0.05)
        #expect(Self.brightness(of: rendered, at: CGPoint(x: 10, y: 10)) > 0.95)
    }

    @Test func hiddenRect_isCenteredAndKeptInsideTheImage() {
        let size = CGSize(width: 390, height: 844)

        let centered = FeedbackScreenshotEncoder.hiddenRect(centeredAt: CGPoint(x: 0.5, y: 0.5), imageSize: size)
        let corner = FeedbackScreenshotEncoder.hiddenRect(centeredAt: CGPoint(x: 0, y: 1), imageSize: size)

        #expect(abs(centered.midX - 0.5) < 0.0001)
        #expect(abs(centered.midY - 0.5) < 0.0001)
        #expect(abs(centered.width - 140.0 / 390.0) < 0.0001)
        #expect(abs(centered.height * 844 - 44) < 0.01)
        #expect(corner.minX == 0)
        #expect(abs(corner.maxY - 1) < 0.0001)
    }

    // MARK: - Helpers

    static let pixelFormat: UIGraphicsImageRendererFormat = {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return format
    }()

    static func noiseImage(width: Int, height: Int) -> UIImage {
        var generator = SystemRandomNumberGenerator()
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: pixelFormat).image { context in
            for y in stride(from: 0, to: height, by: 6) {
                for x in stride(from: 0, to: width, by: 6) {
                    UIColor(
                        red: .random(in: 0...1, using: &generator),
                        green: .random(in: 0...1, using: &generator),
                        blue: .random(in: 0...1, using: &generator),
                        alpha: 1
                    ).setFill()
                    context.fill(CGRect(x: x, y: y, width: 6, height: 6))
                }
            }
        }
    }

    static func brightness(of image: UIImage, at point: CGPoint) -> CGFloat {
        guard let cgImage = image.cgImage,
              let cropped = cgImage.cropping(to: CGRect(x: point.x, y: point.y, width: 1, height: 1))
        else { return -1 }
        var pixel = [UInt8](repeating: 0, count: 4)
        let context = CGContext(
            data: &pixel,
            width: 1,
            height: 1,
            bitsPerComponent: 8,
            bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        context?.draw(cropped, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return (CGFloat(pixel[0]) + CGFloat(pixel[1]) + CGFloat(pixel[2])) / (3 * 255)
    }

    static func inflate(_ deflated: Data, capacity: Int) -> Data? {
        var output = Data(count: capacity + 1)
        let source = Data(deflated)
        let written = output.withUnsafeMutableBytes { destination in
            source.withUnsafeBytes { input in
                compression_decode_buffer(
                    destination.bindMemory(to: UInt8.self).baseAddress!,
                    capacity + 1,
                    input.bindMemory(to: UInt8.self).baseAddress!,
                    source.count,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
        }
        return written > 0 ? output.prefix(written) : nil
    }
}
