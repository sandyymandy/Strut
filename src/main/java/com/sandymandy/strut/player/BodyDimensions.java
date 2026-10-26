package com.sandymandy.strut.player;

/**
 * The size of a player with a body when they stand: the box that is their hitbox and the height of their eyes (what they aim from)
 * <p>
 * Used on both sides, from the body they were given, so the client and the server agree about where they fit. The other poses (sneaking, swimming, sleeping) are the
 * game's own, scaled in the same proportion
 *
 * @param width the width and depth of the hitbox, in blocks (a player is 0.6)
 * @param height the height, in blocks (a player is 1.8)
 * @param eyeHeight the height of the eyes above the feet, in blocks, and below {@code height} (a player's is 1.62)
 */
public record BodyDimensions(float width, float height, float eyeHeight) {
	public BodyDimensions {
		if (!(width > 0f) || !(height > 0f) || !(eyeHeight > 0f) || eyeHeight > height)
			throw new IllegalArgumentException("a body must have a width and height above 0, and eyes inside it: " + width + " x " + height + ", eyes at " + eyeHeight);
	}

	/** How much wider than a player this is */
	public float widthRatio() {
		return this.width / 0.6f;
	}

	/** How much taller than a player this is */
	public float heightRatio() {
		return this.height / 1.8f;
	}
}
