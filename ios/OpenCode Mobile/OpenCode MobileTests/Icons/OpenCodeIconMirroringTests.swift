import SwiftUI
import Testing
import UIKit
@testable import OpenCode_Mobile

@MainActor
struct OpenCodeIconMirroringTests {
    @Test func directionalIcons_mirrorInRightToLeft() {
        let mirrored = Set(OpenCodeIcon.allCases.filter(\.mirrorsInRightToLeft))
        #expect(mirrored == [
            .arrowBack, .arrowForward, .chevronLeft, .chevronRight,
            .sidebar, .subdirectoryArrowRight, .undo, .redo,
        ])
    }

    @Test func arrowBack_pointsToTheLeadingEdge() throws {
        // arrow-left draws its head on the left, so its ink sits left of centre.
        let ltr = try inkCentroidX(.arrowBack, direction: .leftToRight)
        let rtl = try inkCentroidX(.arrowBack, direction: .rightToLeft)
        #expect(ltr < 0.5)
        #expect(rtl > 0.5)
    }

    @Test func nonDirectionalIcon_drawsTheSameInBothDirections() throws {
        let ltr = try inkCentroidX(.settings, direction: .leftToRight)
        let rtl = try inkCentroidX(.settings, direction: .rightToLeft)
        #expect(abs(ltr - rtl) < 0.01)
    }

    /// Horizontal centre of the drawn pixels, from 0 (left edge) to 1 (right edge).
    private func inkCentroidX(_ icon: OpenCodeIcon, direction: LayoutDirection) throws -> Double {
        let renderer = ImageRenderer(
            content: OpenCodeIconImage(icon: icon, size: 48)
                .foregroundStyle(.black)
                .environment(\.layoutDirection, direction)
        )
        renderer.scale = 1
        let image = try #require(renderer.cgImage)
        let width = image.width
        let height = image.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let context = try #require(CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        var weighted = 0.0
        var total = 0.0
        for y in 0..<height {
            for x in 0..<width {
                let alpha = Double(pixels[(y * width + x) * 4 + 3])
                weighted += alpha * (Double(x) + 0.5)
                total += alpha
            }
        }
        try #require(total > 0)
        return weighted / total / Double(width)
    }
}
