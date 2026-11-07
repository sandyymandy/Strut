package com.sandymandy.strut.physics.flesh;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.cache.object.StrutMesh;
import com.sandymandy.strut.cache.object.StrutPoly;
import com.sandymandy.strut.cache.object.StrutVertex;
import org.joml.Vector3f;
import net.minecraft.util.math.Vec3d;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.DebugSkirt;
import com.sandymandy.strut.physics.tools.PhysicsControls;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code flesh} section on the engine that runs it: a jelly ball on a bone swings when its entity moves, is dented by what presses into it and springs back,
 * is held outside a soft zone, and a file written for the old per-vertex flesh still loads, saying what no longer applies
 */
class FleshTest {
	private static StrutMesh ball() {
		return DebugSkirt.ball(0, 0.6, 0.3, 0.12);
	}

	private static BakedStrutModel model(StrutMesh... meshes) {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition skirt = new GroupBoneDefinition(root, "skirt", false, null, null, null);

		root.bakePivot(0, 0, 0);
		skirt.bakePivot(0, 16, 0);
		root.getChildBones().add(skirt);
		for (StrutMesh mesh : meshes)
			skirt.getMeshes().add(mesh);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null, "test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	private record Run(BakedStrutModel model, PhysicsInstance instance, Pose animated, Pose pose) {}

	private static String json(String extra) {
		return "{\"flesh\": {\"ball\": {\"bone\": \"skirt\", \"preset\": \"jelly\"" + extra + "}}}";
	}

	private static Run start(String json) {
		return start(json, ball());
	}

	private static Run start(String json, StrutMesh... meshes) {
		BakedStrutModel model = model(meshes);
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());

		pose.copyFrom(animated);

		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(json, "test"));

		instance.entityId(5);

