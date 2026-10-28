import UIKit

/// Captures the key window for a report. Call before presenting the feedback
/// sheet: the design-system sheet draws over the window.
enum FeedbackScreenCapture {
    /// JPEG of the key window, longest side at most 1080 px.
    static func captureKeyWindow() -> Data? {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        else { return nil }
        let bounds = window.bounds
        let longest = max(bounds.width, bounds.height)
        guard longest > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = min(window.screen.scale, FeedbackScreenshotEncoder.maxDimension / longest)
        format.opaque = true
        let image = UIGraphicsImageRenderer(bounds: bounds, format: format).image { _ in
            window.drawHierarchy(in: bounds, afterScreenUpdates: false)
        }
        return image.jpegData(compressionQuality: 0.9)
    }
}
