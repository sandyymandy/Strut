package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryFixtures.ModelHeader;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawArmature;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCube;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawGroupBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawInfluence;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawMesh;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sandymandy.strut.loading.binary.StrutBinaryFormats.*;
import static org.junit.jupiter.api.Assertions.*;

class BinaryModelReaderTest {
	private static final String SOURCE = "test.strutmodel";

	private static RawBinaryModel read(byte[] bytes) {
		return BinaryModelReader.read(bytes, SOURCE);
	}

	/**
	 * Assert that the bytes are refused, and that the complaint names both the file and enough
	 * of the field to act on
	 */
	private static void assertRefused(byte[] bytes, String... expectedFragments) {
		BinaryFormatException thrown = assertThrows(BinaryFormatException.class, () -> read(bytes));

		assertTrue(thrown.getMessage().contains(SOURCE),
				() -> "error should name the file it came from, but was: " + thrown.getMessage());

		for (String fragment : expectedFragments) {
			assertTrue(thrown.getMessage().contains(fragment),
					() -> "error should mention \"" + fragment + "\", but was: " + thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("a well-formed model")
	class WellFormed {
		@Test
		@DisplayName("reads a cube, its flags and its box UVs")
		void readsCube() {
			RawBinaryModel model = read(BinaryFixtures.cubeModel());

			assertEquals(MODEL_VERSION, model.version());
			assertEquals("strut:test/cube", model.modelId());
			assertEquals(DEFAULT_UNITS_PER_BLOCK, model.unitsPerBlock());
			assertEquals(1, model.cubes().size());
			assertTrue(model.meshes().isEmpty());
			assertTrue(model.armatures().isEmpty());

			RawCube cube = model.cubes().getFirst();

			assertEquals("box", cube.name());
			assertEquals(0.25f, cube.inflate());
			assertTrue(cube.mirror());
			assertTrue(cube.boxUV(), "the box-UV flag is what stops a Bedrock model degrading on a round trip");
			assertEquals(8f, cube.to().y);
			assertEquals(1, cube.faces().size());
			assertEquals(2, cube.faces().getFirst().faceId());
		}

		@Test
		@DisplayName("hangs geometry off the bone that owns it")
		void readsBoneElements() {
			RawGroupBone bone = read(BinaryFixtures.cubeModel()).groupBones().getFirst();

			assertEquals("body", bone.name());
			assertEquals(-1, bone.parentIndex());
			assertEquals(1, bone.elements().size());
			assertEquals(ELEMENT_KIND_CUBE, bone.elements().getFirst().kind());
			assertEquals(0, bone.elements().getFirst().index());
		}

		@Test
		@DisplayName("reads a mesh with a mix of triangles and quads")
		void readsMesh() {
			RawMesh mesh = read(BinaryFixtures.meshModel()).meshes().getFirst();

			assertEquals("plane", mesh.name());
			assertEquals(5, mesh.vertexCount());
			assertEquals(2, mesh.faces().size());
			assertEquals(4, mesh.faces().get(0).cornerCount());
			assertEquals(3, mesh.faces().get(1).cornerCount(), "n-gons other than quads must survive the parse");
			assertTrue(mesh.mirror());
			assertFalse(mesh.isSkinned());
			assertEquals(-1, mesh.armatureIndex());
			assertEquals(45f, mesh.rotation().y);
			assertEquals(4f, mesh.positions()[3], "the second vertex's x");
			assertEquals(0.5f, mesh.faces().get(1).uvs()[4], "the triangle's third corner u");
		}

		@Test
		@DisplayName("reads an armature and the group bone it hangs off")
		void readsArmature() {
			RawBinaryModel model = read(BinaryFixtures.riggedModel());

			assertEquals(1, model.armatures().size());

			RawArmature armature = model.armatures().getFirst();

			assertEquals("rig", armature.name());
			assertEquals(0, armature.parentGroupBone(), "the armature hangs off group bone 0");
			assertEquals(List.of("lower", "upper"), armature.bones().stream().map(bone -> bone.name()).toList());
			assertEquals(-1, armature.bones().get(0).parentIndex());
			assertEquals(0, armature.bones().get(1).parentIndex());
			assertEquals(8f, armature.bones().get(1).head().y);
			assertEquals(8f, armature.bones().get(1).length());
		}

		@Test
		@DisplayName("reads skin influences exactly as authored")
		void readsSkinVerbatim() {
			RawMesh mesh = read(BinaryFixtures.riggedModel()).meshes().getFirst();

			assertTrue(mesh.isSkinned());
			assertEquals(0, mesh.armatureIndex());
			assertEquals(12, mesh.skin().size(), "one entry per vertex");

			assertEquals(List.of(new RawInfluence(0, 1f)), mesh.skin().get(0));
			assertEquals(List.of(new RawInfluence(1, 1f)), mesh.skin().get(11));

			/*
			 * The middle ring is the whole point of the fixture. Read verbatim - not re-sorted,
			 * not renormalised - because doing any of that here would mask an exporter that had
			 * stopped doing it.
			 */
			assertEquals(List.of(new RawInfluence(0, 0.5f), new RawInfluence(1, 0.5f)), mesh.skin().get(4));
		}
	}

	@Nested
	@DisplayName("a malformed model is refused")
	class Malformed {
		@Test
		@DisplayName("with no bytes at all")
		void empty() {
			assertRefused(new byte[0], "is empty");
		}

		@Test
		@DisplayName("when it does not open with the magic")
		void wrongMagic() {
			byte[] bytes = BinaryFixtures.cubeModel();

			bytes[0] = 'X';

			assertRefused(bytes, MODEL_MAGIC, "not a Strut binary model");
		}

		@Test
		@DisplayName("when a Strut animation is loaded as a model")
		void wrongKindOfFile() {
			assertRefused(BinaryFixtures.animation(), MODEL_MAGIC, ANIMATION_MAGIC);
		}

		@Test
		@DisplayName("when it is newer than this build, naming what to do about it")
		void futureVersion() {
			assertRefused(BinaryFixtures.withU16At(BinaryFixtures.cubeModel(), ModelHeader.VERSION, 9999),
					"9999", "newer than this build", "Update Strut");
		}

		@Test
		@DisplayName("when it is truncated mid-record")
		void truncated() {
			byte[] whole = BinaryFixtures.riggedModel();

			assertRefused(BinaryFixtures.truncated(whole, whole.length - 8), "truncated");
		}

		@Test
		@DisplayName("when the header claims a string table larger than the file")
		void impossibleStringTable() {
			assertRefused(BinaryFixtures.withU16At(BinaryFixtures.cubeModel(),
					ModelHeader.STRING_TABLE_SIZE, 60000), "string table", "truncated");
		}

		@Test
		@DisplayName("when a count could not fit in the bytes that remain")
		void impossibleCount() {
			assertRefused(BinaryFixtures.withU16At(BinaryFixtures.cubeModel(), ModelHeader.MESH_COUNT, 30000),
					"30000", "meshes");
		}

		@Test
		@DisplayName("when a record names a string past the end of the table")
		void stringIndexOutOfRange() {
			byte[] bytes = BinaryFixtures.cubeModel();
			int textureRecord = MODEL_HEADER_BYTES + stringTableLength(bytes);

			assertRefused(BinaryFixtures.withU16At(bytes, textureRecord, 500),
					"string index 500", "string table holds");
		}

		@Test
		@DisplayName("when a bone names geometry that does not exist")
		void danglingElement() {
			byte[] bytes = BinaryFixtures.cubeModel();
			// bone record: name(2) parent(2) pivot(12) rotation(12) flags(1) elementCount(2) kind(1) index(2)
			int boneRecord = MODEL_HEADER_BYTES + stringTableLength(bytes) + 10;
			int elementIndex = boneRecord + 2 + 2 + 12 + 12 + 1 + 2 + 1;

			assertRefused(BinaryFixtures.withU16At(bytes, elementIndex, 7), "cube 7 of 1");
		}

		@Test
		@DisplayName("when the authoring grid is not a positive number")
		void unusableUnitScale() {
			byte[] bytes = BinaryFixtures.cubeModel();

			for (int i = 0; i < 4; i++) {
				bytes[ModelHeader.UNITS_PER_BLOCK + i] = 0;
			}

			assertRefused(bytes, "units per block");
		}

		/**
		 * The one texture record follows the string table, so its offset is the table's own length
		 */
		private static int stringTableLength(byte[] bytes) {
			return (bytes[ModelHeader.STRING_TABLE_SIZE] & 0xFF)
					| ((bytes[ModelHeader.STRING_TABLE_SIZE + 1] & 0xFF) << 8);
		}
	}

	@Test
	@DisplayName("a file written when the editor's visibility was saved (test_dummy has 152 such bones) loads with every bone, mesh and cube shown")
	void theOldHiddenBitIsIgnored() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file));

		BakedStrutModel model = BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");

		for (GroupBoneDefinition bone : model.flatBones())
			assertNotEquals(Boolean.TRUE, bone.shouldNeverRender(), bone.getName());
	}
}
