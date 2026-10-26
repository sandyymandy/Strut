package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.ParticleDeform;
import com.sandymandy.strut.animation.state.RigidDeform;
import com.sandymandy.strut.cache.object.MeshWeld;
import com.sandymandy.strut.cache.object.StrutVertex;
import org.joml.Vector3f;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a group's instances move their mesh vertices by is packed as one buffer: a table of where each instance's offsets start, then the offsets and the normals */
class MeshDeformPackTest {
	private static StrutMesh mesh(int polys) {
		return new StrutMesh(new StrutPoly[polys], Vec3d.ZERO, Vec3d.ZERO, false);
	}

	private static float[] filled(int polys, float start) {
		float[] values = new float[polys * 12];

		for (int i = 0; i < values.length; i++)
			values[i] = start + i;

		return values;
	}

	@Test
	void nothingMovedPacksNothing() {
		StrutMesh[] meshes = {mesh(2)};
		MeshDeformPack pack = new MeshDeformPack();

		assertFalse(pack.build(meshes, 3, i -> null));
		assertFalse(pack.build(meshes, 1, i -> new ModelInstance()), "an instance that moves no mesh");
	}

	@Test
	void anInstanceThatMovesAMeshHasItsEntryAndTheOthersHaveNone() {
		StrutMesh skirt = mesh(2);
		StrutMesh cape = mesh(1);
		StrutMesh[] meshes = {skirt, cape};
		ModelInstance moving = new ModelInstance();
		ModelInstance still = new ModelInstance();
		float[] offsets = filled(2, 100f);

		moving.setMeshOffsets(skirt, offsets);

		MeshDeformPack pack = new MeshDeformPack();

		assertTrue(pack.build(meshes, 2, i -> i == 0 ? moving : still));
		assertEquals(2, pack.meshCount());
		assertFalse(pack.hasNormals());
		assertEquals(4, pack.offsetBase(), "after the table: 2 instances times 2 meshes");
		assertEquals(4 + 8 * 4, pack.words());

		// the table: instance 0 moves mesh 0 from entry 0; nothing else moves
		assertEquals(0, pack.data().getInt(0));
		assertEquals(-1, pack.data().getInt(4));
		assertEquals(-1, pack.data().getInt(8));
		assertEquals(-1, pack.data().getInt(12));

		// the offsets: four words for each corner, the last a pad
		for (int corner = 0; corner < 8; corner++) {
			int at = (pack.offsetBase() + corner * 4) * 4;

			assertEquals(offsets[corner * 3], pack.data().getFloat(at));
			assertEquals(offsets[corner * 3 + 1], pack.data().getFloat(at + 4));
			assertEquals(offsets[corner * 3 + 2], pack.data().getFloat(at + 8));
		}
	}

	@Test
	void entriesFollowOneAnotherAcrossInstancesAndMeshes() {
		StrutMesh a = mesh(1);
		StrutMesh b = mesh(2);
		StrutMesh[] meshes = {a, b};
		ModelInstance first = new ModelInstance();
		ModelInstance second = new ModelInstance();

		first.setMeshOffsets(a, filled(1, 0f));
		first.setMeshOffsets(b, filled(2, 50f));
		second.setMeshOffsets(b, filled(2, 90f));

		MeshDeformPack pack = new MeshDeformPack();

		assertTrue(pack.build(meshes, 2, i -> i == 0 ? first : second));

		assertEquals(0, pack.data().getInt(0), "first instance, mesh a: entry 0");
		assertEquals(4, pack.data().getInt(4), "first instance, mesh b: after a's four corners");
		assertEquals(-1, pack.data().getInt(8), "second instance moves no a");
		assertEquals(12, pack.data().getInt(12), "second instance, mesh b: after 4 + 8 corners");
		assertEquals(90f, pack.data().getFloat((pack.offsetBase() + 12 * 4) * 4), "the first float of the second instance's b");
	}

