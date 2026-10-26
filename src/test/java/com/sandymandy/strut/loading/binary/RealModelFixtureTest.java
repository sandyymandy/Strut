package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.loading.binary.raw.RawBinaryModel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawGroupBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawMesh;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawTexture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parses a real authored model, converted from Blockbench by {@code tools/bbmodel_to_meshmodel.py}
 * <p>
 * The synthetic fixtures prove the reader against bytes whose every field was chosen to
 * exercise a branch. This proves it against geometry nobody wrote for it: a 33-bone character
 * of 31 meshes with a deep outliner hierarchy, a texture unwrapped on a grid half its pixel
 * size - the case the separate UV-grid field exists for - and 702 faces of which every single
 * one is a triangle
 */
class RealModelFixtureTest {
	private static final String FIXTURE = "/fixtures/base_character_male.strutmodel";

	private static RawBinaryModel model;

	@BeforeAll
	static void parse() throws IOException {
		try (InputStream in = RealModelFixtureTest.class.getResourceAsStream(FIXTURE)) {
			assertNotNull(in, FIXTURE + " is missing from the test resources");

			model = BinaryModelReader.read(in, FIXTURE);
		}
	}

	@Test
	@DisplayName("parses the whole model")
	void parses() {
		// This fixture was captured at v100 and has no armature to exercise v101's own
		// width/colour addition - a fixed historical snapshot, not "whatever this build currently
		// writes", so it's asserted against literally rather than against MODEL_VERSION
		assertEquals(100, model.version());
		assertEquals(33, model.groupBones().size());
		assertEquals(31, model.meshes().size());
		assertTrue(model.cubes().isEmpty());
		assertTrue(model.armatures().isEmpty(), "the source project has no armature yet");
	}

	@Test
	@DisplayName("keeps the outliner hierarchy, not a flat list of roots")
	void keepsHierarchy() {
		long roots = model.groupBones().stream().filter(bone -> bone.parentIndex() == -1).count();

		assertEquals(1, roots, "the character has exactly one root bone");
		assertEquals("root", model.groupBones().getFirst().name());

		RawGroupBone head = model.groupBones().stream()
				.filter(bone -> bone.name().equals("head"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no bone named \"head\""));

		assertNotEquals(-1, head.parentIndex(), "the head hangs off the neck, not off nothing");
	}

	@Test
	@DisplayName("records the UV grid separately from the texture's pixel size")
	void separatesUvGridFromPixels() {
		RawTexture texture = model.textures().getFirst();

		assertEquals(1024, texture.width());
		assertEquals(512, texture.uvWidth(),
				"this texture is unwrapped on a grid half its pixel size; reading UVs against the"
						+ " pixel size would halve every coordinate");
	}

	/**
	 * Real mesh authoring is triangles, all the way down
	 * <p>
	 * Blockbench triangulates on export and this model has not one quad in it, which makes the
	 * degenerate-quad path the ordinary path rather than an edge case: Minecraft's entity
	 * render types are QUADS-mode, so every one of these faces is emitted as a four-vertex
	 * quad with its last corner repeated. Worth pinning here, because a bake that treated
	 * triangles as the unusual case would be slow on every model anyone actually makes
	 */
	@Test
	@DisplayName("is entirely triangles, which makes the degenerate-quad path the only path")
	void isFullyTriangulated() {
		int triangles = 0;
		int other = 0;
		int vertices = 0;

		for (RawMesh mesh : model.meshes()) {
			vertices += mesh.vertexCount();

			assertFalse(mesh.isSkinned());
			assertEquals(-1, mesh.armatureIndex());

			for (RawFace face : mesh.faces()) {
				assertTrue(face.cornerCount() >= 3, "a face needs at least three corners");
				assertEquals(face.cornerCount() * 2, face.uvs().length, "one UV pair per corner");

				if (face.cornerCount() == 3) {
					triangles++;
				}
				else {
					other++;
				}
			}
		}

		assertEquals(428, vertices, "the whole character, which is low-poly by design");
		assertEquals(702, triangles);
		assertEquals(0, other, "Blockbench triangulated this model; nothing here is a quad");
	}

	@Test
	@DisplayName("every mesh is owned by exactly one bone")
	void everyMeshIsOwned() {
		boolean[] owned = new boolean[model.meshes().size()];

		for (RawGroupBone bone : model.groupBones()) {
			for (RawBinaryModel.RawElement element : bone.elements()) {
				if (element.kind() != StrutBinaryFormats.ELEMENT_KIND_MESH)
					continue;

				assertFalse(owned[element.index()],
						"mesh " + element.index() + " is claimed by more than one bone");

				owned[element.index()] = true;
			}
		}

		for (int i = 0; i < owned.length; i++) {
			assertTrue(owned[i], "mesh " + i + " (\"" + model.meshes().get(i).name()
					+ "\") hangs off no bone and would never render");
		}
	}
}
