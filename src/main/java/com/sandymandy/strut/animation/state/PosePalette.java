package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import org.joml.Matrix4f;

/**
 * Every one of a model instance's group bones' model-space transform, computed in one flat,
 * parent-before-child pass - the "PosePalette": what the
 * current per-bone {@code MatrixStack} push/translate/rotate/scale/pop walk
 * (see {@link com.sandymandy.strut.util.RenderUtil#prepMatrixForBone}) computes implicitly, one
 * bone at a time, with an allocating {@code MatrixStack.Entry} push for each - built here as plain
 * {@code Matrix4f[]} instead
 * <p>
 * "Model-space" means relative to the model's own root, with no entity-level transform baked in -
 * the same thing {@link com.sandymandy.strut.cache.object.GroupBoneHandle#getModelSpaceMatrix()}
 * exposes per bone, backed by {@link #computeBone}. A palette entry for a top-level bone
 * <em>is</em> that bone's local transform; a child's entry is its parent's entry composed with its
 * own local transform
 *
 * <h2>Status</h2>
 *
 * <p>{@link #compute} is what {@link com.sandymandy.strut.renderer.gpu.GpuBatchQueue} uploads as
 * each group's bone palette every frame. {@link #computeBone} is a separate
 * addition: {@code StrutRenderer#trackBoneMatrices} calls it instead of the {@code MatrixStack}-based
 * computation every {@code isTrackingMatrices()} override used to hand-roll. The CPU render path
 * still walks bones through {@code MatrixStack} for actual geometry submission - that stays true
 * for armature (skinned) models until GPU skinning exists - but bone matrix tracking no longer
 * needs it at all
 */
public final class PosePalette {
	private PosePalette() {}

	/**
	 * Fills {@code dest} with {@code model}'s bones' model-space transforms as posed by
	 * {@code pose}, in {@link BakedStrutModel#flatBones()} order
	 * <p>
	 * {@code dest} must be at least {@code model.flatBones().length} long; sized and indexed
	 * identically to {@link BakedStrutModel#flatBones()} and {@link Pose} itself, so a caller can
	 * reuse one array across frames without reallocating
	 */
	public static void compute(BakedStrutModel model, Pose pose, Matrix4f[] dest) {
		GroupBoneDefinition[] definitions = model.flatBones();

		for (int i = 0; i < definitions.length; i++) {
			GroupBoneDefinition definition = definitions[i];
			GroupBoneDefinition parent = definition.getParent();
			Matrix4f matrix = dest[i];

			if (parent == null)
				matrix.identity();
			else
				matrix.set(dest[parent.getIndex()]);

			composeLocal(matrix, pose, i, definition);
		}
	}

	/**
	 * Fills {@code dest} with a single bone's model-space transform, without computing every other
	 * bone's - walks only that bone's own ancestor chain (root to bone), O(depth) rather than
	 * {@link #compute}'s O(bone count). Produces byte-identical output to running {@link #compute}
	 * and reading index {@code boneIndex} - this is that same computation, just without the other
	 * bones' work, for a caller that only ever needs one (an attachment; every
	 * {@code isTrackingMatrices()} consumer is collapsed into
	 * {@code StrutRenderer#trackBoneMatrices} - see that method's own doc comment for why the old
	 * {@code MatrixStack}-derived computation reduces algebraically to exactly this)
	 */
	public static void computeBone(BakedStrutModel model, Pose pose, int boneIndex, Matrix4f dest) {
		computeBone(pose, model.getFlatBone(boneIndex), dest);
	}

	/**
	 * Same as {@link #computeBone(BakedStrutModel, Pose, int, Matrix4f)}, from a bone definition
	 * already in hand rather than an index - what a caller already holding a
	 * {@link com.sandymandy.strut.cache.object.GroupBoneHandle} (via
	 * {@link com.sandymandy.strut.cache.object.GroupBoneHandle#getDefinition()}) can call directly,
	 * with no {@link BakedStrutModel} reference needed at all
	 */
	public static void computeBone(Pose pose, GroupBoneDefinition definition, Matrix4f dest) {
		GroupBoneDefinition parent = definition.getParent();

		if (parent == null)
			dest.identity();
		else
			computeBone(pose, parent, dest);

		composeLocal(dest, pose, definition.getIndex(), definition);
	}

	/**
	 * A bone's transform with its origin <b>at the bone's own pivot</b>, which is what GeckoLib's tracked bone matrices were: it captured them after
	 * the translate, rotate and scale but before translating back away from the pivot. {@link #computeBone} ends with that translate-away, because it
	 * is the matrix that carries the bone's model-space geometry to where it is drawn; applying it to the point (0, 0, 0) gives the model's origin
	 * moved by the bone, not the bone. This one is what a position, a camera, a passenger seat or an attached item wants: (0, 0, 0) is the bone's pivot,
	 * moved and rotated with the bone
	 */
	public static void computeBoneAtPivot(Pose pose, GroupBoneDefinition definition, Matrix4f dest) {
		int index = definition.getIndex();

		computeBone(pose, definition, dest);
		dest.translate((definition.getPivotX() + pose.getPivotDeltaX(index)) / 16f, (definition.getPivotY() + pose.getPivotDeltaY(index)) / 16f,
				(definition.getPivotZ() + pose.getPivotDeltaZ(index)) / 16f);
	}

	/**
	 * Post-multiplies bone {@code index}'s local transform onto {@code matrix} - the same
	 * translate-to-bone, translate-to-pivot, rotate, scale, translate-away-from-pivot sequence
	 * {@link com.sandymandy.strut.util.RenderUtil#prepMatrixForBone} applies via five separate
	 * {@code MatrixStack} calls, expressed here as direct {@link Matrix4f} mutation instead so nine
	 * calls (five bone ops, each potentially allocating a {@code Quaternionf} on the rotate step)
	 * become one composed local transform with no {@code MatrixStack} involved at all
	 * <p>
	 * Skips the rotation step entirely when the bone's rotation is exactly zero, and uses a single
	 * combined {@code rotateZYX} rather than three separate axis rotations otherwise - the same
	 * reasoning {@link com.sandymandy.strut.util.RenderUtil#optionalRotateZYX} and
	 * {@link com.sandymandy.strut.util.RenderUtil#applyPivotRotation} already apply
	 */
	private static void composeLocal(Matrix4f matrix, Pose pose, int index, GroupBoneDefinition definition) {
		float pivotX = definition.getPivotX() + pose.getPivotDeltaX(index);
		float pivotY = definition.getPivotY() + pose.getPivotDeltaY(index);
		float pivotZ = definition.getPivotZ() + pose.getPivotDeltaZ(index);

		matrix.translate(-pose.getPosX(index) / 16f, pose.getPosY(index) / 16f, pose.getPosZ(index) / 16f);
		matrix.translate(pivotX / 16f, pivotY / 16f, pivotZ / 16f);

		float rotX = pose.getRotX(index);
		float rotY = pose.getRotY(index);
		float rotZ = pose.getRotZ(index);

		if (rotX == 0 && rotY == 0) {
			if (rotZ != 0)
				matrix.rotateZ(rotZ);
		}
		else if (rotX == 0 && rotZ == 0) {
			matrix.rotateY(rotY);
		}
		else if (rotY == 0 && rotZ == 0) {
			matrix.rotateX(rotX);
		}
		else {
			matrix.rotateZYX(rotZ, rotY, rotX);
		}

		matrix.scale(pose.getScaleX(index), pose.getScaleY(index), pose.getScaleZ(index));
		matrix.translate(-pivotX / 16f, -pivotY / 16f, -pivotZ / 16f);
	}
}
