package com.sandymandy.strut.physics.solver;

import java.util.List;
import java.util.Set;

/**
 * A chain's tuning: hair, a tail, an ear or a strap made of bones that each hang from the one before
 *
 * @param bones The bones from the root down, each the child of the one before it
 * @param frequency How strongly the chain is pulled back to its animated pose, in hertz (higher is stiffer)
 * @param dampingRatio How quickly it stops swinging, 0 to 1
 * @param inertia How much of the entity's own motion the chain lags behind, 0 (it is carried along rigidly) to 1 (it stays where it was in the world)
 * @param gravity Gravity's pull in blocks per second squared
 * @param radius The size of each particle for collision, in blocks
 * @param maxAngle The most a segment may bend away from its animated direction, in radians, or 0 for no limit
 * @param tipLength How far the last bone reaches past its pivot, in blocks
 * @param weight 0 to 1: how much of the chain's motion reaches the pose. 0 is exactly the animation
 * @param collidesWith The collider layers this chain collides with: {@code body} for the model's own shapes and {@code world} for blocks
 * @param friction 0 to 1: the share of its motion a particle in contact with a shape loses each step
 */
public record ChainParams(List<String> bones, float frequency, float dampingRatio, float inertia, float gravity, float radius, float maxAngle, float tipLength,
						  float weight, Set<String> collidesWith, float friction) {
	public ChainParams {
		bones = List.copyOf(bones);
		collidesWith = Set.copyOf(collidesWith);
	}

	ChainParams scaled(float frequencyScale, float weightScale, float inertiaScale) {
		return new ChainParams(this.bones, this.frequency * frequencyScale, this.dampingRatio, Math.min(1f, this.inertia * inertiaScale), this.gravity, this.radius,
				this.maxAngle, this.tipLength, Math.max(0f, Math.min(1f, this.weight * weightScale)), this.collidesWith, this.friction);
	}
}
