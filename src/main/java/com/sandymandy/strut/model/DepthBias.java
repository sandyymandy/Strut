package com.sandymandy.strut.model;

/**
 * A polygon offset ({@code glPolygonOffset(factor, units)}): a negative value pulls the geometry towards the camera.
 *
 * @param factor Scales with the polygon's depth slope
 * @param units Scales with the smallest resolvable depth difference
 */
public record DepthBias(float factor, float units) {
	/** Nudges towards the camera, enough to win a tie against the same surface drawn before */
	public static final DepthBias DEFAULT = new DepthBias(-1f, -1f);

	/** No offset */
	public static final DepthBias NONE = new DepthBias(0f, 0f);
}
