package com.sandymandy.strut.physics.solver;

import java.util.List;
import java.util.Set;

/**
 * A cloth's tuning: a skirt, a cape, a loose shirt or a sheet, simulated from the vertices of a mesh on a bone
 *
 * @param bone The bone whose meshes are the cloth
 * @param mesh Which of the bone's meshes (by order), or -1 for every one that can be simulated
 * @param stiffness 0 to 1: how firmly each edge keeps its length (a cloth barely stretches at 1; lower is stretchy)
 * @param bend 0 to 1: how much a fold resists, the thickness of the cloth (0 is silk, 1 is card)
 * @param damping How quickly motion dies, per second
 * @param inertia How much of the entity's own motion the cloth lags behind, 0 (carried rigidly) to 1 (stays where it was in the world)
 * @param gravity Gravity's pull in blocks per second squared
 * @param radius The thickness of the cloth for collision, in blocks
 * @param pinHeight The share of the mesh's height, from the top, that is sewn to the bone and cannot move (a waistband); 0 pins nothing
 * @param anchor How firmly the free cloth is pulled toward where the animation puts it, per second (0 lets it hang entirely free)
 * @param weight 0 to 1: how much of the cloth's motion is shown. 0 is the mesh exactly as it is modelled
 * @param collidesWith The collider layers the cloth collides with: {@code body} for the model's own shapes, {@code world} for blocks
 * @param friction 0 to 1: the share of its motion a particle in contact with a shape loses each step
 * @param iterations How many times each step the constraints are relaxed: more is stiffer and costs more
 * @param maxParticles The most vertices (after welding) simulated; a denser mesh is left as modelled and a warning says so
 * @param volume 0 to 1: how much a closed mesh keeps its volume, so it bulges where it is pressed in (flesh and jelly); 0 for cloth
 * @param softCenter With {@code softRadius}: the middle of the soft part of the mesh, in the mesh space (blocks), or null for the origin
 * @param carry Other bones whose meshes ride on this one (a tip on a pouch): each of their vertices moves as the nearest particle of this mesh does
 * @param softRadius The mesh is soft within this distance of {@code softCenter} and blends to sewn on (firm) beyond it, or 0 for no zone
 */
public record ClothParams(String bone, int mesh, float stiffness, float bend, float damping, float inertia, float gravity, float radius, float pinHeight, float anchor,
						  float weight, Set<String> collidesWith, float friction, int iterations, int maxParticles, float volume, float[] softCenter, float softRadius,
						  List<String> carry) {
	public ClothParams {
		collidesWith = Set.copyOf(collidesWith);
		carry = List.copyOf(carry);
	}

	ClothParams scaled(float weightScale) {
		return new ClothParams(this.bone, this.mesh, this.stiffness, this.bend, this.damping, this.inertia, this.gravity, this.radius, this.pinHeight, this.anchor,
				Math.max(0f, Math.min(1f, this.weight * weightScale)), this.collidesWith, this.friction, this.iterations, this.maxParticles, this.volume, this.softCenter, this.softRadius, this.carry);
	}
}
