package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.SkinningState;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutSkin;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawArmature;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawElement;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawGroupBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawInfluence;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawMesh;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A skinned mesh parented to an Armature that sits under an offset group, with its own origin and
 * a {@code [180, 0, 180]} rotation - the shape of a real authored rig that rendered at the model's
 * feet with its bone motion mirrored on X. In Blockbench the Armature's frame is its parent group's
 * pivot, the mesh's origin and the bones' heads are relative to that frame (and bones to their
 * parents), and skinning happens after the mesh's own rotation is applied
 */
class ArmatureFrameBakeTest {
	private static final float EPSILON = 1.0e-5f;

	private BakedStrutModel model;
	private StrutArmature armature;
	private StrutMesh mesh;

	@BeforeEach
	void bake() {
		List<RawGroupBone> groupBones = List.of(
				new RawGroupBone("root", -1, new Vector3f(), new Vector3f(), List.of()),
				new RawGroupBone("pelvis", 0, new Vector3f(0, 16, 0), new Vector3f(),
						List.of(new RawElement(StrutBinaryFormats.ELEMENT_KIND_MESH, 0))));

		List<RawBone> bones = List.of(
				new RawBone("base", -1, new Vector3f(0, 0, 0), new Vector3f(), 4, 1, 0),
				new RawBone("tip", 0, new Vector3f(0, 4, 0), new Vector3f(), 4, 1, 0));

		List<RawInfluence> onTip = List.of(new RawInfluence(1, 1f));
		RawMesh rawMesh = new RawMesh("blob", 0, new Vector3f(0, -2, 0), new Vector3f(180, 0, 180), false, 0,
				new float[] {1, 2, 0, 1, 3, 0, -1, 3, 0, -1, 2, 0, 0, 2.5f, 1},
				List.of(new RawFace(new int[] {0, 1, 2, 3}, new float[] {0, 0, 1, 0, 1, 1, 0, 1}),
						new RawFace(new int[] {0, 1, 4}, new float[] {0, 0, 1, 0, 1, 1})),
				// Vertex 4 carries no weight at all - a real authored mesh had over 200 of these
				List.of(onTip, onTip, onTip, onTip, List.of()), false);

		RawBinaryModel raw = new RawBinaryModel(StrutBinaryFormats.MODEL_VERSION, "strut:test/frame", 16,
				new Vector3f(-16, 0, -16), new Vector3f(16, 32, 16), List.of("strut:test/frame"),
				List.of(new RawTexture("texture", 16, 16, 16, 16)), groupBones, List.of(rawMesh), List.of(),
				List.of(new RawArmature("rig", new Vector3f(), 1, bones)));

		this.model = BinaryModelBaker.bake(raw, "frame");
		this.armature = this.model.armatures().getFirst();
		this.mesh = this.model.getBone("pelvis").orElseThrow().getMeshes().getFirst();
	}

	@Test
	@DisplayName("a mesh under an Armature is anchored at the armature's group pivot, not the model origin")
	void anchoredAtArmatureFrame() {
		// frame (0, 16, 0) + origin (0, -2, 0) + Y180 of (1, 2, 0) = (-1, 16, 0)
		Vector3f rest = this.mesh.restPositions()[0];

		assertEquals(-1 / 16f, rest.x, EPSILON, "the mesh's own 180/180 rotation is baked in before skinning");
		assertEquals(16 / 16f, rest.y, EPSILON, "the armature group's pivot is added, not dropped");
		assertEquals(0, rest.z, EPSILON);
		assertEquals(0, this.mesh.rotation().length(), EPSILON, "nothing is left for the renderer to rotate after skinning");
	}

	@Test
	@DisplayName("armature bone heads accumulate from the armature's frame, parent to child")
	void bonesInTheSameFrame() {
		Vector3f tipHead = this.armature.getBone("tip").getInverseBind().invert(new Matrix4f())
				.transformPosition(new Vector3f());

		assertEquals(0, tipHead.x, EPSILON);
		assertEquals((16 + 4) / 16f, tipHead.y, EPSILON, "pelvis pivot + base head + tip head");
		assertEquals(0, tipHead.z, EPSILON);
	}

	@Test
	@DisplayName("moving a bone +X in Blockbench moves the skinned mesh +X, not mirrored")
	void positionIsNotMirrored() {
		ModelInstance instance = new ModelInstance();
		SkinningState skinning = new SkinningState();
		StrutSkin skin = this.mesh.skin();
		Vector3f[] skinned = skinning.skinnedVerticesFor(skin);

		// BinaryAnimationBaker stores an authored +1 X as -1, cancelling the renderer's own negation
		instance.armatureHandleFor(this.model, this.armature, this.armature.getBone("tip")).updatePosition(-1, 0, 0);
		skinning.poseArmatures(this.model, instance);
		skin.skin(this.mesh.restPositions(), skinning.skinMatricesFor(this.armature), skinned);

		assertEquals(1 / 16f, skinned[0].x - this.mesh.restPositions()[0].x, EPSILON);
		assertEquals(0, skinned[0].y - this.mesh.restPositions()[0].y, EPSILON);
	}

	@Test
	@DisplayName("an unweighted vertex stays at rest instead of collapsing to the origin, like Blockbench")
	void unweightedVertexStaysAtRest() {
		ModelInstance instance = new ModelInstance();
		SkinningState skinning = new SkinningState();
		StrutSkin skin = this.mesh.skin();
		Vector3f[] skinned = skinning.skinnedVerticesFor(skin);

		instance.armatureHandleFor(this.model, this.armature, this.armature.getBone("tip")).updatePosition(-1, 0, 0);
		skinning.poseArmatures(this.model, instance);
		skin.skin(this.mesh.restPositions(), skinning.skinMatricesFor(this.armature), skinned);

		Vector3f rest = this.mesh.restPositions()[4];

		assertTrue(rest.length() > 0.5f, "sanity: the unweighted vertex's rest position is well away from the origin");
		assertEquals(rest.x, skinned[4].x, EPSILON);
		assertEquals(rest.y, skinned[4].y, EPSILON);
		assertEquals(rest.z, skinned[4].z, EPSILON);
	}
}
