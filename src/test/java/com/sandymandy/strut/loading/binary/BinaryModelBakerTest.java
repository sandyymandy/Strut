package com.sandymandy.strut.loading.binary;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutQuad;
import com.sandymandy.strut.cache.object.StrutVertex;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCube;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawCubeFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawElement;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawFace;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawGroupBone;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawMesh;
import com.sandymandy.strut.loading.binary.raw.RawBinaryModel.RawTexture;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.sandymandy.strut.loading.binary.StrutBinaryFormats.*;
import static org.junit.jupiter.api.Assertions.*;

class BinaryModelBakerTest {
	private static BakedStrutModel bake(byte[] bytes) {
		return BinaryModelBaker.bake(BinaryModelReader.read(bytes, "test.strutmodel"), "test.strutmodel");
	}

	/**
	 * A single quad lying flat in the XZ plane, wound so that it faces downwards
	 */
	private static byte[] flatQuadModel(float unitsPerBlock) {
		BinaryFixtures.Strings strings = new BinaryFixtures.Strings();
		strings.intern("strut:test/quad");
		int textureName = strings.intern("texture");
		int boneName = strings.intern("root");
		int meshName = strings.intern("quad");

		BinaryOutput textures = new BinaryOutput().u16(textureName).u16(16).u16(16).u16(16).u16(16);
		BinaryOutput bones = new BinaryOutput()
				.u16(boneName).i16(-1).vec3(0, 0, 0).vec3(0, 0, 0).u8(0)
				.u16(1).u8(ELEMENT_KIND_MESH).u16(0);

		BinaryOutput meshes = new BinaryOutput()
				.u16(meshName).u16(0)
				.vec3(0, 0, 0).vec3(0, 0, 0)
				.u8(0).i16(-1)
				.u32(4).u32(1)
				.vec3(0, 0, 0).vec3(4, 0, 0).vec3(4, 0, 4).vec3(0, 0, 4)
				.u8(4)
				.u32(0).f32(0).f32(0)
				.u32(1).f32(16).f32(0)
				.u32(2).f32(16).f32(16)
				.u32(3).f32(0).f32(16);

		byte[] bytes = BinaryFixtures.modelFile(strings, 0, 1, 1, 1, 0, 0, textures, bones, meshes,
				new BinaryOutput(), new BinaryOutput());

		// Overwrite the header's units-per-block, which the fixture builder always writes as 16
		int bits = Float.floatToIntBits(unitsPerBlock);

		for (int i = 0; i < 4; i++) {
			bytes[BinaryFixtures.ModelHeader.UNITS_PER_BLOCK + i] = (byte)((bits >> (i * 8)) & 0xFF);
		}

		return bytes;
	}

	private static StrutMesh onlyMesh(BakedStrutModel model) {
		return model.topLevelBones().getFirst().getMeshes().getFirst();
	}

	@Test
	@DisplayName("builds the bone hierarchy and hangs meshes off the right bones")
	void buildsHierarchy() {
		BakedStrutModel model = bake(BinaryFixtures.meshModel());

		assertEquals(1, model.topLevelBones().size());

		GroupBoneDefinition bone = model.topLevelBones().getFirst();

		assertEquals("root", bone.getName());
		assertEquals(1, bone.getMeshes().size());
		assertTrue(bone.getCubes().isEmpty());
	}

	@Test
	@DisplayName("emits a triangle as a degenerate quad, repeating its last corner")
	void triangleBecomesDegenerateQuad() {
		StrutMesh mesh = onlyMesh(bake(BinaryFixtures.meshModel()));

		assertEquals(2, mesh.polys().length, "one quad and one triangle in, two polys out");

		for (StrutPoly poly : mesh.polys()) {
			assertEquals(4, poly.vertices().length, "Minecraft's entity buffers are QUADS-mode");
		}

		StrutPoly triangle = mesh.polys()[1];

		assertEquals(triangle.vertices()[2].position(), triangle.vertices()[3].position(),
				"the degenerate corner is the repeat of the last real one");
	}

