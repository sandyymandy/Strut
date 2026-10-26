package com.sandymandy.strut.physics.world;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.json.raw.ModelProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.solver.ChainInstance;
import com.sandymandy.strut.physics.solver.ChainParams;
import com.sandymandy.strut.physics.solver.ChainSolver;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Physics module: chains collide with blocks, lose motion to friction, and can be held by the cursor
 * (the in-game tool's grab). The world is a stand-in floor here; the game reads the real blocks through {@link WorldSource}
 */
class WorldCollisionTest {
	private static BakedStrutModel model() {
		GroupBoneDefinition root = new GroupBoneDefinition(null, "root", false, null, null, null);
		GroupBoneDefinition hair1 = new GroupBoneDefinition(root, "hair1", false, null, null, null);
		GroupBoneDefinition hair2 = new GroupBoneDefinition(hair1, "hair2", false, null, null, null);
		GroupBoneDefinition hair3 = new GroupBoneDefinition(hair2, "hair3", false, null, null, null);
		GroupBoneDefinition lump = new GroupBoneDefinition(root, "lump", false, null, null, null);

		root.bakePivot(0, 0, 0);
		hair1.bakePivot(0, 24, 0);
		hair2.bakePivot(0, 20, 0);
		hair3.bakePivot(0, 16, 0);
		lump.bakePivot(0, 10, 0);
		root.getChildBones().add(hair1);
		root.getChildBones().add(lump);
		hair1.getChildBones().add(hair2);
		hair2.getChildBones().add(hair3);

		ModelProperties properties = new ModelProperties(null, null, null, null, null, null, null, null, null, null,
				"test", null, 16, 16, null, new double[] {0, 0, 0}, null);

		return new BakedStrutModel(List.of(root), properties);
	}

	/** A floor whose top is at {@code top}, covering everything near the origin */
	private static WorldSource floor(double top) {
		return (x0, y0, z0, x1, y1, z1, sink) -> sink.box(-20, top - 1, -20, 20, top, 20);
	}

	private record Run(BakedStrutModel model, PhysicsInstance instance, Pose animated, Pose pose) {}

	private static Run start(String json, WorldSource world) {
		BakedStrutModel model = model();
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(json, "test"));

		instance.world(world);

		return new Run(model, instance, animated, pose);
	}

	private static void frame(Run run, PhysicsControls controls, double t, double x, double z) {
		run.pose().copyFrom(run.animated());
		run.instance().update(run.model(), run.animated(), run.pose(), new PhysicsInstance.Sample(x, 64, z, 0f, t), controls);
	}

	private static final String CHAIN = "{\"chains\": {\"ponytail\": {\"bones\": [\"hair1\", \"hair2\", \"hair3\"], \"preset\": \"hair\", \"tipLength\": 0.25%s}}}";

	@Test
	void aChainHangingOntoAFloorRestsOnItAndDoesNotGoThrough() {
		// root at y = 64 + 24/16 = 65.5 and the chain reaches 0.75 down, to 64.75; the floor top is at 65.0, so the chain has to bend up off it
		Run run = start(String.format(CHAIN, ""), floor(65.0));
		PhysicsControls controls = new PhysicsControls();
		double lowest = Double.MAX_VALUE;

		for (int i = 0; i <= 300; i++) {
			frame(run, controls, i / 60.0, 0.5 * Math.sin(i / 20.0), 0);

			ChainInstance chain = run.instance().chain("ponytail");

			if (!chain.state.initialised)
				continue;

			for (int k = 1; k < 4; k++)
				lowest = Math.min(lowest, chain.state.x[k][1]);
		}

		assertTrue(lowest >= 65.0 + 0.05 - 0.01, "lowest particle " + lowest + ", the floor plus the particle radius is 65.05");
	}

	@Test
	void withoutTheWorldLayerTheSameChainGoesThroughTheFloor() {
		Run run = start(String.format(CHAIN, ", \"collidesWith\": [\"body\"]"), floor(65.0));
		PhysicsControls controls = new PhysicsControls();
		double lowest = Double.MAX_VALUE;

		for (int i = 0; i <= 120; i++) {
			frame(run, controls, i / 60.0, 0, 0);

			if (run.instance().chain("ponytail").state.initialised)
				lowest = Math.min(lowest, run.instance().chain("ponytail").state.x[3][1]);
		}

		assertTrue(lowest < 64.9, "with no world layer the tip hangs to " + lowest);
	}

	@Test
	void thePhysicsOptionSwitchesWorldCollisionOff() {
		PhysicsOptions.worldCollision(false);

		try {
			Run run = start(String.format(CHAIN, ""), floor(65.0));
			PhysicsControls controls = new PhysicsControls();
			double lowest = Double.MAX_VALUE;

			for (int i = 0; i <= 120; i++) {
				frame(run, controls, i / 60.0, 0, 0);

				if (run.instance().chain("ponytail").state.initialised)
					lowest = Math.min(lowest, run.instance().chain("ponytail").state.x[3][1]);
			}

			assertTrue(lowest < 64.9, "with world collision off: " + lowest);
		}
		finally {
			PhysicsOptions.worldCollision(true);
		}
	}

	@Test
	void frictionTakesMotionFromAParticleInContact() {
		ChainParams none = PhysicsConfig.parse(String.format(CHAIN, ", \"friction\": 0, \"gravity\": 0"), "t").chains().get("ponytail");
		ChainParams heavy = PhysicsConfig.parse(String.format(CHAIN, ", \"friction\": 0.5, \"gravity\": 0"), "t").chains().get("ponytail");
		double[] speeds = new double[2];

		for (int variant = 0; variant < 2; variant++) {
			ChainSolver.State st = new ChainSolver.State(2);
			double[][] anim = {{0, 1, 0}, {0, 1, 1}};
			Collider.Placed wall = new Collider.Placed();

			wall.shape = Collider.Shape.BOX;
			wall.half[0] = 5;
			wall.half[1] = 5;
			wall.half[2] = 0.2;
			wall.p[0] = 0;
			wall.p[1] = 1;
			wall.p[2] = 1.22;
			wall.axes.identity();
			st.snapTo(anim);
			// at the end of its segment (1 from the root), pressed 0.03 into the wall's near face (z = 1.02), moving sideways
			st.x[1][0] = 0;
			st.x[1][1] = 1;
			st.x[1][2] = 1.0;
			st.prev[1][0] = -0.02;
			st.prev[1][1] = 1;
			st.prev[1][2] = 1.0;
			ChainSolver.step(st, variant == 0 ? none : heavy, 1f / 60f, anim, new double[] {1.0}, new double[3], List.of(wall));

			speeds[variant] = Math.abs(st.x[1][0] - st.prev[1][0]);
		}

		assertTrue(speeds[1] < speeds[0] * 0.7, "friction 0.5 kept " + speeds[1] + " of " + speeds[0]);
	}

	@Test
	void aPinnedParticleStaysWhereItIsPutAndTheChainFollowsItWithItsLengths() {
		Run run = start(String.format(CHAIN, ""), null);
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 30; i++)
			frame(run, controls, i / 60.0, 0, 0);

		// root is at (0, 65.5, 0) in world space; hold the tip half a block to the side and a little below the root
		controls.grab("ponytail", 3, 0.5, 65.1, 0.0);

		for (int i = 31; i <= 120; i++)
			frame(run, controls, i / 60.0, 0, 0);

		ChainInstance chain = run.instance().chain("ponytail");

		assertEquals(0.5, chain.state.x[3][0], 1e-6);
		assertEquals(65.1, chain.state.x[3][1], 1e-6);
		assertTrue(chain.state.x[2][0] > 0.2, "the particle before it is pulled across: " + chain.state.x[2][0]);
		assertTrue(chain.worstLengthError() < 0.1, "lengths while held " + chain.worstLengthError());
		assertNotEquals(0f, run.pose().getRotZ(run.model().getBone("hair1").orElseThrow().getIndex()), "and the bones turn to show it");

		controls.release();

		for (int i = 121; i <= 400; i++)
			frame(run, controls, i / 60.0, 0, 0);

		assertEquals(0.0, chain.state.x[3][0], 0.05, "let go, it swings back to hang");
	}

	@Test
	void aSpringBoneHeldByTheCursorLeansTowardItAndSettlesWhenReleased() {
		Run run = start("{\"bones\": {\"lump\": {\"preset\": \"jiggle-soft\"}}}", null);
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 10; i++)
			frame(run, controls, i / 60.0, 0, 0);

		// the lump hangs from the root pivot region; pull its mass well off to the side
		controls.grab("lump", 0, 0.5, 64.0, 0.0);

		for (int i = 11; i <= 90; i++)
			frame(run, controls, i / 60.0, 0, 0);

		float held = Math.abs(run.instance().rotationOf("lump")[2]) + Math.abs(run.instance().rotationOf("lump")[0]);

		assertTrue(held > 0.05f, "the bone leans toward the cursor: " + held);

		controls.release();

		for (int i = 91; i <= 400; i++)
			frame(run, controls, i / 60.0, 0, 0);

		assertEquals(0f, run.instance().rotationOf("lump")[2], 1e-3f);
	}

	@Test
	void aWorldWithTooManyBoxesIsCappedNotFatal() {
		WorldSource crowded = (x0, y0, z0, x1, y1, z1, sink) -> {
			for (int i = 0; i < 2000; i++)
				sink.box(30 + i, 0, 30, 31 + i, 1, 31);
		};
		Run run = start(String.format(CHAIN, ""), crowded);
		PhysicsControls controls = new PhysicsControls();

		for (int i = 0; i <= 60; i++)
			frame(run, controls, i / 60.0, 0, 0);

		assertTrue(run.instance().chain("ponytail").worstLengthError() < 0.02);
	}

	@Test
	void timeScaleSlowsThePhysicsWithoutBreakingIt() {
		PhysicsOptions.timeScale(0.25f);

		try {
			Run slow = start(String.format(CHAIN, ""), null);
			PhysicsControls controls = new PhysicsControls();
			double z = 0;

			for (int i = 0; i <= 45; i++) {
				z = 8.0 * (i / 60.0) * (i / 60.0);
				frame(slow, controls, i / 60.0, 0, z);
			}

			ChainInstance chain = slow.instance().chain("ponytail");

			assertTrue(chain.worstLengthError() < 0.02);
		}
		finally {
			PhysicsOptions.timeScale(1f);
		}
	}
}
