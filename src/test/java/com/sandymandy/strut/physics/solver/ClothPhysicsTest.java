package com.sandymandy.strut.physics.solver;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutVertex;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import java.util.Set;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.DebugSkirt;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Physics module: cloth. A curtain of mesh vertices sewn to a bone along its top edge. The gates: the mesh
 * is welded into particles and edges, what is sewn on never moves, a kicked cloth swings and settles with its edges still their length, cloth is pushed out of
 * the body's shapes, and a mesh that is too dense or not cloth-ready is left as modelled with the reason said
 */
class ClothPhysicsTest {
	private static final int COLS = 6;
	private static final int ROWS = 6;

	/** A grid of quads hanging in the plane z = 0.3, 0.8 blocks wide, from y = 1.0 (the top row) down to y = 0.2 */
	private static StrutMesh curtain(int cols, int rows) {
		StrutPoly[] polys = new StrutPoly[cols * rows];
		int at = 0;

		for (int j = 0; j < rows; j++) {
			for (int i = 0; i < cols; i++)
				polys[at++] = new StrutPoly(new StrutVertex[] {vertex(i, j, cols, rows), vertex(i + 1, j, cols, rows), vertex(i + 1, j + 1, cols, rows), vertex(i, j + 1, cols, rows)},
						new Vector3f(0, 0, 1));
		}

		return new StrutMesh(polys, Vec3d.ZERO, Vec3d.ZERO, false);
	}

	private static StrutVertex vertex(int i, int j, int cols, int rows) {
		return new StrutVertex(new Vector3f(-0.4f + 0.8f * i / cols, 1.0f - 0.8f * j / rows, 0.3f), 0, 0, new Vector3f(0, 0, 1));
	}

	private static BakedStrutModel model(StrutMesh mesh) {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition skirt = new GroupBoneDefinition(root, "skirt", false, null, null, null);

		root.bakePivot(0, 0, 0);
		skirt.bakePivot(0, 16, 0);
		root.getChildBones().add(skirt);
		skirt.getMeshes().add(mesh);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private record Run(BakedStrutModel model, PhysicsInstance instance, Pose animated, Pose pose, StrutMesh mesh) {}

	private static Run start(String json) {
		return start(json, curtain(COLS, ROWS));
	}

	private static Run start(String json, StrutMesh mesh) {
		BakedStrutModel model = model(mesh);
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());

		pose.copyFrom(animated);

		return new Run(model, new PhysicsInstance(model, PhysicsConfig.parse(json, "test")), animated, pose, mesh);
	}

	private static String clothJson(String extra) {
		return "{\"cloth\": {\"skirt\": {\"bone\": \"skirt\", \"preset\": \"cloth\"" + extra + "}}}";
	}

	/** Frames at 60 Hz with the entity at the origin, yaw 180 (which makes the model's space the world's) */
	private static void still(Run run, PhysicsControls controls, double from, double to) {
		for (double t = from; t <= to + 1e-9; t += 1.0 / 60.0) {
			run.pose().copyFrom(run.animated());
			run.instance().update(run.model(), run.animated(), run.pose(), new PhysicsInstance.Sample(0, 64, 0, 180f, t), controls);
		}
	}

	/** The corner offsets of the vertices in row {@code row} of the grid (the corners of the polys' top or bottom edge) */
	private static float[] rowOffsets(Run run, int row) {
		ClothInstance cloth = run.instance().cloth("skirt");
		float[] out = new float[(COLS + 1) * 3];
		float[] all = cloth.cornerOffsets();

		for (int i = 0; i <= COLS; i++) {
			// vertex (i, row) is corner 0 of the poly (i, row) except on the right edge, where it is corner 1 of the poly before it; the last row is corner 3 or 2
			int poly;
			int corner;

			if (row < ROWS) {
				poly = row * COLS + Math.min(i, COLS - 1);
				corner = i < COLS ? 0 : 1;
			}
			else {
				poly = (ROWS - 1) * COLS + Math.min(i, COLS - 1);
				corner = i < COLS ? 3 : 2;
			}

			int c = (poly * 4 + corner) * 3;

			out[i * 3] = all[c];
			out[i * 3 + 1] = all[c + 1];
			out[i * 3 + 2] = all[c + 2];
		}

		return out;
	}

