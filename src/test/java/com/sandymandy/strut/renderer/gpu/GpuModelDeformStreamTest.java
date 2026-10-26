package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.MeshWeld;
import com.sandymandy.strut.loading.binary.BinaryFixtures;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import com.sandymandy.strut.util.RenderUtil;
import java.util.Arrays;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deform stream the bake gives each vertex, and the formula the shaders move a mesh corner by, pinned against what the CPU walk does */
class GpuModelDeformStreamTest {
	private static BakedStrutModel bake(byte[] bytes) {
		return BinaryModelBaker.bake(BinaryModelReader.read(bytes, "m.strutmodel"), "m.strutmodel");
	}

	@Test
	void aCubeOnlyModelHasNoMeshAndEveryVertexIsMarkedAsNone() {
		GpuModel.Accumulated baked = GpuModel.accumulate(bake(BinaryFixtures.cubeModel()));

		assertTrue(baked.meshes().isEmpty());
		assertEquals(baked.vertexBoneIndices().size() * 3, baked.deformInfo().size(), "a (mesh, corner, particle) triple for each vertex");

		for (int v = 0; v < baked.vertexBoneIndices().size(); v++)
			assertEquals(-1, baked.deformInfo().getInt(v * 3), "a cube's vertex belongs to no mesh");
	}

	@Test
	void aMeshsCornersAreNumberedByPolygonAndCorner() {
		GpuModel.Accumulated baked = GpuModel.accumulate(bake(BinaryFixtures.meshModel()));

		assertEquals(1, baked.meshes().size());
		assertEquals(1, baked.meshElements().size());
		assertEquals(baked.vertexBoneIndices().size() * 3, baked.deformInfo().size());

		for (int v = 0; v < baked.vertexBoneIndices().size(); v++) {
			assertEquals(0, baked.deformInfo().getInt(v * 3), "the vertex belongs to the model's only mesh");
			assertEquals(v, baked.deformInfo().getInt(v * 3 + 1), "a mesh that is the whole model: its polygon * 4 + corner is the vertex's own index");
		}
	}

	@Test
	void aMeshsCornersAreAlsoNumberedByTheParticleTheyWeldInto() {
		GpuModel.Accumulated baked = GpuModel.accumulate(bake(BinaryFixtures.meshModel()));

		// a quad, then a triangle that shares the quad's first two corners and has one of its own (its last corner repeated): five places
		int[] particles = {0, 1, 2, 3, 0, 1, 4, 4};

		for (int v = 0; v < particles.length; v++)
			assertEquals(particles[v], baked.deformInfo().getInt(v * 3 + 2), "the particle of corner " + v);

		// and it is the numbering the cloth solver's particles have, since both come from the same weld
		assertEquals(Arrays.toString(particles), Arrays.toString(MeshWeld.of(baked.meshes().get(0)).cornerParticle()));
		assertEquals(5, MeshWeld.of(baked.meshes().get(0)).particles());
	}

	@Test
	void aSkinnedMeshIsNumberedTheSameWay() {
		GpuModel.Accumulated baked = GpuModel.accumulate(bake(BinaryFixtures.riggedModel()));

		assertEquals(1, baked.meshes().size());
		assertEquals(baked.vertexBoneIndices().size() * 3, baked.deformInfo().size());
		assertEquals(0, baked.deformInfo().getInt(0));
		assertEquals(0, baked.deformInfo().getInt(1));
	}

	/**
	 * The vertex shader moves a rigid mesh's corner as {@code bone * (baked + mat3(element) * offset)}, the baked position already being {@code element * rest}.
	 * The CPU walk's {@code renderDeformedMesh} does {@code (bone * element) * (rest + offset)}. They must be one point, for a mesh turned about its own pivot
	 */
	@Test
	void theShadersFormulaIsTheCpuWalksForAMeshTurnedAboutItsPivot() {
		Vec3d pivot = new Vec3d(4, 8, 2);
		Vec3d rotation = new Vec3d(0.3, 0.5, -0.2);
		Matrix4f element = new Matrix4f();

		RenderUtil.applyPivotRotation(element, new Matrix3f(), pivot, rotation);

		Matrix4f bone = new Matrix4f().translate(1f, 2f, 3f).rotateY(0.7f).scale(1f, 1.5f, 0.8f);
		Vector3f rest = new Vector3f(0.2f, 0.9f, -0.4f);
		Vector3f offset = new Vector3f(0.05f, -0.1f, 0.02f);

		Vector4f cpu = new Matrix4f(bone).mul(element).transform(new Vector4f(rest.x + offset.x, rest.y + offset.y, rest.z + offset.z, 1f));

		Vector4f baked = element.transform(new Vector4f(rest.x, rest.y, rest.z, 1f));
		Vector3f turned = new Matrix3f(element).transform(new Vector3f(offset));
		Vector4f gpu = bone.transform(new Vector4f(baked.x + turned.x, baked.y + turned.y, baked.z + turned.z, 1f));

		assertEquals(cpu.x, gpu.x, 1e-5f);
		assertEquals(cpu.y, gpu.y, 1e-5f);
		assertEquals(cpu.z, gpu.z, 1e-5f);
	}
}
