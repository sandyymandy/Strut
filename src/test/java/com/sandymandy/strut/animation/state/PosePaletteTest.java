package com.sandymandy.strut.animation.state;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import com.sandymandy.strut.util.RenderUtil;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PosePalette#compute} has to agree exactly with what the real render walk computes one
 * bone at a time via {@link RenderUtil#prepMatrixForBone} through a {@link MatrixStack} - this
 * test drives both from the same {@link Pose} on a real multi-level bone tree and compares every
 * bone's resulting matrix, rather than trusting that two independently-written implementations of
 * the same maths agree. The palette's own gate calls for exactly this:
 * "palette output verified identical to the recursive walk's matrices (unit test comparing both
 * for a real rig)"
 * <p>
 * Uses {@link RenderUtil#prepMatrixForBone} itself as the reference, not a hand-written
 * re-implementation of its maths - a second reimplementation could reproduce the same mistake the
 * first one made, which would defeat the point. The tree is built the same way
 * {@code BakedStrutModelFlatteningTest}'s JSON-bake-path case builds one - by hand, through
 * {@link GroupBoneDefinition}'s public constructor and its mutable {@code getChildBones()} list -
 * since none of the binary fixtures happen to carry a multi-level group-bone hierarchy (only
 * {@code riggedModel()}'s armature is deep; its one group bone is a flat root)
 */
class PosePaletteTest {
	private static final float EPSILON = 1.0e-4f;

	private BakedStrutModel model;
	private ModelInstance instance;

	@BeforeEach
	void buildATreeWithRealDepthAndOffPivots() {
		// root -> child -> grandchild, plus a sibling of child, each with a distinct pivot so a
		// bug in how a pivot composes into its parent's space cannot hide behind a shared value
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition child = new GroupBoneDefinition(root, "child", false, null, null, null);
		GroupBoneDefinition grandchild = new GroupBoneDefinition(child, "grandchild", false, null, null, null);
		GroupBoneDefinition sibling = new GroupBoneDefinition(root, "sibling", false, null, null, null);

		root.bakePivot(0, 8, 0);
		child.bakePivot(3, 10, -2);
		grandchild.bakePivot(-1, 14, 4);
		sibling.bakePivot(5, 6, 1);

		root.getChildBones().add(child);
		root.getChildBones().add(sibling);
		child.getChildBones().add(grandchild);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		this.model = new BakedStrutModel(List.of(root), properties);
		this.instance = new ModelInstance();
	}

	@Test
	void aTrackedBoneMatrixHasItsOriginAtTheBonePivot() {
		// The bug this pins: the tracked matrix was the geometry matrix, so a bone's "position" (a camera, a seat) was the model origin moved by
		// the bone, not the bone. GeckoLib captured the matrix before translating away from the pivot, so (0, 0, 0) was the pivot
		Pose current = this.instance.poseFor(this.model);
		GroupBoneDefinition child = this.model.flatBones()[1];

		this.instance.resetPoseHistory();

		Matrix4f tracked = new Matrix4f();

		PosePalette.computeBoneAtPivot(this.instance.renderedPose(), child, tracked);

		Vector3f rest = tracked.transformPosition(new Vector3f());
		GroupBoneDefinition root = this.model.flatBones()[0];

		// Rest pose: every bone's transform is a pure translate-and-back, so the origin lands on the pivot itself (root pivot 0,8,0 is a parent
		// that also rests, so it adds nothing)
		assertEquals(child.getPivotX() / 16f, rest.x, EPSILON);
		assertEquals(child.getPivotY() / 16f, rest.y, EPSILON);
		assertEquals(child.getPivotZ() / 16f, rest.z, EPSILON);
		assertEquals(0, root.getIndex());

		// Moving the bone moves that point, with the X flip the renderer applies to a bone's position
		current.setPosition(child.getIndex(), 16, 32, -16);
		this.instance.resetPoseHistory();
		PosePalette.computeBoneAtPivot(this.instance.renderedPose(), child, tracked);

		Vector3f moved = tracked.transformPosition(new Vector3f());

		assertEquals(child.getPivotX() / 16f - 1f, moved.x, EPSILON);
		assertEquals(child.getPivotY() / 16f + 2f, moved.y, EPSILON);
		assertEquals(child.getPivotZ() / 16f - 1f, moved.z, EPSILON);

		// And the geometry matrix is still what draws the bone: its origin is NOT the pivot
		Matrix4f geometry = new Matrix4f();

		PosePalette.computeBone(this.instance.renderedPose(), child, geometry);

		Vector3f origin = geometry.transformPosition(new Vector3f());

		assertEquals(-1f, origin.x, EPSILON);
	}

	@Test
	void matchesTheMatrixStackWalkForAPosedRig() {
		Pose current = this.instance.poseFor(this.model);

		// A distinct, non-trivial rotation/position/scale per bone, so an ordering or
		// parent-composition bug shows up as a real numeric mismatch rather than an accidental
		// match (every bone posed identically could hide a bug that only bites on divergent poses)
		for (int i = 0; i < current.boneCount(); i++) {
			float seed = i + 1;

			current.setRotation(i, 0.1f * seed, -0.2f * seed, 0.15f * seed);
			current.setPosition(i, seed, -seed * 0.5f, seed * 0.25f);
			current.setScale(i, 1f + 0.05f * seed, 1f - 0.02f * seed, 1f + 0.03f * seed);
		}

		// PosePalette reads renderedPose(), same as every real render-time consumer - syncing it
		// from current is what a real animation update's interpolateRenderedPose call would also
		// do at partial tick 1
		this.instance.resetPoseHistory();

		Pose rendered = this.instance.renderedPose();
		Matrix4f[] palette = new Matrix4f[rendered.boneCount()];

		for (int i = 0; i < palette.length; i++) {
			palette[i] = new Matrix4f();
		}

		PosePalette.compute(this.model, rendered, palette);

		MatrixStack poseStack = new MatrixStack();

		assertMatchesRecursiveWalk(poseStack, palette, this.instance.topLevelHandlesFor(this.model));
	}

	@Test
	void computeBoneMatchesTheFullPaletteForEveryBone() {
		// computeBone walks only one bone's
		// ancestor chain rather than every bone, so it has to agree with compute()'s output at every
		// index or StrutRenderer#trackBoneMatrices (the sole consumer this replaces the MatrixStack
		// walk for) would silently diverge per bone from what the full palette - and therefore the
		// GPU path - actually renders
		Pose current = this.instance.poseFor(this.model);

		for (int i = 0; i < current.boneCount(); i++) {
			float seed = i + 1;

			current.setRotation(i, 0.1f * seed, -0.2f * seed, 0.15f * seed);
			current.setPosition(i, seed, -seed * 0.5f, seed * 0.25f);
			current.setScale(i, 1f + 0.05f * seed, 1f - 0.02f * seed, 1f + 0.03f * seed);
		}

		this.instance.resetPoseHistory();

		Pose rendered = this.instance.renderedPose();
		Matrix4f[] palette = new Matrix4f[rendered.boneCount()];

		for (int i = 0; i < palette.length; i++) {
			palette[i] = new Matrix4f();
		}

		PosePalette.compute(this.model, rendered, palette);

		for (GroupBoneDefinition definition : this.model.flatBones()) {
			Matrix4f viaIndex = new Matrix4f();
			Matrix4f viaDefinition = new Matrix4f();

			PosePalette.computeBone(this.model, rendered, definition.getIndex(), viaIndex);
			PosePalette.computeBone(rendered, definition, viaDefinition);

			assertMatrixEquals(palette[definition.getIndex()], viaIndex, definition.getName());
			assertMatrixEquals(palette[definition.getIndex()], viaDefinition, definition.getName());
		}
	}

	@Test
	void zeroPoseIsIdentityComposedThroughThePivotOnly() {
		// Every bone at its rest pose (Pose defaults to identity) - the palette should still
		// differ per bone, since each has its own pivot, but every bone's local rotation/scale
		// contribution should be a no-op. poseFor binds the instance to the model (resetPoseHistory
		// is a no-op before that, since there is no Pose yet to reset)
		this.instance.poseFor(this.model);
		this.instance.resetPoseHistory();

		Pose rendered = this.instance.renderedPose();
		Matrix4f[] palette = new Matrix4f[rendered.boneCount()];

		for (int i = 0; i < palette.length; i++) {
			palette[i] = new Matrix4f();
		}

		PosePalette.compute(this.model, rendered, palette);

		MatrixStack poseStack = new MatrixStack();

		assertMatchesRecursiveWalk(poseStack, palette, this.instance.topLevelHandlesFor(this.model));
	}

	private void assertMatchesRecursiveWalk(MatrixStack poseStack, Matrix4f[] palette, List<GroupBoneHandle> bones) {
		for (GroupBoneHandle bone : bones) {
			poseStack.push();
			RenderUtil.prepMatrixForBone(poseStack, bone);

			assertMatrixEquals(poseStack.peek().getPositionMatrix(), palette[bone.getIndex()], bone.getName());

			assertMatchesRecursiveWalk(poseStack, palette, bone.getChildBones());
			poseStack.pop();
		}
	}

	private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual, String boneName) {
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON,
						"bone \"" + boneName + "\" element (" + row + "," + column + ")");
			}
		}
	}
}
