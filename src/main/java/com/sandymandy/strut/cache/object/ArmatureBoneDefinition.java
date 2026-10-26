package com.sandymandy.strut.cache.object;

import com.sandymandy.strut.animation.state.BoneSnapshot;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * The immutable, shared definition of one bone of a {@link StrutArmature}: one that deforms mesh
 * vertices by weight rather than moving geometry rigidly
 * <p>
 * Baked once and shared by every animatable using the model, exactly like {@link GroupBoneDefinition}.
 * This class carries no pose - position, rotation and scale moved to
 * {@link com.sandymandy.strut.animation.state.ArmaturePose}, one per instance, addressed through
 * {@link ArmatureBoneHandle}. This is the same {@link GroupBoneDefinition}/{@link GroupBoneHandle} split
 * already applied to group bones, closing the same aliasing hole for armature bones.
 *
 * @see StrutArmature
 * @see ArmatureBoneHandle
 */
public class ArmatureBoneDefinition {
	private final ArmatureBoneDefinition parent;
	private final String name;
	private final Matrix4f bindLocal;
	private final Matrix4f inverseBind;
	private final float length;
	private final int index;

	@Nullable
	private BoneSnapshot initialSnapshot;

	/**
	 * @param bindLocal This bone's rest transform relative to its parent
	 * @param inverseBind The inverse of this bone's rest transform in model space
	 * @param length The bone's length along its own axis, carried for tooling and debug rendering
	 * @param index This bone's position within its armature, which is what a vertex influence names
	 */
	public ArmatureBoneDefinition(@Nullable ArmatureBoneDefinition parent, String name, Matrix4f bindLocal,
						   Matrix4f inverseBind, float length, int index) {
		this.parent = parent;
		this.name = name;
		this.bindLocal = bindLocal;
		this.inverseBind = inverseBind;
		this.length = length;
		this.index = index;
	}

	public String getName() {
		return this.name;
	}

	public ArmatureBoneDefinition getParent() {
		return this.parent;
	}

	/**
	 * This bone's rest transform relative to its parent
	 * <p>
	 * Composed with the bone's animated delta to produce its posed transform, so that a bone with
	 * no animation on it resolves to its rest pose rather than to the identity
	 */
	public Matrix4f getBindLocal() {
		return this.bindLocal;
	}

	/**
	 * The inverse of this bone's rest transform in model space
	 * <p>
	 * Takes a vertex from model space into this bone's rest frame, which is the first half of
	 * skinning it. Precomputed at bake because it never changes and inverting a matrix per vertex
	 * per frame would dominate everything else
	 */
	public Matrix4f getInverseBind() {
		return this.inverseBind;
	}

	public float getLength() {
		return this.length;
	}

	/**
	 * This bone's position within its armature, which is the number a vertex influence carries, and
	 * the index a per-instance {@link com.sandymandy.strut.animation.state.ArmaturePose} addresses
	 * it by
	 */
	public int getIndex() {
		return this.index;
	}

	/**
	 * This bone's rest pose, for the animation reset logic to lerp back toward
	 * <p>
	 * Unlike {@link GroupBoneDefinition#getInitialSnapshot()}, this is always the same constant - zero
	 * rotation, zero offset, unit scale - never derived from anything authored. An armature bone's
	 * rest pose is entirely captured by {@link #getBindLocal()}; the animatable floats an
	 * {@link ArmatureBoneHandle} exposes are deltas applied on top of it (see
	 * {@link StrutArmature#computeSkinMatrices}), not absolute values, so "no animation" is always
	 * "every delta at its identity" regardless of what the bind pose itself looks like
	 */
	public BoneSnapshot getInitialSnapshot() {
		return this.initialSnapshot;
	}

	/**
	 * Package-private in spirit, public only because {@link com.sandymandy.strut.animation.AnimationProcessor} -
	 * the sole legitimate caller - lives in a different package. Called once, the first time this
	 * bone is registered for animation
	 */
	public void saveInitialSnapshot() {
		if (this.initialSnapshot == null)
			this.initialSnapshot = new BoneSnapshot(this.name, 0, 0, 0, 0, 0, 0, 1, 1, 1);
	}
}