	@Test
	void normalsAreThereOnlyWhereGivenAndFlaggedSo() {
		StrutMesh smooth = mesh(1);
		StrutMesh flat = mesh(1);
		StrutMesh[] meshes = {smooth, flat};
		ModelInstance instance = new ModelInstance();

		instance.setMeshOffsets(smooth, filled(1, 0f), filled(1, 500f));
		instance.setMeshOffsets(flat, filled(1, 0f));

		MeshDeformPack pack = new MeshDeformPack();

		assertTrue(pack.build(meshes, 1, i -> instance));
		assertTrue(pack.hasNormals());
		assertEquals(2, pack.offsetBase(), "after the table: one instance, two meshes");
		assertEquals(2 + 8 * 4, pack.normalBase(), "the normals start after the offsets of the eight corners of both meshes");
		assertEquals(2 + 8 * 4 * 2, pack.words(), "the table, the offsets and the normals");

		// the smooth mesh's corner 0 has its normal and the flag 1, the flat mesh's has zeros and the flag 0
		int smoothAt = pack.normalBase() * 4;
		int flatAt = (pack.normalBase() + 4 * 4) * 4;

		assertEquals(500f, pack.data().getFloat(smoothAt));
		assertEquals(1f, pack.data().getFloat(smoothAt + 12));
		assertEquals(0f, pack.data().getFloat(flatAt));
		assertEquals(0f, pack.data().getFloat(flatAt + 12));
	}

	@Test
	void anArrayOfTheWrongSizeIsIgnoredRatherThanReadIntoTheNextMesh() {
		StrutMesh mesh = mesh(2);
		ModelInstance instance = new ModelInstance();

		instance.setMeshOffsets(mesh, new float[12]);

		assertFalse(new MeshDeformPack().build(new StrutMesh[] {mesh}, 1, i -> instance));
	}

	@Test
	void theBufferIsReusedAndFollowsTheLatestBuild() {
		StrutMesh mesh = mesh(1);
		ModelInstance instance = new ModelInstance();
		MeshDeformPack pack = new MeshDeformPack();

		instance.setMeshOffsets(mesh, filled(1, 1f));
		assertTrue(pack.build(new StrutMesh[] {mesh}, 1, i -> instance));
		assertEquals(1f, pack.data().getFloat(pack.offsetBase() * 4));

		instance.setMeshOffsets(mesh, filled(1, 7f));
		assertTrue(pack.build(new StrutMesh[] {mesh}, 1, i -> instance));
		assertEquals(7f, pack.data().getFloat(pack.offsetBase() * 4));
	}

	/** A mesh of {@code polys} quads, each with its own four corners at places of their own (so each corner is a particle of its own) */
	private static StrutMesh realMesh(int polys) {
		StrutPoly[] made = new StrutPoly[polys];

		for (int p = 0; p < polys; p++) {
			StrutVertex[] corners = new StrutVertex[4];

			for (int c = 0; c < 4; c++)
				corners[c] = new StrutVertex(new Vector3f(p * 4 + c, 0, 0), 0f, 0f, new Vector3f(0, 1, 0));

			made[p] = new StrutPoly(corners, new Vector3f(0, 1, 0));
		}

		return new StrutMesh(made, Vec3d.ZERO, Vec3d.ZERO, false);
	}

	@Test
	void aValueForEachParticleIsPackedOnceForEachParticleAndFlaggedByTheTable() {
		StrutMesh mesh = realMesh(2);
		ParticleDeform deform = new ParticleDeform(mesh, MeshWeld.of(mesh));
		ModelInstance instance = new ModelInstance();

		for (int i = 0; i < deform.offsets.length; i++) {
			deform.offsets[i] = 10f + i;
			deform.normalDeltas[i] = 100f + i;
		}

		instance.setMeshDeform(mesh, deform);

		MeshDeformPack pack = new MeshDeformPack();

		assertTrue(pack.build(new StrutMesh[] {mesh}, 1, i -> instance));
		assertTrue(pack.hasNormals(), "the change to each normal is always given");

		// eight particles, not the thirty-two floats' worth of corners a value for each corner would take
		assertEquals(1 + 8 * 4 * 2, pack.words());
		assertEquals(-2, pack.data().getInt(0), "kind 0 (particles), from slot 0: -2 - (0 * 2 + 0)");

		for (int k = 0; k < 8; k++) {
			int at = (pack.offsetBase() + k * 4) * 4;
			int normalAt = (pack.normalBase() + k * 4) * 4;

			assertEquals(10f + k * 3, pack.data().getFloat(at));
			assertEquals(10f + k * 3 + 2, pack.data().getFloat(at + 8));
			assertEquals(100f + k * 3 + 1, pack.data().getFloat(normalAt + 4));
			assertEquals(1f, pack.data().getFloat(normalAt + 12));
		}
	}

