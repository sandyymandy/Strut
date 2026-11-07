package com.sandymandy.strut.physics.flesh;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * How one soft body moves: a pouch, a pair of them, a bottom, a belly. Read from the {@code soft} section of a model's physics (see
 * {@link com.sandymandy.strut.physics.config.PhysicsConfig}); every value has a default from the entry's preset
 *
 * @param bones The root bone of each region: one, or two for a left and right pair that press on each other
 * @param softness How soft: 1 is the preset, up to 3 for very soft and down to 0.5 for firm. Multiplied by the entity's own (see
 *                 {@link com.sandymandy.strut.physics.tools.PhysicsControls#softness})
 * @param shapeSoftness What the pull back to the cage's own shape is divided by: the softness for a pouch, a fixed firmness for a bottom
 * @param limit The farthest a node may move from where the animation puts it, blocks
 * @param reach A share of {@code limit} the region may actually use (a bottom travels less than a pouch)
 * @param tip Which way, in the root bone's own space, is the free end, or null to take the end farthest out from the body
 * @param up Which way, in the root bone's own space, the region hangs from, or null for the model's up
 * @param anchorFrom How far toward the up side (0 to 1, by the cosine of the angle) the extra hold at the attachment starts
 * @param anchorHold The strength of that hold
 * @param anchorFull Where it is at full strength
 * @param depthFrom Where along the region, from the tip (0) to the base (1), the hold starts to count
 * @param depthTo Where it counts fully
 * @param lumpDamping The damping ratio of the whole region's swing
 * @param inertia How strongly the region feels the body's acceleration
 * @param lumpShare The share of that taken by the whole region's swing; the rest moves the nodes on their own
 * @param sideStiffness How much stiffer the swing is sideways than up and down
 * @param stiffen How soon the swing stiffens as it goes out, as a share of the limit
 * @param firmness A frequency scale on the whole region
 * @param nodeDamping The damping ratio of the free end's own wobble (an anchored node is critically damped)
 * @param shape How strongly the cage is pulled back to its own shape each step
 * @param link How strongly each link of the cage is kept to its length each step
 * @param gravity How far the free flesh sags each step, blocks
 * @param squeezeIn How easily a pair gives way toward each other, as a share of the swing's stiffness squared
 * @param squeezeOut How firmly it comes back
 * @param squeezeStiffness How hard two lumps pressed together push apart
 * @param rim How far the flesh pressed flat against the other of a pair bulges out at the edge of the contact
 * @param volume How much a stretch one way narrows it the other ways
 * @param bulge The share of the volume pressed in at one place that swells out elsewhere
 * @param rigid How much the swing turns the region about its base rather than sliding it
 * @param rise How high the flesh bounces on each footstep, blocks
 * @param sway How far it sways from side to side with the steps, blocks
 * @param turn How strongly turning swings it sideways
 * @param dressedStiffness How much firmer a dressed region is
 * @param dressedLimit The share of the limit a dressed region may use
 * @param dressedCouple How much the two of a dressed pair move together
 * @param collidesWith What the nodes are pushed out of: {@code body} (the model's own other parts, measured from where they rest), {@code other},
 *                     {@code players}, {@code mobs}
 * @param presses Bones of the model's own (names, {@code *} at either end allowed) that press into the flesh rather than being part of the body it rests on: arms,
 *                hands, a partner
 * @param weight 0 to 1: how much of the motion is shown
 * @param softCenter The middle of the soft zone, in the root bone's own space (blocks), or null for none
 * @param softRadius How far from it the flesh is soft: farther than this it is sewn on exactly as animated, blending to soft toward the middle. 0 for no zone
 * @param carry Other bones whose meshes ride on the region as one rigid piece, besides the meshes under its root
 * @param bonePivot Whether the region swings about its root bone's own pivot (the default) rather than about the base of the cage it is fitted with
 * @param pivot Where it swings about instead, in the root bone's space (blocks), or null; wins over {@code bonePivot}
 * @param meat 0 to 1 and more: how heavy and fleshy it feels: its swing and its nodes damped more (less bounce), and more sag
 */
public record FleshParams(List<String> bones, float softness, float shapeSoftness, float limit, float reach, @Nullable float[] tip, @Nullable float[] up,
						 float anchorFrom, float anchorHold, float anchorFull, float depthFrom, float depthTo, float lumpDamping, float inertia, float lumpShare,
						 float sideStiffness, float stiffen, float firmness, float nodeDamping, float shape, float link, float gravity, float squeezeIn,
						 float squeezeOut, float squeezeStiffness, float rim, float volume, float bulge, float rigid, float rise, float sway, float turn,
						 float dressedStiffness, float dressedLimit, float dressedCouple, Set<String> collidesWith, List<String> presses, float weight,
						 @Nullable float[] softCenter, float softRadius, List<String> carry, float meat, boolean bonePivot, @Nullable float[] pivot) {
	/** These parameters with {@code weight} scaled */
	public FleshParams scaled(float weightScale) {
		if (weightScale == 1f)
			return this;

		return new FleshParams(this.bones, this.softness, this.shapeSoftness, this.limit, this.reach, this.tip, this.up, this.anchorFrom, this.anchorHold,
				this.anchorFull, this.depthFrom, this.depthTo, this.lumpDamping, this.inertia, this.lumpShare, this.sideStiffness, this.stiffen, this.firmness,
				this.nodeDamping, this.shape, this.link, this.gravity, this.squeezeIn, this.squeezeOut, this.squeezeStiffness, this.rim, this.volume, this.bulge,
				this.rigid, this.rise, this.sway, this.turn, this.dressedStiffness, this.dressedLimit, this.dressedCouple, this.collidesWith, this.presses,
				Math.max(0f, Math.min(1f, this.weight * weightScale)), this.softCenter, this.softRadius, this.carry, this.meat, this.bonePivot, this.pivot);
	}
}