	/**
	 * Minecraft renders models mirrored along X, so the bake negates it - which flips the
	 * handedness of every face. Corners are reversed to compensate
	 * <p>
	 * This quad's own winding faces it downwards. If the reversal were dropped the bake would
	 * produce {@code (0, 1, 0)} here: the model would render inside-out and lit from the wrong
	 * side, which looks like a texture or lighting problem rather than a winding one
	 */
	@Test
	@DisplayName("keeps a face pointing the way it was wound, despite the mirrored X axis")
	void mirroringDoesNotFlipFacing() {
		StrutPoly poly = onlyMesh(bake(flatQuadModel(16))).polys()[0];

		assertEquals(0f, poly.normal().x, 1.0e-6f);
		assertEquals(-1f, poly.normal().y, 1.0e-6f, "this quad is wound to face downwards");
		assertEquals(0f, poly.normal().z, 1.0e-6f);
	}

	@Test
	@DisplayName("normalises UVs against the texture's UV grid")
	void normalisesUvs() {
		StrutVertex[] vertices = onlyMesh(bake(flatQuadModel(16))).polys()[0].vertices();
		boolean sawFullU = false;

		for (StrutVertex vertex : vertices) {
			assertTrue(vertex.texU() >= 0 && vertex.texU() <= 1, "UVs are normalised at bake");
			assertTrue(vertex.texV() >= 0 && vertex.texV() <= 1);

			sawFullU |= vertex.texU() == 1f;
		}

		assertTrue(sawFullU, "a UV of 16 on a 16-wide grid is the far edge of the texture");
	}

	/**
	 * A model authored on a 32-unit grid has to end up half the size of the same model authored
	 * on Minecraft's 16, or it renders at double scale with nothing saying why
	 */
	@Test
	@DisplayName("normalises the authoring grid to Minecraft's 16 units per block")
	void normalisesUnitScale() {
		Vector3f onSixteen = onlyMesh(bake(flatQuadModel(16))).polys()[0].vertices()[1].position();
		Vector3f onThirtyTwo = onlyMesh(bake(flatQuadModel(32))).polys()[0].vertices()[1].position();

		assertEquals(onSixteen.x / 2, onThirtyTwo.x, 1.0e-6f);
		assertEquals(onSixteen.z / 2, onThirtyTwo.z, 1.0e-6f);
	}

	@Test
	@DisplayName("bakes a whole real model without producing a degenerate normal")
	void bakesRealModel() throws IOException {
		BakedStrutModel model;

		try (InputStream in = BinaryModelBakerTest.class.getResourceAsStream("/fixtures/base_character_male.strutmodel")) {
			assertNotNull(in);

			model = BinaryModelBaker.bake(BinaryModelReader.read(in, "male"), "male");
		}

		int polys = countPolys(model.topLevelBones().getFirst());

		assertEquals(702, polys, "one poly per triangle in the source model");
	}

	/**
	 * The end-to-end check on scale and axes, without a game to look at
	 * <p>
	 * The source character measures 33.26 units tall and 13.57 wide on a 16-unit grid, so a
	 * correct bake puts it just over two blocks tall and under a block wide, standing on y=0.
	 * A wrong units-per-block, a missing divide by 16, or a swapped axis all move these numbers
	 * somewhere obvious, which is most of what can go wrong between a parsed file and a model
	 * that looks right in the world
	 */
	@Test
	@DisplayName("bakes a real model to the right size and orientation in block space")
	void bakesRealModelToBlockSpace() throws IOException {
		BakedStrutModel model;

		try (InputStream in = BinaryModelBakerTest.class.getResourceAsStream("/fixtures/base_character_male.strutmodel")) {
			assertNotNull(in);

			model = BinaryModelBaker.bake(BinaryModelReader.read(in, "male"), "male");
		}

		Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
		Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

		extend(model.topLevelBones().getFirst(), min, max);

		assertEquals(0f, min.y, 0.01f, "the character stands on the ground plane");
		assertEquals(33.26f / 16f, max.y, 0.01f, "33.26 model units is a little over two blocks");
		assertEquals(13.57f / 16f, max.x - min.x, 0.01f, "and under a block wide");
		assertEquals(7.35f / 16f, max.z - min.z, 0.01f);
	}