	private static double magnitude(float[] v, int from, int to) {
		double worst = 0;

		for (int i = from; i < to; i += 3)
			worst = Math.max(worst, Math.sqrt(v[i] * v[i] + v[i + 1] * v[i + 1] + v[i + 2] * v[i + 2]));

		return worst;
	}

	@Test
	void theMeshIsWeldedIntoParticlesAndEdges() {
		Run run = start(clothJson(""));
		ClothInstance cloth = run.instance().cloth("skirt");

		// a 6 x 6 grid of quads has 7 x 7 shared vertices, not 6 * 6 * 4 corners
		assertEquals(49, cloth.particles());
		// 7 * 6 across, 6 * 7 down, and a diagonal in each of the 36 quads
		assertEquals(42 + 42 + 36, cloth.topology.edgeA.length);
		assertTrue(cloth.topology.bendA.length > 0, "folds across shared edges resist bending");
		assertEquals(0, run.instance().problems().size());
	}

	@Test
	void theSewnTopRowNeverMovesAndTheRestSettlesBackAfterAKick() {
		Run run = start(clothJson(", \"pinHeight\": 0.1, \"damping\": 1.2"));
		PhysicsControls controls = new PhysicsControls();

		still(run, controls, 0, 0.5);
		run.instance().cloth("skirt").impulse(3f, 0f, 2f);

		double swung = 0;
		double worstEdge = 0;
		double topMoved = 0;

		for (int i = 0; i < 120; i++) {
			still(run, controls, 0.5 + i / 60.0, 0.5 + i / 60.0);
			swung = Math.max(swung, magnitude(rowOffsets(run, ROWS), 0, (COLS + 1) * 3));
			worstEdge = Math.max(worstEdge, run.instance().cloth("skirt").worstEdgeError());
			topMoved = Math.max(topMoved, magnitude(rowOffsets(run, 0), 0, (COLS + 1) * 3));
		}

		assertTrue(swung > 0.05, "the kicked hem swung " + swung);
		assertEquals(0.0, topMoved, 1e-4, "what is sewn on does not move");
		assertTrue(worstEdge < 0.15, "edges stayed near their length, worst " + worstEdge);

		still(run, controls, 2.5, 12.0);

		assertTrue(magnitude(rowOffsets(run, ROWS), 0, (COLS + 1) * 3) < 0.03, "it came to rest hanging as modelled, off by " + magnitude(rowOffsets(run, ROWS), 0, (COLS + 1) * 3));
		assertTrue(run.instance().cloth("skirt").worstEdgeError() < 0.05);
	}

	@Test
	void aWalkingEntityDragsTheClothBehindIt() {
		Run run = start(clothJson(", \"pinHeight\": 0.1, \"inertia\": 1.0"));
		PhysicsControls controls = new PhysicsControls();
		double lag = 0;

		for (int i = 0; i <= 180; i++) {
			double t = i / 60.0;

			run.pose().copyFrom(run.animated());
			// yaw 180 maps the model's +z to the world's +z, so walking along +z the hem trails toward -z
			run.instance().update(run.model(), run.animated(), run.pose(), new PhysicsInstance.Sample(0, 64, 3.0 * t, 180f, t), controls);

			if (i > 60)
				lag = Math.min(lag, rowOffsets(run, ROWS)[2 * 3 + 2]);
		}

		assertTrue(lag < -0.2, "the hem trails behind a walker, z offset " + lag);
	}

	@Test
	void clothIsPushedOutOfABodyShape() {
		// a box across the middle of the curtain, in front of and behind the plane z = 0.3: the hanging cloth has to go round its faces
		String json = "{\"cloth\": {\"skirt\": {\"bone\": \"skirt\", \"preset\": \"cloth\", \"pinHeight\": 0.1}},"
				+ " \"colliders\": [{\"bone\": \"root\", \"shape\": \"box\", \"center\": [0, 9.6, 4.8], \"size\": [16, 6.4, 4]}]}";
		Run run = start(json);
		PhysicsControls controls = new PhysicsControls();

		still(run, controls, 0, 4.0);

		ClothInstance cloth = run.instance().cloth("skirt");
		float radius = 0.02f;
		int inside = 0;

		for (int k = 0; k < cloth.particles(); k++) {
			double x = cloth.state.x[k][0];
			double y = cloth.state.x[k][1] - 64;
			double z = cloth.state.x[k][2];
			boolean within = Math.abs(x) < 0.5 + radius - 0.01 && Math.abs(y - 0.6) < 0.2 + radius - 0.01 && Math.abs(z - 0.3) < 0.125 + radius - 0.01;

			if (within)
				inside++;
		}

		assertEquals(0, inside, "no particle ends a step inside the body's shape");
		assertTrue(run.instance().cloth("skirt").worstEdgeError() < 0.6, "the cloth is stretched over the shape, not torn");
	}

