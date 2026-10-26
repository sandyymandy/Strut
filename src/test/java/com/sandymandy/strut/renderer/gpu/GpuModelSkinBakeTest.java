package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.StrutArmature;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutSkin;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GpuModel#accumulate}
 * is where a skinned mesh first gets baked at all (it used to be skipped outright, per
 * {@code GpuModel.bake}'s own earlier doc comment). Runs entirely without a GL context, unlike
 * {@link GpuModel#bake} itself, since {@code accumulate} is the pure-data half {@link GpuModel#bake}
 * was split into for exactly this reason
 */
class GpuModelSkinBakeTest {
	private static final float EPSILON = 1.0e-5f;

	@Test
	@DisplayName("a rigid-only model bakes no skinned meshes and every vertex's SkinSlot is NO_SKIN")
	void rigidModelHasNoSkinning() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.cubeModel(), "cube.strutmodel"), "cube.strutmodel");
		GpuModel.Accumulated baked = GpuModel.accumulate(model);

		assertEquals(0, baked.skinnedMeshes().length, "a rigid-only model has no skinned meshes at all");

		IntArrayList skinSlots = baked.skinSlots();

		assertEquals(baked.vertexBoneIndices().size(), skinSlots.size(),
				"one SkinSlot entry per baked vertex, rigid or not");

		for (int i = 0; i < skinSlots.size(); i++) {
			assertEquals(GpuModel.NO_SKIN, skinSlots.getInt(i), "vertex " + i + "'s SkinSlot");
		}
	}

	@Test
	@DisplayName("a skinned mesh's own record carries its element matrix, armature base and shading mode")
	void skinnedMeshRecordIsCorrect() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		StrutArmature armature = model.armatures().getFirst();
		GroupBoneDefinition rootBone = model.topLevelBones().getFirst();
		StrutMesh mesh = rootBone.getMeshes().getFirst();
		GpuModel.Accumulated baked = GpuModel.accumulate(model);

		assertEquals(1, baked.skinnedMeshes().length, "exactly one skinned mesh in this fixture");

		GpuModel.SkinnedMeshRecord record = baked.skinnedMeshes()[0];

		assertEquals(0, record.armatureBase(), "the only armature in the model starts at offset 0");
		assertEquals(mesh.restNormals() != null, record.smooth(),
				"the record's own shading mode must match whether the mesh actually carries rest normals");
		assertTrue(record.elementMatrix().equals(new Matrix4f(), EPSILON),
				"riggedModel()'s mesh has a zero pivot/rotation, so its element matrix is the identity");
	}

	@Test
	@DisplayName("every skinned vertex bakes its true rest position, not one pre-transformed by the element matrix")
	void skinnedVerticesBakeUntransformedRestPositions() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		GroupBoneDefinition rootBone = model.topLevelBones().getFirst();
		StrutMesh mesh = rootBone.getMeshes().getFirst();
		StrutSkin skin = mesh.skin();
		GpuModel.Accumulated baked = GpuModel.accumulate(model);
		FloatArrayList vertexData = baked.vertexData();
		IntArrayList skinSlots = baked.skinSlots();

		assertEquals(32, skinSlots.size(), "8 quads * 4 corners, none deduplicated - the GPU bake never dedupes, unlike StrutSkin's own per-source-vertex buffer");

		for (int i = 0; i < skinSlots.size(); i++) {
			assertEquals(0, skinSlots.getInt(i), "vertex " + i + " belongs to the fixture's one skinned mesh");
		}

		// Spot-check poly 0 (a bottom-band side quad) - its corners bind to the fixture's known
		// ring/bone layout (SkinningTest's own doc comment: poly 0 corner 0 is the bottom ring)
		int poly0Corner0 = 0;
		int sourceVertex = skin.sourceVertexOf(0, 0);
		Vector3f expectedRest = mesh.restPositions()[sourceVertex];

		assertPosition(vertexData, poly0Corner0, expectedRest.x(), expectedRest.y(), expectedRest.z(),
				"poly 0 corner 0's baked position must equal its true rest position, untouched by any element matrix");
	}

	@Test
	@DisplayName("every skinned vertex's baked joints/weights match StrutSkin's own per-source-vertex arrays")
	void skinnedVerticesCarryTheirSourceVertexsInfluences() {
		BakedStrutModel model = BinaryModelBaker.bake(
				BinaryModelReader.read(BinaryFixtures.riggedModel(), "rig.strutmodel"), "rig.strutmodel");
		StrutArmature armature = model.armatures().getFirst();
		GroupBoneDefinition rootBone = model.topLevelBones().getFirst();
		StrutMesh mesh = rootBone.getMeshes().getFirst();
		StrutSkin skin = mesh.skin();
		GpuModel.Accumulated baked = GpuModel.accumulate(model);
		IntArrayList jointIndices = baked.jointIndices();
		FloatArrayList weights = baked.weights();

		assertEquals(baked.skinSlots().size() * StrutSkin.MAX_INFLUENCES, jointIndices.size());
		assertEquals(baked.skinSlots().size() * StrutSkin.MAX_INFLUENCES, weights.size());

		for (int poly = 0; poly < mesh.polys().length; poly++) {
			for (int corner = 0; corner < mesh.polys()[poly].vertices().length; corner++) {
				int bakedVertex = poly * 4 + corner;
				int sourceVertex = skin.sourceVertexOf(poly, corner);
				int base = bakedVertex * StrutSkin.MAX_INFLUENCES;

				for (int influence = 0; influence < StrutSkin.MAX_INFLUENCES; influence++) {
					// armatureBase is 0 for this fixture's one armature, so the baked (global) joint
					// index equals StrutSkin's own local one directly
					assertEquals(skin.boneIndex(sourceVertex, influence), jointIndices.getInt(base + influence),
							"poly " + poly + " corner " + corner + " influence " + influence + " joint index");
					assertEquals(skin.weight(sourceVertex, influence), weights.getFloat(base + influence), EPSILON,
							"poly " + poly + " corner " + corner + " influence " + influence + " weight");
				}
			}
		}
	}

	private static void assertPosition(FloatArrayList vertexData, int vertex, float x, float y, float z, String what) {
		int base = vertex * 8;

		assertEquals(x, vertexData.getFloat(base), EPSILON, what + " x");
		assertEquals(y, vertexData.getFloat(base + 1), EPSILON, what + " y");
		assertEquals(z, vertexData.getFloat(base + 2), EPSILON, what + " z");
	}
}