	/**
	 * A mirrored pair of bones must stay mirrored through the bake
	 * <p>
	 * The character's arms are a symmetric rig: {@code armL} and {@code armR} sit at equal and
	 * opposite X and carry equal and opposite rotations about Z. Mirroring the geometry without
	 * mirroring the rotation leaves both arms swinging the same way - the rig looks broken
	 * rather than mis-signed, which is what makes it worth a test rather than an eyeball
	 */
	@Test
	@DisplayName("keeps a mirrored pair of bones mirrored")
	void mirrorsRotationsWithGeometry() throws IOException {
		BakedStrutModel model;

		try (InputStream in = BinaryModelBakerTest.class.getResourceAsStream("/fixtures/base_character_male.strutmodel")) {
			assertNotNull(in);

			model = BinaryModelBaker.bake(BinaryModelReader.read(in, "male"), "male");
		}

		GroupBoneDefinition left = model.getBone("armL").orElseThrow(() -> new AssertionError("no bone named \"armL\""));
		GroupBoneDefinition right = model.getBone("armR").orElseThrow(() -> new AssertionError("no bone named \"armR\""));

		assertEquals(-left.getPivotX(), right.getPivotX(), 0.02f, "the arms sit at equal and opposite X");
		assertEquals(left.getPivotY(), right.getPivotY(), 1.0e-4f);

		assertEquals(-left.getInitialRotZ(), right.getInitialRotZ(), 1.0e-5f,
				"and must rotate in opposite directions, or they both swing the same way");
		assertEquals((float)Math.toRadians(-12.5), left.getInitialRotZ(), 1.0e-5f,
				"the authored -12.5 degrees, unmirrored - the bake passes a rotation through as written");
	}

	/**
	 * Nothing before this exercised {@code BinaryFixtures.cubeModel()} at all - {@link
	 * BinaryModelBaker#bake} silently dropped every {@code ELEMENT_KIND_CUBE} element, so no
	 * cube from any binary model has ever reached the renderer, regardless of what the exporter
	 * produced. Live-verified in a real game session (an exported model's cubes never appeared),
	 * traced back to this exact gap
	 */
	@Test
	@DisplayName("bakes a cube instead of silently dropping it")
	void bakesCubeFaces() {
		BakedStrutModel model = bake(BinaryFixtures.cubeModel());
		GroupBoneDefinition bone = model.topLevelBones().getFirst();

		assertTrue(bone.getMeshes().isEmpty());
		assertEquals(1, bone.getCubes().size());

		StrutCube cube = bone.getCubes().getFirst();

		assertEquals(1, cube.quads().length, "the fixture declares exactly one face");
		assertTrue(cube.mirror(), "the fixture sets CUBE_FLAG_MIRROR");
	}

	@Test
	@DisplayName("normalises a cube face's explicit UV rectangle against the texture grid")
	void bakesCubeUvs() {
		StrutCube cube = bake(BinaryFixtures.cubeModel()).topLevelBones().getFirst().getCubes().getFirst();

		for (StrutVertex vertex : cube.quads()[0].vertices()) {
			assertTrue(vertex.texU() >= 0 && vertex.texU() <= 1, "UVs are normalised at bake");
			assertTrue(vertex.texV() >= 0 && vertex.texV() <= 1);
		}
	}

	/**
	 * The fixture's one declared face is Bedrock face id 2 ("north"), which the reused
	 * {@code BakedModelFactory.VertexSet} resolves to the box's min-Z side (matching Minecraft's
	 * real -Z-facing {@code Direction.NORTH}) - every corner of a single face therefore shares one
	 * Z, while X and Y each span the box's own full, inflated range. The box itself runs
	 * (-4, 0, -4) to (4, 8, 4) on the default 16-unit grid, inflated 0.25 units
	 */
	@Test
	@DisplayName("bakes a cube to the right block-space position and size, inflated")
	void bakesCubeToBlockSpace() {
		StrutCube cube = bake(BinaryFixtures.cubeModel()).topLevelBones().getFirst().getCubes().getFirst();
		Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
		Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);