	@Test
	void aMeshTooDenseForTheBudgetIsLeftAsModelled() {
		Run run = start(clothJson(", \"maxParticles\": 20"));

		assertEquals(0, run.instance().clothCount());
		assertEquals(1, run.instance().problems().size());
		assertTrue(run.instance().problems().get(0).contains("maxParticles"), run.instance().problems().get(0));
	}

	@Test
	void aBoneWithoutAMeshOrAMissingBoneSaysSo() {
		Run run = start("{\"cloth\": {\"a\": {\"bone\": \"root\"}, \"b\": {\"bone\": \"nope\"}}}");

		assertEquals(0, run.instance().clothCount());
		assertEquals(2, run.instance().problems().size());
	}

	@Test
	void weightZeroGivesNoOffsetsAndTheCostIsLow() {
		Run run = start(clothJson(", \"weight\": 0"));
		PhysicsControls controls = new PhysicsControls();

		still(run, controls, 0, 1.0);

		int[] published = {0};

		run.instance().clothOutputs((mesh, offsets) -> published[0]++);
		assertEquals(0, published[0], "a cloth with no weight is drawn exactly as modelled, so it does not turn the GPU path off");
	}

	@Test
	void theClothPublishesItsOffsetsForTheRendererAndTheCostStaysSmall() {
		StrutMesh dense = curtain(20, 20);
		Run run = start(clothJson(", \"maxParticles\": 600, \"iterations\": 8"), dense);
		PhysicsControls controls = new PhysicsControls();

		still(run, controls, 0, 1.0);

		StrutMesh[] got = {null};
		float[][] offsets = {null};

		run.instance().clothOutputs((mesh, o) -> {
			got[0] = mesh;
			offsets[0] = o;
		});
		assertSame(dense, got[0]);
		assertEquals(dense.polys().length * 12, offsets[0].length);
		assertEquals(441, run.instance().cloth("skirt").particles());

		long started = System.nanoTime();

		still(run, controls, 1.0, 3.0);

		double perFrameMs = (System.nanoTime() - started) / 1e6 / 121;

		// the target is 0.5 ms per instance at 400 particles on a warm JVM; a loose bound here only catches an accidental blow-up
		assertTrue(perFrameMs < 5.0, "a 441 particle cloth cost " + perFrameMs + " ms a frame");
	}


	@Test
	void theConfigReadsClothKeysAndReportsWhatItDoesNotKnow() {
		PhysicsConfig config = PhysicsConfig.parse("{\"cloth\": {\"cape\": {\"bone\": \"cape\", \"preset\": \"cloth-stiff\", \"pinHeight\": 0.2, \"mesh\": 1, \"wobble\": 3,"
				+ " \"collidesWith\": [\"world\"], \"collideBones\": [\"torso\"]}, \"bad\": {}}}", "test");
		ClothParams cape = config.cloth().get("cape");

		assertNotNull(cape);
		assertEquals("cape", cape.bone());
		assertEquals(1, cape.mesh());
		assertEquals(1.0f, cape.stiffness(), 1e-6f);
		assertEquals(0.6f, cape.bend(), 1e-6f);
		assertEquals(0.2f, cape.pinHeight(), 1e-6f);
		assertEquals(Set.of("world"), cape.collidesWith());
		assertTrue(config.filter("cape").allows("torso") && !config.filter("cape").allows("head"));
		assertFalse(config.cloth().containsKey("bad"));
		assertFalse(config.isEmpty());
		assertTrue(config.warnings().stream().anyMatch(w -> w.contains("wobble")), config.warnings().toString());
		assertTrue(config.warnings().stream().anyMatch(w -> w.contains("bad")), config.warnings().toString());
	}
}
