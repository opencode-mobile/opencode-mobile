import UIKit

/// Host cell that suppresses UIKit's default animated self-resize (WWDC22) and
/// seeds preferred height from the last known measurement.
final class MessageListTimelineHostCell: UICollectionViewCell {
    var itemID: String?
    weak var heightCache: MessageListTimelineRowHeightCache?
    /// Heuristic seed used when the cache has no entry yet.
    var estimatedHeightSeed: CGFloat = MessageListTimelineDefaults.estimatedRowHeight

    override func prepareForReuse() {
        super.prepareForReuse()
        itemID = nil
        estimatedHeightSeed = MessageListTimelineDefaults.estimatedRowHeight
        contentConfiguration = nil
    }

    override func apply(_ layoutAttributes: UICollectionViewLayoutAttributes) {
        // WWDC22: self-resizing cells animate estimate→real by default. Diffable
        // animatingDifferences:false does not wrap this mutation — disable here.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            super.apply(layoutAttributes)
        }
        CATransaction.commit()
    }

    override func preferredLayoutAttributesFitting(
        _ layoutAttributes: UICollectionViewLayoutAttributes
    ) -> UICollectionViewLayoutAttributes {
        var fitted = layoutAttributes
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        UIView.performWithoutAnimation {
            let attributes = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
            let seed: CGFloat
            if estimatedHeightSeed < 0.5 {
                seed = 0
            } else {
                seed =
                    (itemID.flatMap { heightCache?.height(for: $0) })
                    ?? estimatedHeightSeed
            }
            if seed > 0.5, abs(attributes.size.height - seed) > 0.5 {
                attributes.frame.size.height = seed
            } else if seed < 0.5 {
                attributes.frame.size.height = 0
            }
            fitted = super.preferredLayoutAttributesFitting(attributes)
        }
        CATransaction.commit()

        if let itemID {
            heightCache?.setHeight(fitted.size.height, for: itemID)
        }
        return fitted
    }
}