	@Test
	void aRigidMeshIsThreeSlotsWhateverItsSize() {
		StrutMesh tip = realMesh(40);
		StrutMesh pouch = realMesh(1);
		ParticleDeform particles = new ParticleDeform(pouch, MeshWeld.of(pouch));
		RigidDeform rigid = new RigidDeform(tip);
		ModelInstance instance = new ModelInstance();

		for (int i = 0; i < rigid.offset.length; i++)
			rigid.offset[i] = 1f + i;

		for (int i = 0; i < rigid.turn.length; i++)
			rigid.turn[i] = 50f + i;

		instance.setMeshDeform(pouch, particles);
		instance.setMeshDeform(tip, rigid);

		MeshDeformPack pack = new MeshDeformPack();

		assertTrue(pack.build(new StrutMesh[] {pouch, tip}, 1, i -> instance));

		// four particles then the tip's three rows
		assertEquals(2 + 7 * 4 * 2, pack.words());
		assertEquals(-2, pack.data().getInt(0), "the pouch: particles from slot 0");
		assertEquals(-2 - (4 * 2 + 1), pack.data().getInt(4), "the tip: rigid, from slot 4: -2 - (4 * 2 + 1)");

		for (int row = 0; row < 3; row++) {
			int at = (pack.offsetBase() + (4 + row) * 4) * 4;
			int normalAt = (pack.normalBase() + (4 + row) * 4) * 4;

			assertEquals(1f + row * 4, pack.data().getFloat(at), "row " + row + " of A | b");
			assertEquals(1f + row * 4 + 3, pack.data().getFloat(at + 12), "its b");
			assertEquals(50f + row * 3 + 2, pack.data().getFloat(normalAt + 8), "row " + row + " of the normal turn");
		}
	}

	@Test
	void aValueForEachCornerAndOneForEachParticleCanShareABuffer() {
		StrutMesh perCorner = realMesh(1);
		StrutMesh perParticle = realMesh(1);
		ModelInstance instance = new ModelInstance();

		instance.setMeshOffsets(perCorner, filled(1, 3f));
		instance.setMeshDeform(perParticle, new ParticleDeform(perParticle, MeshWeld.of(perParticle)));

		MeshDeformPack pack = new MeshDeformPack();

		assertTrue(pack.build(new StrutMesh[] {perCorner, perParticle}, 1, i -> instance));
		assertEquals(0, pack.data().getInt(0), "a value for each corner: its slot");
		assertEquals(-2 - 4 * 2, pack.data().getInt(4), "then the particles' from slot 4");
		assertEquals(3f, pack.data().getFloat(pack.offsetBase() * 4));
	}

	@Test
	void aBuildBiggerThanTheLastOneStillFitsTheBufferItAlreadyHas() {
		StrutMesh small = mesh(1);
		StrutMesh big = mesh(2);
		ModelInstance instance = new ModelInstance();
		MeshDeformPack pack = new MeshDeformPack();

		instance.setMeshOffsets(small, filled(1, 1f));
		assertTrue(pack.build(new StrutMesh[] {small}, 1, i -> instance));
		assertEquals(17, pack.words());

		// the buffer was made with room for this (256 bytes), but the first build's size, 68, must not be the limit the second is written under
		instance.setMeshOffsets(big, filled(2, 3f));
		assertTrue(pack.build(new StrutMesh[] {small, big}, 1, i -> instance));
		assertEquals(3f, pack.data().getFloat((pack.offsetBase() + 4 * 4) * 4));
	}
}