		return new Run(model, instance, animated, pose);
	}

	/** One frame at 60 Hz, the entity at {@code x} facing yaw 180 (which makes the model's space the world's), with {@code shapes} pressing */
	private static void frame(Run run, double x, double t, List<Collider.Placed> shapes) {
		if (!shapes.isEmpty())
			Bodies.publishModel(99, new ArrayList<>(shapes));

		Bodies.nextFrame();
		run.pose().copyFrom(run.animated());
		run.instance().update(run.model(), run.animated(), run.pose(), new PhysicsInstance.Sample(x, 64, 0, 180f, t), new PhysicsControls());
	}

	private static double largest(Run run) {
		double[] most = {0};

		run.instance().clothDeforms((mesh, deform) -> {
			for (float o : deform.cornerOffsets())
				most[0] = Math.max(most[0], Math.abs(o));
		});

		return most[0];
	}

	/** The largest offset of any corner, along y, the way a downward dent shows (negative) */
	private static double lowest(Run run) {
		double[] low = {0};

		run.instance().clothDeforms((mesh, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int i = 1; i < o.length; i += 3)
				low[0] = Math.min(low[0], o[i]);
		});

		return low[0];
	}

	/** A shape pressing about 0.03 into the top of the ball: its top is at y = 0.72, less the 0.03 it has sagged under its own weight, and the shape's bottom is at 0.66 */
	private static Collider.Placed hand() {
		Collider.Placed shape = new Collider.Placed();

		shape.shape = Collider.Shape.SPHERE;
		shape.radius = 0.08;
		shape.p[0] = 0;
		shape.p[1] = 64 + 0.74;
		shape.p[2] = 0.3;
		shape.layer = "body";

		return shape;
	}

	@Test
	void aFleshEntryMakesAJellyBallThatSwingsWhenItsEntityMovesAndSettlesBack() {
		Run run = start(json(""));

		assertEquals(1, run.instance().fleshCount());
		assertEquals(114, run.instance().flesh("ball").vertices(), "7 rings of 16 and two poles");

		double swung = 0;
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60) {
			frame(run, 0, t, List.of());
		}

		double resting = largest(run);

		for (int i = 0; i < 90; i++, t += 1.0 / 60) {
			frame(run, 0.4 * Math.sin(t * 8), t, List.of());
			swung = Math.max(swung, largest(run));
		}

		for (int i = 0; i < 600; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		assertTrue(swung > resting + 0.01, "it wobbled " + swung + " against " + resting);
		assertEquals(resting, largest(run), 0.003, "and settled back to its shape");
	}

	@Test
	void somethingPressingIntoItDentsTheSkinAndItSpringsBackWhenLetGo() {
		Run run = start(json(""));
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double before = lowest(run);

		for (int i = 0; i < 90; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of(hand()));

		double pressed = lowest(run);

		assertTrue(pressed < before - 0.012, "the top is pushed in " + pressed + " from " + before);

		for (int i = 0; i < 300; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		assertEquals(before, lowest(run), 0.003, "and comes back");
	}

	@Test
	void aBlockOfTheWorldPressedAgainstItDentsTheSkinToo() {
		Run run = start(json(""));
		boolean[] placed = {false};

		// a block whose underside is 0.03 into the top of the ball (the same depth as the hand above)
		run.instance().world((x0, y0, z0, x1, y1, z1, sink) -> {
			if (placed[0])
				sink.box(-0.3, 64 + 0.66, -0.3 + 0.3, 0.3, 64 + 1.2, 0.3 + 0.3);
		});

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double before = lowest(run);

		placed[0] = true;

		for (int i = 0; i < 90; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		assertTrue(lowest(run) < before - 0.012, "the top is pushed in by the block " + lowest(run) + " from " + before);

		placed[0] = false;

		for (int i = 0; i < 300; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		assertEquals(before, lowest(run), 0.003, "and comes back");
	}

	@Test
	void aMeshTurnedOnItsOwnPivotIsFollowedAndDentedLikeAnyOther() {
		StrutMesh plain = ball();
		// the same ball turned about its own middle (in pixels), which does not change where its surface is
		StrutMesh turned = new StrutMesh(plain.polys(), new Vec3d(0, 0.6 * 16, 0.3 * 16), new Vec3d(0.4, 0.7, 0.3), false);
		Run run = start(json(""), turned);

		assertTrue(run.instance().problems().stream().noneMatch(p -> p.contains("own pivot")), "no problem is raised for it: " + run.instance().problems());
		assertEquals(1, run.instance().fleshCount());

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double before = lowest(run);

		for (int i = 0; i < 90; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of(hand()));

		assertTrue(lowest(run) < before - 0.012, "the top of the turned ball is pushed in " + lowest(run) + " from " + before);
	}

	/** The offsets of every corner, copied */
	private static float[] corners(Run run) {
		java.util.List<float[]> all = new java.util.ArrayList<>();

		run.instance().clothDeforms((mesh, deform) -> all.add(deform.cornerOffsets().clone()));

		int total = 0;

		for (float[] o : all)
			total += o.length;

		float[] out = new float[total];
		int at = 0;

		for (float[] o : all) {
			System.arraycopy(o, 0, out, at, o.length);
			at += o.length;
		}

		return out;
	}

	@Test
	void aShapeHeldStillInsideTheFleshLeavesTheDrawnSurfaceStill() {
		for (double sink : new double[] {0.0, 0.05, 0.12}) {
			Run run = start(json(""));
			double t = 0;
			Collider.Placed hand = hand();

			hand.p[1] -= sink;

			for (int i = 0; i < 120; i++, t += 1.0 / 60)
				frame(run, 0, t, List.of());

			for (int i = 0; i < 240; i++, t += 1.0 / 60)
				frame(run, 0, t, List.of(hand));

			float[] before = corners(run);
			double moved = 0;

			for (int i = 0; i < 60; i++, t += 1.0 / 60) {
				frame(run, 0, t, List.of(hand));

				float[] now = corners(run);

				for (int k = 0; k < now.length; k++)
					moved = Math.max(moved, Math.abs(now[k] - before[k]));

				before = now;
			}

			assertTrue(moved < 0.002, "sunk " + sink + " the surface still moves " + moved + " a frame");
		}
	}

	/** The most any corner has moved outward from the ball's middle (0, 0.6, 0.3) and the most any has moved at all, blocks */
	private static double[] outAndMost(Run run, StrutMesh mesh) {
		double[] result = {0, 0};
		StrutPoly[] polys = mesh.polys();

		run.instance().clothDeforms((m, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int poly = 0; poly < polys.length; poly++) {
				for (int c = 0; c < 4; c++) {
					int at = (poly * 4 + c) * 3;

					if (at + 2 >= o.length)
						continue;

					org.joml.Vector3f v = polys[poly].vertices()[c].position();
					double nx = v.x;
					double ny = v.y - 0.6;
					double nz = v.z - 0.3;
					double length = Math.sqrt(nx * nx + ny * ny + nz * nz);

					if (length < 1e-9)
						continue;

					result[0] = Math.max(result[0], (o[at] * nx + o[at + 1] * ny + o[at + 2] * nz) / length);
					result[1] = Math.max(result[1], Math.sqrt(o[at] * o[at] + o[at + 1] * o[at + 1] + o[at + 2] * o[at + 2]));
				}
			}
		});

		return result;
	}

	@Test
	void somethingSunkDeepIntoTheFleshDentsItDeeplyWithNothingStandingOut() {
		Run run = start(json(""));
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double before = lowest(run);
		// its bottom 0.08 into the top of the ball (radius 0.12): two thirds of the way to the middle
		Collider.Placed hand = new Collider.Placed();

		hand.shape = Collider.Shape.SPHERE;
		hand.radius = 0.08;
		hand.p[0] = 0;
		hand.p[1] = 64 + 0.72 + 0.08 - 0.08;
		hand.p[2] = 0.3;
		hand.layer = "body";

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of(hand));

		double[] out = outAndMost(run, ball());

		assertTrue(lowest(run) < before - 0.05, "pushed in deeply: " + lowest(run) + " from " + before);
		assertTrue(out[0] < 0.04, "nothing stands out of the surface past a swell: " + out[0]);
		assertTrue(out[1] < 0.12, "nothing goes through the middle: " + out[1]);
	}

	@Test
	void aBodyStandingInTheFleshLeavesItWholeWithNothingTornOrTurnedInsideOut() {
		Run run = start(json(""));
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		// a camera's body flown right through it: a capsule wider than the ball, its axis just off the middle
		Collider.Placed body = new Collider.Placed();

		body.shape = Collider.Shape.CAPSULE;
		body.radius = 0.3;
		body.p[0] = 0.05;
		body.p[1] = 64;
		body.p[2] = 0.3;
		body.q[0] = 0.05;
		body.q[1] = 64 + 1.8;
		body.q[2] = 0.3;
		body.layer = "body";

		float[] still = null;

		for (int i = 0; i < 120; i++, t += 1.0 / 60) {
			frame(run, 0, t, List.of(body));

			if (i == 90)
				still = corners(run);
		}

		double[] out = outAndMost(run, ball());
		float[] now = corners(run);
		double moved = 0;

		for (int k = 0; k < now.length; k++)
			moved = Math.max(moved, Math.abs(now[k] - still[k]));

		// it has swallowed the middle: there is no way round it, so the flesh is left as it was and only clips into it
		assertTrue(out[1] < 0.04, "the flesh is left whole: " + out[1]);
		assertTrue(moved < 0.002, "and it is still: " + moved);
	}

	@Test
	void aBlockThatHasSwallowedTheMiddleFlattensTheFleshAgainstItsFace() {
		Run run = start(json(""));
		boolean[] placed = {false};

		// the ball's middle is at y = 0.6; a block from 0.55 up has it, and its nearest face to the middle is the bottom one
		run.instance().world((x0, y0, z0, x1, y1, z1, sink) -> {
			if (placed[0])
				sink.box(-0.5, 64 + 0.55, -0.2, 0.5, 64 + 1.55, 0.8);
		});

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double before = lowest(run);

		placed[0] = true;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double[] out = outAndMost(run, ball());

		assertTrue(lowest(run) < before - 0.08, "the top of the ball is pressed down flat against the block " + lowest(run) + " from " + before);
		// what is pressed flat gives its lost volume back as a swell round the sides, as squashed flesh does (a third of the jelly's limit at most), but nothing spikes out
		assertTrue(out[0] < 0.1, "nothing stands out past a swell: " + out[0]);
	}

	@Test
	void aBlockTheFleshIsLeantIntoFlattensItAgainstTheFace() {
		Run run = start(json(""));
		boolean[] placed = {false};

		// a block whose underside is 0.1 into the top of the ball (radius 0.12), most of the way to the middle
		run.instance().world((x0, y0, z0, x1, y1, z1, sink) -> {
			if (placed[0])
				sink.box(-0.5, 64 + 0.62, -0.2, 0.5, 64 + 1.62, 0.8);
		});

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double before = lowest(run);

		placed[0] = true;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double[] out = outAndMost(run, ball());

		assertTrue(lowest(run) < before - 0.06, "the top is pressed down against the block: " + lowest(run) + " from " + before);
		assertTrue(out[0] < 0.04, "nothing stands out: " + out[0]);
	}

	@Test
	void aReleasedDentCreepsBackSlowerTheMoreMeat() {
		double[] left = new double[2];

		for (int m = 0; m < 2; m++) {
			Run run = start(json(", \"meat\": " + (m == 0 ? "0" : "1.5")));
			double t = 0;

			for (int i = 0; i < 120; i++, t += 1.0 / 60)
				frame(run, 0, t, List.of());

			double before = lowest(run);

			for (int i = 0; i < 90; i++, t += 1.0 / 60)
				frame(run, 0, t, List.of(hand()));

			double pressed = lowest(run);

			// let go, and look a tenth of a second later
			for (int i = 0; i < 6; i++, t += 1.0 / 60)
				frame(run, 0, t, List.of());

			left[m] = (lowest(run) - before) / (pressed - before);
		}

		assertTrue(left[1] > left[0] + 0.05, "with more meat more of the dent is still there: " + left[0] + " against " + left[1]);
	}

	@Test
	void pressedInTheFleshGivesItsLostVolumeBackAsASwellEvenWhenOpen() {
		// the ball is an open mesh as far as the cage is concerned (it keeps no volume of its own): the cage's shell does the swelling
		Run run = start(json(", \"bulge\": 1.0"));
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double[] before = outAndMost(run, ball());

		for (int i = 0; i < 90; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of(hand()));

		double[] pressed = outAndMost(run, ball());

		assertTrue(pressed[0] > before[0] + 0.004, "somewhere the surface swells out: " + pressed[0] + " from " + before[0]);
	}

	/** Two balls that share no vertex, merged into one mesh: ball A at x = -0.3 and ball B at x = +0.3 */
	private static StrutMesh twoBalls() {
		StrutMesh a = DebugSkirt.ball(-0.3, 0.6, 0.3, 0.12);
		StrutMesh b = DebugSkirt.ball(0.3, 0.6, 0.3, 0.12);
		StrutPoly[] polys = new StrutPoly[a.polys().length + b.polys().length];

		System.arraycopy(a.polys(), 0, polys, 0, a.polys().length);
		System.arraycopy(b.polys(), 0, polys, a.polys().length, b.polys().length);

		return new StrutMesh(polys, Vec3d.ZERO, Vec3d.ZERO, false);
	}

	@Test
	void aTipMergedIntoTheMeshIsOneWithThePouchAndPressingItInDentsThePouch() {
		StrutMesh a = ball();
		StrutMesh b = DebugSkirt.ball(0, 0.70, 0.3, 0.04);
		StrutPoly[] polys = new StrutPoly[a.polys().length + b.polys().length];

		System.arraycopy(a.polys(), 0, polys, 0, a.polys().length);
		System.arraycopy(b.polys(), 0, polys, a.polys().length, b.polys().length);

		StrutMesh merged = new StrutMesh(polys, Vec3d.ZERO, Vec3d.ZERO, false);
		Run pressed = start(json(""), merged);
		Run free = start(json(""), merged);

		assertEquals(1, pressed.instance().flesh("ball").regionCount(), "the tip is part of the pouch, not a region of its own");

		// a small thing pressing the tip's top in, 0.03
		Collider.Placed finger = new Collider.Placed();

		finger.shape = Collider.Shape.SPHERE;
		finger.radius = 0.02;
		finger.p[0] = 0;
		finger.p[1] = 64 + 0.74 + 0.02 - 0.03;
		finger.p[2] = 0.3;
		finger.layer = "body";

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60) {
			frame(pressed, 0, t, List.of());
			frame(free, 0, t, List.of());
		}

		for (int i = 0; i < 120; i++, t += 1.0 / 60) {
			frame(pressed, 0, t, List.of(finger));
			frame(free, 0, t, List.of());
		}

		float[] with = corners(pressed);
		float[] without = corners(free);
		double deepest = 0;

		// the pouch's own corners, which the finger never touches (it is inside the tip): they go in because the tip does
		for (int k = 1; k < a.polys().length * 12 && k < with.length; k += 3)
			deepest = Math.min(deepest, with[k] - without[k]);

		assertTrue(deepest < -0.005, "the pouch round the tip goes in with it " + deepest);
	}

	/** The lowest corner offset along y, of the polys from {@code from} to {@code to} of the mesh */
	private static double lowestOf(Run run, int from, int to) {
		double[] low = {0};

		run.instance().clothDeforms((mesh, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int i = from * 12 + 1; i < to * 12 && i < o.length; i += 3)
				low[0] = Math.min(low[0], o[i]);
		});

		return low[0];
	}

	/** The largest offset of the corners of the polys from {@code from} to {@code to} of the mesh */
	private static double offsetOf(Run run, int from, int to) {
		double[] most = {0};

		run.instance().clothDeforms((mesh, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int i = from * 12; i < to * 12 && i < o.length; i++)
				most[0] = Math.max(most[0], Math.abs(o[i]));
		});

		return most[0];
	}

	/** One quad, four particles, beside the ball */
	private static StrutMesh patch() {
		Vector3f n = new Vector3f(0, 0, 1);
		StrutVertex[] corners = {new StrutVertex(new Vector3f(0.25f, 0.55f, 0.3f), 0, 0, n), new StrutVertex(new Vector3f(0.35f, 0.55f, 0.3f), 0, 0, n),
				new StrutVertex(new Vector3f(0.35f, 0.65f, 0.3f), 0, 0, n), new StrutVertex(new Vector3f(0.25f, 0.65f, 0.3f), 0, 0, n)};

		return new StrutMesh(new StrutPoly[] {new StrutPoly(corners, n)}, Vec3d.ZERO, Vec3d.ZERO, false);
	}

	@Test
	void aBoneWithSeveralSoftMeshesOfDifferentSizesEachMovesItsOwnParticles() {
		// the belly of the real model is three meshes of 28, 20 and 738 particles: each must own its own, not the biggest's
		Run run = start(json(""), patch(), ball());

		assertEquals(1, run.instance().flesh("ball").regionCount());
		assertEquals(4 + 114, run.instance().flesh("ball").vertices());

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0.3 * Math.sin(t * 6), t, List.of());

		assertTrue(largest(run) > 0, "and it runs");
	}

	@Test
	void twoPartsInOneMeshAreTwoRegionsEachMovedAndPressedOnItsOwn() {
		StrutMesh both = twoBalls();
		int half = both.polys().length / 2;
		Run run = start(json(""), both);

		assertEquals(1, run.instance().fleshCount());
		assertEquals(2, run.instance().flesh("ball").regionCount(), "one cage for each ball");
		assertEquals(228, run.instance().flesh("ball").vertices(), "two balls of 114");

		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double restingB = offsetOf(run, half, half * 2);

		// ball A (x = -0.3) is pressed on the top
		Collider.Placed onA = hand();

		onA.p[0] = -0.3;

		for (int i = 0; i < 90; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of(onA));

		double pressedA = 0;
		double[] lowest = {0};

		run.instance().clothDeforms((mesh, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int i = 1; i < half * 12; i += 3)
				lowest[0] = Math.min(lowest[0], o[i]);
		});
		pressedA = lowest[0];

		assertTrue(pressedA < -0.04, "ball A is pushed in: " + pressedA);
		assertEquals(restingB, offsetOf(run, half, half * 2), 0.004, "ball B was not touched and stays as it was");

		// and when A is let go it settles and B is still as it was
		for (int i = 0; i < 300; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		assertEquals(restingB, offsetOf(run, half, half * 2), 0.004);
		assertTrue(offsetOf(run, 0, half) < restingB + 0.01, "A has come back");
	}

	/** The mean of the corners' offsets along x: the ball as a whole moving, which a dent in one place does not */
	private static double meanX(Run run) {
		double[] sum = {0, 0};

		run.instance().clothDeforms((mesh, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int i = 0; i < o.length; i += 3) {
				sum[0] += o[i];
				sum[1]++;
			}
		});

		return sum[0] / Math.max(1, sum[1]);
	}

	@Test
	void aShapePushedIntoTheSideShovesTheWholeBallAndItSwingsBackWhenLetGo() {
		Run run = start(json(""));
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		double resting = meanX(run);
		double shoved = resting;

		// from the left: it touches the ball at x = -0.2 and is pushed in 0.1 further, at 0.3 a second
		for (int i = 0; i < 60; i++, t += 1.0 / 60) {
			Collider.Placed side = hand();

			side.p[0] = -0.3 + 0.2 * Math.min(1.0, i / 20.0);
			side.p[1] = 64 + 0.6;
			side.p[2] = 0.3;
			frame(run, 0, t, List.of(side));
			shoved = Math.max(shoved, meanX(run));
		}

		assertTrue(shoved > resting + 0.005, "the whole ball is moved along by the push: " + shoved + " from " + resting);

		// let go: it swings back past where it was, and settles
		double back = shoved;

		for (int i = 0; i < 240; i++, t += 1.0 / 60) {
			frame(run, 0, t, List.of());
			back = Math.min(back, meanX(run));
		}

		assertTrue(back < resting - 0.0015, "it swings back past rest: " + back + " from " + resting);
		assertEquals(resting, meanX(run), 0.004, "and settles");
	}

	/** The volume of the ball as it is drawn now, relative to its volume at rest: its polys' corners moved by the offsets, by the divergence theorem */
	private static double volumeRatio(Run run) {
		StrutMesh mesh = run.model().flatBones()[1].getMeshes().get(0);
		double[] drawn = {0};
		double rest = 0;

		for (int p = 0; p < mesh.polys().length; p++) {
			var corners = mesh.polys()[p].vertices();
			double[][] at = new double[4][3];
			double[][] home = new double[4][3];

			for (int c = 0; c < 4; c++) {
				var v = corners[c].position();

				home[c] = new double[] {v.x, v.y, v.z};
			}

			rest += tetra(home[0], home[1], home[2]) + tetra(home[0], home[2], home[3]);
		}

		run.instance().clothDeforms((m, deform) -> {
			float[] o = deform.cornerOffsets();

			for (int p = 0; p < m.polys().length; p++) {
				double[][] at = new double[4][3];

				for (int c = 0; c < 4; c++) {
					var v = m.polys()[p].vertices()[c].position();
					int i = (p * 4 + c) * 3;

					at[c] = new double[] {v.x + o[i], v.y + o[i + 1], v.z + o[i + 2]};
				}

				drawn[0] += tetra(at[0], at[1], at[2]) + tetra(at[0], at[2], at[3]);
			}
		});

		return drawn[0] / rest;
	}

	private static double tetra(double[] a, double[] b, double[] c) {
		return (a[0] * (b[1] * c[2] - b[2] * c[1]) + a[1] * (b[2] * c[0] - b[0] * c[2]) + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6.0;
	}

	@Test
	void aPressedClosedBallGivesItsVolumeBackAsASwellAroundTheDent() {
		double keeps = pressedVolume(", \"bulge\": 1.0");
		double loses = pressedVolume(", \"bulge\": 0");

		assertTrue(loses < 0.97, "pressed with no swell it loses volume: " + loses);
		assertTrue(keeps > loses + 0.02, "with the swell it keeps more of it: " + keeps + " against " + loses);
	}

	private static double pressedVolume(String extra) {
		Run run = start(json(extra));
		double t = 0;

		for (int i = 0; i < 120; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		Collider.Placed press = hand();

		for (int i = 0; i < 150; i++, t += 1.0 / 60) {
			// pressed down from above to about a third of the way in (the ball's top is at 0.72)
			press.p[1] = 64 + 0.80 - 0.10 * Math.min(1.0, i / 60.0);
			frame(run, 0, t, List.of(press));
		}

		return volumeRatio(run);
	}

	@Test
	void outsideTheSoftZoneTheFleshIsSewnToTheBone() {
		double free = shaken(json(""));
		double zone = shaken(json(", \"softCenter\": [0, 0.6, 0.3], \"softRadius\": 0.05"));

		assertTrue(free > 0.01, "without a zone it moves " + free);
		assertTrue(zone < free * 0.5, "with a zone this small almost all of it is sewn on: " + zone + " against " + free);
	}

	private static double shaken(String json) {
		Run run = start(json);
		double t = 0;
		double most = 0;

		for (int i = 0; i < 60; i++, t += 1.0 / 60)
			frame(run, 0, t, List.of());

		for (int i = 0; i < 90; i++, t += 1.0 / 60) {
			frame(run, 0.4 * Math.sin(t * 8), t, List.of());
			most = Math.max(most, largest(run));
		}

		return most;
	}

	@Test
	void aFileWrittenForTheOldPerVertexFleshLoadsAndSaysWhatNoLongerApplies() {
		PhysicsConfig config = PhysicsConfig.parse("{\"flesh\": {\"belly\": {\"bone\": \"belly\", \"preset\": \"flesh-firm\", \"anchor\": 12, \"stiffness\": 0.9,"
				+ " \"inertia\": 0.8, \"volume\": 0.7, \"softCenter\": [0, 1, 0], \"softRadius\": 0.2, \"carry\": [\"tip\"]},"
				+ " \"bad\": {\"bone\": \"x\", \"preset\": \"slime\"}}}", "test");
		FleshParams belly = config.flesh().get("belly");

		assertNotNull(belly);
		assertTrue(config.cloth().isEmpty(), "flesh is no longer made of cloth");
		assertEquals(0.7f, belly.softness(), 1e-6f, "flesh-firm");
		assertEquals(0.7f, belly.volume(), 1e-6f);
		assertArrayEquals(new float[] {0, 1, 0}, belly.softCenter());
		assertEquals(0.2f, belly.softRadius(), 1e-6f);
		assertEquals(List.of("tip"), belly.carry());
		assertTrue(belly.collidesWith().contains("players") && belly.collidesWith().contains("other"), "pressed by other bodies by default");
		assertEquals(1, config.warnings().stream().filter(w -> w.contains("per-vertex")).count(), config.warnings().toString());
		assertTrue(config.warnings().stream().anyMatch(w -> w.contains("per-vertex") && w.contains("anchor") && w.contains("stiffness") && w.contains("inertia")));
		assertTrue(config.warnings().stream().anyMatch(w -> w.contains("slime")), config.warnings().toString());
		assertFalse(config.isEmpty());
	}

	@Test
	void theCagesOwnGroupsKeepTheirNamesInAFleshEntryAndBoneOrBonesBothWork() {
		PhysicsConfig config = PhysicsConfig.parse("{\"flesh\": {\"one\": {\"bone\": \"a\", \"anchor\": {\"from\": 0.5}, \"lump\": {\"damping\": 0.3}},"
				+ " \"two\": {\"bones\": [\"b\", \"c\"], \"preset\": \"flesh-soft\"}}}", "test");

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());
		assertEquals(0.5f, config.flesh().get("one").anchorFrom(), 1e-6f);
		assertEquals(0.3f * 1.4f, config.flesh().get("one").lumpDamping(), 1e-6f);
		assertEquals(List.of("b", "c"), config.flesh().get("two").bones());
		assertEquals(1.3f, config.flesh().get("two").softness(), 1e-6f);
	}

	@Test
	void theOldSoftSectionStillReadsAndSaysItsNewName() {
		PhysicsConfig config = PhysicsConfig.parse("{\"soft\": {\"chest\": {\"preset\": \"pouch\", \"bones\": [\"pouchL\", \"pouchR\"]}}}", "test");

		assertEquals(1, config.flesh().size());
		assertTrue(config.warnings().stream().anyMatch(w -> w.contains("now called \"flesh\"")), config.warnings().toString());
	}
}