		for (StrutVertex vertex : cube.quads()[0].vertices()) {
			min.min(vertex.position());
			max.max(vertex.position());
		}

		float inflate = 0.25f / 16f;

		assertEquals(min.z, max.z, 1.0e-6f, "a single face's corners all share one Z");
		assertEquals(-(4f / 16f) - inflate, min.z, 1.0e-4f);
		assertEquals(-(4f / 16f) - inflate, min.x, 1.0e-4f);
		assertEquals((4f / 16f) + inflate, max.x, 1.0e-4f);
		assertEquals(-inflate, min.y, 1.0e-4f);
		assertEquals(8f / 16f + inflate, max.y, 1.0e-4f);
	}

	/**
	 * A cube whose faces carry whatever UV rectangle {@code uv} gives each one, in Bedrock face-id
	 * order (west, east, north, south, up, down) - lets a test hand individual faces a real
	 * rectangle or a zero-size ("never mapped") one without needing a specially-shaped cube
	 */
	private static RawBinaryModel cubeModelWithUvs(Vector3f from, Vector3f to, float[][] uv) {
		List<RawCubeFace> faces = new ArrayList<>();

		for (int faceId = 0; faceId < uv.length; faceId++) {
			faces.add(new RawCubeFace(faceId, uv[faceId][0], uv[faceId][1], uv[faceId][2], uv[faceId][3], 0));
		}

		RawCube cube = new RawCube("card", 0, new Vector3f(0, 2, 2), new Vector3f(0, 0, 0), from, to, 0,
				false, false, faces);
		RawGroupBone bone = new RawGroupBone("root", -1, new Vector3f(0, 0, 0), new Vector3f(0, 0, 0),
				List.of(new RawElement(ELEMENT_KIND_CUBE, 0)));

		return new RawBinaryModel(100, "test:card", 16, new Vector3f(-1, 0, -1), new Vector3f(1, 4, 1),
				List.of("test:card", "texture", "root", "card"),
				List.of(new RawTexture("texture", 16, 16, 16, 16)),
				List.of(bone), List.of(), List.of(cube), List.of());
	}

	private static final float[] MAPPED = {0, 0, 4, 4};
	private static final float[] UNMAPPED = {0, 0, 0, 0};
	private static final float[] ZERO_WIDTH = {2, 0, 2, 4};
	private static final float[] ZERO_HEIGHT = {0, 2, 4, 2};

	/**
	 * A face whose UV rectangle has zero size is drawn in one texel's colour by Blockbench and GeckoLib, and models use such faces to fill the
	 * gaps between cubes. Dropping them opened those gaps as slits (a consumer mod, 2026-09-30), so every face in the file bakes
	 */
	@Test
	@DisplayName("keeps a face whose UV rectangle has zero size, as Blockbench and GeckoLib draw it")
	void keepsFacesWithZeroSizeUv() {
		RawBinaryModel model = cubeModelWithUvs(new Vector3f(-4, 0, -4), new Vector3f(4, 8, 4),
				new float[][] {MAPPED, UNMAPPED, MAPPED, UNMAPPED, UNMAPPED, UNMAPPED});

		StrutCube cube = BinaryModelBaker.bake(model, "test").topLevelBones().getFirst().getCubes().getFirst();

		assertEquals(6, cube.quads().length, "every face in the file bakes, a zero-size UV rectangle included");

		StrutQuad unmapped = cube.quads()[1];

		for (StrutVertex corner : unmapped.vertices()) {
			assertEquals(0f, corner.texU(), "a zero-size rectangle samples one point");
			assertEquals(0f, corner.texV());
		}
	}

	/**
	 * Bedrock box UV does not necessarily zero both UV dimensions together for a flattened box: a
	 * face's rectangle size comes from two of the box's three dimensions, and which two differs
	 * per face, so a flattened box can zero only the width of one face and only the height of
	 * another. Such a face samples one column or row of texels, and bakes like any other
	 */
	@Test
	@DisplayName("keeps a face whose UV rectangle is zero in only one dimension")
	void keepsFacesWithOneZeroUvDimension() {
		RawBinaryModel model = cubeModelWithUvs(new Vector3f(-4, 0, -4), new Vector3f(4, 8, 4),
				new float[][] {MAPPED, MAPPED, ZERO_WIDTH, ZERO_HEIGHT, ZERO_WIDTH, ZERO_HEIGHT});

		StrutCube cube = BinaryModelBaker.bake(model, "test").topLevelBones().getFirst().getCubes().getFirst();

		assertEquals(6, cube.quads().length, "a rectangle with zero width or zero height still draws");
	}

	/**
	 * A flat cube's faces stay exactly on its plane, as GeckoLib bakes them. Lifting them apart against z-fighting opened hairline cracks between
	 * the many flat cubes a model can be built from (a consumer mod, 2026-09-30)
	 */
	@Test
	@DisplayName("a flat cube's opposing faces stay on its plane, not lifted apart")
	void flatCubeFacesStayOnTheirPlane() {
		RawBinaryModel model = cubeModelWithUvs(new Vector3f(0, 0, 0), new Vector3f(0, 4, 4),
				new float[][] {MAPPED, MAPPED, MAPPED, MAPPED, MAPPED, MAPPED});

		StrutCube cube = BinaryModelBaker.bake(model, "test").topLevelBones().getFirst().getCubes().getFirst();

		assertEquals(6, cube.quads().length);

		for (StrutVertex corner : cube.quads()[0].vertices())
			assertEquals(0f, corner.position().x, "west stays at x = 0");

		for (StrutVertex corner : cube.quads()[1].vertices())
			assertEquals(0f, corner.position().x, "east stays at x = 0");
	}

	/**
	 * A cube with real thickness has nothing coplanar to fight with, so it must come through
	 * completely untouched - the lift is strictly for the flattened case
	 */
	@Test
	@DisplayName("a cube with real thickness is never lifted")
	void solidCubeIsNeverLifted() {
		RawBinaryModel model = cubeModelWithUvs(new Vector3f(-2, 0, 0), new Vector3f(2, 4, 4),
				new float[][] {MAPPED, MAPPED, MAPPED, MAPPED, MAPPED, MAPPED});

		StrutCube cube = BinaryModelBaker.bake(model, "test").topLevelBones().getFirst().getCubes().getFirst();

		assertEquals(-2f / 16f, cube.quads()[0].vertices()[0].position().x, 1.0e-6f, "west stays exactly where authored");
		assertEquals(2f / 16f, cube.quads()[1].vertices()[0].position().x, 1.0e-6f, "east stays exactly where authored");
	}

	/**
	 * Two quads meeting at a right-angle hinge along a shared edge - a floor quad in the XZ plane
	 * and a wall quad in the YZ plane, sharing their {@code v0}-{@code v3} edge. Deliberately not
	 * coplanar, so a smoothed and an unsmoothed bake produce visibly different normals at the
	 * shared edge and the test can tell them apart
	 */
	private static RawBinaryModel hingeModel(boolean smooth) {
		float[] positions = {
				0, 0, 0, // v0 - shared
				2, 0, 0, // v1 - floor only
				2, 0, 2, // v2 - floor only
				0, 0, 2, // v3 - shared
				0, 2, 2, // v4 - wall only
				0, 2, 0  // v5 - wall only
		};
		float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
		RawFace floor = new RawFace(new int[] {0, 1, 2, 3}, uvs);
		RawFace wall = new RawFace(new int[] {0, 3, 4, 5}, uvs);
		RawMesh mesh = new RawMesh("hinge", 0, new Vector3f(0, 0, 0), new Vector3f(0, 0, 0),
				false, -1, positions, List.of(floor, wall), List.of(), smooth);
		RawGroupBone bone = new RawGroupBone("root", -1, new Vector3f(0, 0, 0), new Vector3f(0, 0, 0),
				List.of(new RawElement(ELEMENT_KIND_MESH, 0)));

		return new RawBinaryModel(100, "test:hinge", 16, new Vector3f(0, 0, 0), new Vector3f(2, 2, 2),
				List.of("test:hinge", "texture", "root", "hinge"),
				List.of(new RawTexture("texture", 16, 16, 16, 16)),
				List.of(bone), List.of(mesh), List.of(), List.of());
	}

	@Test
	@DisplayName("gives every corner its own face's flat normal when a mesh is not smooth-shaded")
	void flatMeshGivesEveryCornerItsFaceNormal() {
		StrutMesh mesh = onlyMesh(BinaryModelBaker.bake(hingeModel(false), "test"));

		for (StrutPoly poly : mesh.polys()) {
			for (StrutVertex vertex : poly.vertices()) {
				assertEquals(poly.normal(), vertex.normal(), "an unsmoothed corner just repeats its face's normal");
			}
		}
	}

	/**
	 * The shared edge's two vertices sit on both the floor and the wall, so a smooth bake must
	 * blend those two faces' normals there - while the four corners unique to one face or the
	 * other keep that face's own normal untouched, having nothing to blend with
	 */
	@Test
	@DisplayName("blends a shared edge's normal between its two faces when a mesh is smooth-shaded")
	void smoothMeshBlendsNormalsAtASharedEdge() {
		StrutMesh mesh = BinaryModelBaker.bake(hingeModel(true), "test").topLevelBones().getFirst().getMeshes().getFirst();
		StrutPoly floor = mesh.polys()[0];
		StrutPoly wall = mesh.polys()[1];

		assertNotEquals(floor.normal(), floor.vertices()[0].normal(),
				"the shared corner must no longer be the floor's own flat normal");

		Vector3f expected = new Vector3f(floor.normal()).add(wall.normal()).normalize();

		assertEquals(expected.x, floor.vertices()[0].normal().x, 1.0e-5f);
		assertEquals(expected.y, floor.vertices()[0].normal().y, 1.0e-5f);
		assertEquals(expected.z, floor.vertices()[0].normal().z, 1.0e-5f);
		assertEquals(floor.vertices()[0].normal(), floor.vertices()[3].normal(),
				"both corners at the shared edge see the same two faces, so they blend identically");
		assertEquals(floor.vertices()[0].normal(), wall.vertices()[0].normal());
		assertEquals(floor.vertices()[3].normal(), wall.vertices()[1].normal());

		assertEquals(floor.normal(), floor.vertices()[1].normal(), "unshared corners keep their own face's normal");
		assertEquals(floor.normal(), floor.vertices()[2].normal());
		assertEquals(wall.normal(), wall.vertices()[2].normal());
		assertEquals(wall.normal(), wall.vertices()[3].normal());
	}

	private static void extend(GroupBoneDefinition bone, Vector3f min, Vector3f max) {
		for (StrutMesh mesh : bone.getMeshes()) {
			for (StrutPoly poly : mesh.polys()) {
				for (StrutVertex vertex : poly.vertices()) {
					min.min(vertex.position());
					max.max(vertex.position());
				}
			}
		}

		for (GroupBoneDefinition child : bone.getChildBones()) {
			extend(child, min, max);
		}
	}

	/**
	 * A zero-length normal renders black, and is exactly what a naive single cross product
	 * produces on a degenerate quad - which, on a fully triangulated model, is every face
	 */
	private static int countPolys(GroupBoneDefinition bone) {
		int count = 0;

		for (StrutMesh mesh : bone.getMeshes()) {
			for (StrutPoly poly : mesh.polys()) {
				assertEquals(1f, poly.normal().length(), 1.0e-4f,
						"every face normal must be unit length, degenerate quads included");

				count++;
			}
		}

		for (GroupBoneDefinition child : bone.getChildBones()) {
			count += countPolys(child);
		}

		return count;
	}
}
