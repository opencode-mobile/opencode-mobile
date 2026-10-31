import UIKit

/// Drawn sample pictures for image galleries in the Component Gallery and snapshot evidence:
/// flat mock screens with no real content, the same on every run.
enum SnapshotEvidenceImageMocks {
    struct Sample {
        let filename: String
        let accent: UIColor
        let size: CGSize
    }

    static let samples: [Sample] = [
        Sample(filename: "login-iphone-se.png", accent: .systemTeal, size: CGSize(width: 300, height: 600)),
        Sample(filename: "login-iphone-16.png", accent: .systemGreen, size: CGSize(width: 300, height: 650)),
        Sample(filename: "settings-dark.png", accent: .systemIndigo, size: CGSize(width: 300, height: 650)),
        Sample(filename: "diff-chart.png", accent: .systemOrange, size: CGSize(width: 480, height: 320)),
        Sample(filename: "flow-1.png", accent: .systemPurple, size: CGSize(width: 480, height: 320)),
        Sample(filename: "flow-2.png", accent: .systemPink, size: CGSize(width: 480, height: 320)),
        Sample(filename: "before.png", accent: .systemBlue, size: CGSize(width: 400, height: 400)),
        Sample(filename: "after.png", accent: .systemMint, size: CGSize(width: 400, height: 400)),
    ]

    /// Sample `index` (wrapping), drawn once per process.
    static func image(_ index: Int) -> UIImage {
        let sample = samples[index % samples.count]
        if let cached = cache.object(forKey: NSNumber(value: index % samples.count)) { return cached }
        let image = draw(sample)
        cache.setObject(image, forKey: NSNumber(value: index % samples.count))
        return image
    }

    /// `count` gallery items with pictures in memory.
    static func items(_ count: Int, prefix: String = "sample") -> [OpenCodeImageGalleryItem] {
        (0..<count).map { index in
            OpenCodeImageGalleryItem(
                id: "\(prefix)-\(index)",
                filename: samples[index % samples.count].filename,
                preview: image(index)
            )
        }
    }

    private static let cache = NSCache<NSNumber, UIImage>()

    private static func draw(_ sample: Sample) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: sample.size, format: format).image { context in
            let bounds = CGRect(origin: .zero, size: sample.size)
            UIColor(white: 0.12, alpha: 1).setFill()
            context.fill(bounds)
            let inset = sample.size.width * 0.1
            let width = sample.size.width - inset * 2
            sample.accent.setFill()
            UIBezierPath(
                roundedRect: CGRect(x: inset, y: inset, width: width, height: sample.size.height * 0.12),
                cornerRadius: 12
            ).fill()
            sample.accent.withAlphaComponent(0.45).setFill()
            for (row, fraction) in [0.9, 0.7, 0.8, 0.5].enumerated() {
                let y = inset + sample.size.height * (0.24 + 0.12 * CGFloat(row))
                UIBezierPath(
                    roundedRect: CGRect(x: inset, y: y, width: width * fraction, height: sample.size.height * 0.06),
                    cornerRadius: 8
                ).fill()
            }
            sample.accent.setFill()
            UIBezierPath(
                roundedRect: CGRect(
                    x: inset,
                    y: sample.size.height * 0.82,
                    width: width,
                    height: sample.size.height * 0.08
                ),
                cornerRadius: 12
            ).fill()
        }
    }
}
