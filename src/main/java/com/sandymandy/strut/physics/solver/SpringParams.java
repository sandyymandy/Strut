package com.sandymandy.strut.physics.solver;

import org.joml.Vector3f;

import java.util.Set;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * One spring bone's tuning. Authors think in frequency and damping ratio, not stiffness: a bone with a
 * {@code frequency} of 2 Hz and a {@code dampingRatio} of 0.25 wobbles twice a second and settles in about a second, whatever its mass or the frame rate
 *
 * @param frequency Natural frequency in hertz: how fast it wobbles
 * @param dampingRatio 0 rings forever, 1 returns without overshoot; jiggle sits around 0.15 to 0.35
 * @param inertia How much of the entity's own acceleration reaches the bone, 0 to 1 (1 is fully physical)
 * @param gravity Gravity's pull on the bone in blocks per second squared, 0 for jiggle, about 9.8 for hair
 * @param rotate Whether the bone's rotation is driven
 * @param translate Whether the bone's position is driven
 * @param tip The direction, in the bone's own space, from its pivot to the mass that lags (default straight down)
 * @param length The distance from the pivot to that mass, in blocks. A shorter lever turns the same push into a bigger angle
 * @param rotationLimit Per axis {@code {min, max}} in radians, or null for no limit; applies to the rotation the physics adds
 * @param translationLimit The most the bone may move from where it is animated, in model units (sixteenths of a block), or 0 for no limit
 * @param translationScale Multiplier on the translation the physics adds
 * @param weight 0 to 1: how much of the physics reaches the pose. 0 is exactly the animation
 * @param collidesWith The layers the bone's mass is pushed out of: {@code other}, {@code players}, {@code mobs}. Empty means it collides with nothing
 * @param radius The size of the mass for collision, in blocks
 * @param squish 0 to 1: how far the bone flattens under a press (a soft bone on a cube model). 0.3 shortens it by up to 30 percent along the push and bulges it across
 */
public record SpringParams(float frequency, float dampingRatio, float inertia, float gravity, boolean rotate, boolean translate, Vector3f tip, float length,
						   float[][] rotationLimit, float translationLimit, float translationScale, float weight, Set<String> collidesWith, float radius, float squish,
						   boolean localRotation) {
	/** The same without {@code localRotation} (false): the spring's rotation is added to the bone's rotation angles */
	public SpringParams(float frequency, float dampingRatio, float inertia, float gravity, boolean rotate, boolean translate, Vector3f tip, float length,
						float[][] rotationLimit, float translationLimit, float translationScale, float weight, Set<String> collidesWith, float radius, float squish) {
		this(frequency, dampingRatio, inertia, gravity, rotate, translate, tip, length, rotationLimit, translationLimit, translationScale, weight, collidesWith, radius, squish, false);
	}

	public SpringParams {
		tip = new Vector3f(tip);
		collidesWith = Set.copyOf(collidesWith);
	}

	/** A copy with the runtime multipliers of {@link PhysicsControls} applied: {@code firmness} scales the frequency, {@code weight} the output */
	public SpringParams scaled(float frequencyScale, float weightScale, float inertiaScale) {
		return new SpringParams(this.frequency * frequencyScale, this.dampingRatio, Math.min(1f, this.inertia * inertiaScale), this.gravity, this.rotate,
				this.translate, this.tip, this.length, this.rotationLimit, this.translationLimit, this.translationScale, Math.max(0f, Math.min(1f, this.weight * weightScale)),
				this.collidesWith, this.radius, this.squish, this.localRotation);
	}
}
