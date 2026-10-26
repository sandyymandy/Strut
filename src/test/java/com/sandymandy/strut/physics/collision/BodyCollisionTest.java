package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.solver.SpringParams;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Entities pressing on each other's soft parts. The registry of published bodies, a chain and a soft bone pushed by a player's hitbox or by another
 * model's colliders, the squish, and the two switches
 */
class BodyCollisionTest {
	private static BakedStrutModel dummy() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file), "needs Strut's test dummy model next to the module");

		return BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
	}

	@BeforeEach
	void fresh() {
		Bodies.clear();
		PhysicsOptions.otherCollision(true);
		PhysicsOptions.playerCollision(true);
	}

	@AfterEach
	void restore() {
		Bodies.clear();
		PhysicsOptions.otherCollision(true);
		PhysicsOptions.playerCollision(true);
	}

	// ---- the registry ---------------------------------------------------------------------------------------------------------------------------

	private static List<Collider.Placed> near(int self, boolean others, boolean players) {
		List<Collider.Placed> out = new ArrayList<>();

		Bodies.nearby(self, 0, 64, 0, 10, others, players, out);

		return out;
	}

	@Test
	void anEntityNeverFeelsItself() {
		Bodies.nextFrame();
		Bodies.publishHitbox(1, Bodies.PLAYERS, 0, 64, 0, 1.8, 0.3);

		assertTrue(near(1, true, true).isEmpty());
		assertEquals(1, near(2, true, true).size());
	}

	@Test
	void theTwoSwitchesChooseWhoIsFelt() {
		Bodies.nextFrame();
		Bodies.publishHitbox(1, Bodies.PLAYERS, 0, 64, 0, 1.8, 0.3);
		Bodies.publishHitbox(2, Bodies.MOBS, 1, 64, 0, 1.0, 0.3);

		assertEquals(2, near(9, true, true).size());
		assertEquals(1, near(9, false, true).size(), "players only");
		assertEquals(1, near(9, true, false).size(), "mobs and models only");
		assertTrue(near(9, false, false).isEmpty());
	}

	@Test
	void aPartFeelsOnlyTheLayersItListsAndAModelsBodyIsOther() {
		Collider.Placed body = new Collider.Placed();
		Collider.Placed player = new Collider.Placed();
		Collider.Placed mob = new Collider.Placed();

		body.layer = "body";
		player.layer = Bodies.PLAYERS;
		mob.layer = Bodies.MOBS;

		assertTrue(Bodies.feels(Set.of("other"), body));
		assertFalse(Bodies.feels(Set.of("players", "mobs"), body));
		assertTrue(Bodies.feels(Set.of("players"), player));
		assertFalse(Bodies.feels(Set.of("players"), mob));
		assertTrue(Bodies.feels(Set.of("mobs"), mob));
		assertFalse(Bodies.feels(Set.of(), player), "nothing listed, nothing felt");
	}

	@Test
	void shapesFarAwayAndStaleOnesAreLeftOut() {
		Bodies.nextFrame();
		Bodies.publishHitbox(1, Bodies.PLAYERS, 50, 64, 0, 1.8, 0.3);
		Bodies.publishHitbox(2, Bodies.PLAYERS, 1, 64, 0, 1.8, 0.3);

		assertEquals(1, near(9, true, true).size(), "the one 50 blocks away is out of reach");

		// entity 2 stops publishing: it is ignored after a frame and forgotten after a few
		Bodies.nextFrame();
		Bodies.nextFrame();
		Bodies.nextFrame();

		assertTrue(near(9, true, true).isEmpty());
		assertEquals(0, Bodies.published());
	}

	@Test
	void aModelsBodyCollidersReplaceItsHitboxAndLoseTheirOtherLayers() {
		Collider.Placed body = new Collider.Placed();
		Collider.Placed hair = new Collider.Placed();

		body.shape = Collider.Shape.SPHERE;
		body.layer = "body";
		body.radius = 0.4;
		body.p[1] = 64;
		hair.shape = Collider.Shape.SPHERE;
		hair.layer = "hair";
		hair.radius = 0.4;

		Bodies.nextFrame();
		Bodies.publishHitbox(1, Bodies.MOBS, 0, 64, 0, 1.8, 0.3);
		Bodies.publishModel(1, List.of(body, hair));
		// the hitbox arrives again the same frame (the order of publishing is not fixed): the model's shapes stay
		Bodies.publishHitbox(1, Bodies.MOBS, 0, 64, 0, 1.8, 0.3);

		List<Collider.Placed> shapes = near(2, true, true);

		assertEquals(1, shapes.size(), "the body collider only: neither the hair layer nor the hitbox");
		assertEquals("body", shapes.get(0).layer);
	}

	@Test
	void aModelWithoutBodyCollidersKeepsItsHitbox() {
		Collider.Placed hair = new Collider.Placed();

		hair.shape = Collider.Shape.SPHERE;
		hair.layer = "hair";
		hair.radius = 0.4;

		Bodies.nextFrame();
		Bodies.publishHitbox(1, Bodies.MOBS, 0, 64, 0, 1.8, 0.3);
		Bodies.publishModel(1, List.of(hair));

		assertEquals(1, near(2, true, true).size());
		assertEquals(Bodies.MOBS, near(2, true, true).get(0).layer);
	}

	// ---- config ---------------------------------------------------------------------------------------------------------------------------------

	@Test
	void springBonesReadCollidesWithRadiusAndSquishAndTheSoftBonePresetPressesByDefault() {
		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"a\": {\"preset\": \"soft-bone\"}, \"b\": {\"collidesWith\": [\"players\"], \"radius\": 0.2, \"squish\": 0.5}, \"c\": {}}}", "test");

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());

		SpringParams a = config.bones().get("a");
		SpringParams b = config.bones().get("b");
		SpringParams c = config.bones().get("c");

		assertEquals(Set.of("other", "players", "mobs", "body"), a.collidesWith());
		assertEquals(0.3f, a.squish(), 1e-6f);
		assertEquals(Set.of("players"), b.collidesWith());
		assertEquals(0.2f, b.radius(), 1e-6f);
		assertEquals(0.5f, b.squish(), 1e-6f);
		assertTrue(c.collidesWith().isEmpty(), "an ordinary bone collides with nothing, so every earlier config behaves as before");
		assertEquals(0f, c.squish(), 1e-6f);
	}

	// ---- a chain pressed by a hitbox and by another model ----------------------------------------------------------------------------------------

	private static final String CHAIN_JSON = "{\"chains\": {\"armL\": {\"bones\": [\"armL\", \"lowerArmL\"], \"preset\": \"tail\", \"frequency\": 1.0, \"damping\": 0.2, "
			+ "\"tipLength\": 0.3, \"radius\": 0.06, \"maxAngle\": 90, \"collidesWith\": [\"players\", \"other\"]}}}";

	private static final class Rig {
		final BakedStrutModel model;
		final PhysicsInstance instance;
		final ModelInstance modelInstance = new ModelInstance();
		final Pose animated;
		final Pose pose;
		double seconds;

		Rig(BakedStrutModel model, String json, int id) {
			this.model = model;
			this.instance = new PhysicsInstance(model, PhysicsConfig.parse(json, "test"));
			this.instance.entityId(id);
			this.modelInstance.poseFor(model);
			this.modelInstance.resetPoseHistory();
			this.animated = this.modelInstance.renderedPose();
			this.pose = new Pose(this.animated.boneCount());
		}

		void frame(double x, double y, double z, float yaw) {
			this.pose.copyFrom(this.animated);
			this.instance.update(this.model, this.animated, this.pose, new PhysicsInstance.Sample(x, y, z, yaw, this.seconds), new PhysicsControls());
			this.seconds += 1.0 / 60.0;
		}
	}

	/** The distance from a point to a vertical capsule's axis segment, less its radius: below zero means inside */
	private static double insideCapsule(double[] point, double x, double y0, double y1, double z, double radius) {
		double cy = Math.max(y0, Math.min(y1, point[1]));
		double dx = point[0] - x;
		double dy = point[1] - cy;
		double dz = point[2] - z;

		return Math.sqrt(dx * dx + dy * dy + dz * dz) - radius;
	}

	@Test
	void aPlayersBodyPushesAChainOutOfItAndPassesThroughWhenTheSwitchIsOff() throws Exception {
		BakedStrutModel model = dummy();
		Rig rig = new Rig(model, CHAIN_JSON, 1);

		for (int i = 0; i < 20; i++)
			rig.frame(0, 64, 0, 0f);

		List<PhysicsInstance.Pickable> parts = new ArrayList<>();

		rig.instance.pickables(parts);

		PhysicsInstance.Pickable tip = parts.stream().filter(p -> p.part().equals("armL") && p.index() == 2).findFirst().orElseThrow();

		// a player's capsule standing exactly where the arm hangs
		double radius = 0.3;
		Bodies.nextFrame();
		Bodies.publishHitbox(50, Bodies.PLAYERS, tip.x(), tip.y() - 1.0, tip.z(), 2.0, radius);

		double worst = 1;

		for (int i = 0; i < 60; i++) {
			Bodies.nextFrame();
			Bodies.publishHitbox(50, Bodies.PLAYERS, tip.x(), tip.y() - 1.0, tip.z(), 2.0, radius);
			rig.frame(0, 64, 0, 0f);

			List<PhysicsInstance.Pickable> now = new ArrayList<>();

			rig.instance.pickables(now);

			for (PhysicsInstance.Pickable p : now) {
				if (p.part().equals("armL"))
					worst = Math.min(worst, insideCapsule(new double[] {p.x(), p.y(), p.z()}, tip.x(), tip.y() - 1.0 + radius, tip.y() + 1.0 - radius, tip.z(), radius + 0.06));
			}
		}

		assertTrue(worst > -0.02, "an arm particle sank " + (-worst) + " blocks into the player");

		// the same again with players off: nothing pushes, so the particle stays where the animation puts it
		PhysicsOptions.playerCollision(false);

		Rig free = new Rig(model, CHAIN_JSON, 3);

		for (int i = 0; i < 20; i++)
			free.frame(0, 64, 0, 0f);

		for (int i = 0; i < 60; i++) {
			Bodies.nextFrame();
			Bodies.publishHitbox(50, Bodies.PLAYERS, tip.x(), tip.y() - 1.0, tip.z(), 2.0, radius);
			free.frame(0, 64, 0, 0f);
		}

		List<PhysicsInstance.Pickable> now = new ArrayList<>();

		free.instance.pickables(now);

		PhysicsInstance.Pickable freeTip = now.stream().filter(p -> p.part().equals("armL") && p.index() == 2).findFirst().orElseThrow();

		assertEquals(tip.x(), freeTip.x(), 0.02);
		assertEquals(tip.y(), freeTip.y(), 0.02);
		assertEquals(tip.z(), freeTip.z(), 0.02);
	}

	@Test
	void oneModelsBodyStopsAnotherModelsChain() throws Exception {
		BakedStrutModel model = dummy();
		String bodies = "{\"chains\": {\"armL\": {\"bones\": [\"armL\", \"lowerArmL\"], \"preset\": \"tail\", \"frequency\": 1.0, \"damping\": 0.2, \"tipLength\": 0.3, "
				+ "\"radius\": 0.06, \"maxAngle\": 90, \"collidesWith\": [\"other\"]}}, \"colliders\": ["
				+ "{\"bone\": \"torso\", \"shape\": \"capsule\", \"from\": [0, -3, 0], \"to\": [0, 10, 0], \"radius\": 2.5, \"layer\": \"body\"}]}";
		Rig mover = new Rig(model, bodies, 1);
		Rig still = new Rig(model, bodies, 2);

		// the still one stands at x = 0, and the mover is brought so its left arm hangs across the still one's torso
		for (int i = 0; i < 30; i++) {
			Bodies.nextFrame();
			still.frame(0, 64, 0, 0f);
			mover.frame(0.4, 64, 0, 0f);
		}

		assertTrue(Bodies.published() >= 1, "the still model published its torso");

		double worst = 0;

		for (int i = 0; i < 120; i++) {
			Bodies.nextFrame();
			still.frame(0, 64, 0, 0f);
			// the mover sweeps its arm through the other torso and back
			mover.frame(0.4 - 0.8 * Math.abs(Math.sin(i / 30.0)), 64, 0, 0f);

			List<PhysicsInstance.Pickable> parts = new ArrayList<>();

			mover.instance.pickables(parts);

			List<Collider.Placed> shapes = new ArrayList<>();

			Bodies.nearby(1, 0, 65, 0, 5, true, true, shapes);

			for (PhysicsInstance.Pickable p : parts) {
				for (Collider.Placed s : shapes) {
					double[] point = {p.x(), p.y(), p.z()};
					double[] copy = point.clone();

					if (Collider.pushOut(s, copy, 0.06)) {
						double d = Math.sqrt(Math.pow(copy[0] - point[0], 2) + Math.pow(copy[1] - point[1], 2) + Math.pow(copy[2] - point[2], 2));

						worst = Math.max(worst, d);
					}
				}
			}
		}

		assertTrue(worst < 0.03, "a chain particle sank " + worst + " blocks into another model's torso");
	}

	// ---- a soft bone pressed and flattened --------------------------------------------------------------------------------------------------------

	private static final String SOFT_JSON = "{\"bones\": {\"pouchL\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4}}}";

	@Test
	void aSoftBoneIsPushedAwayAndFlattenedByAPlayerAndRecoversWhenItLeaves() throws Exception {
		BakedStrutModel model = dummy();
		Rig rig = new Rig(model, SOFT_JSON, 1);

		for (int i = 0; i < 30; i++)
			rig.frame(0, 64, 0, 0f);

		List<PhysicsInstance.Pickable> parts = new ArrayList<>();

		rig.instance.pickables(parts);

		PhysicsInstance.Pickable mass = parts.stream().filter(p -> p.part().equals("pouchL")).findFirst().orElseThrow();
		int bone = model.getBone("pouchL").orElseThrow().getIndex();

		assertEquals(1f, rig.pose.getScaleX(bone), 1e-6f, "at rest it is exactly as animated");
		assertEquals(0f, rig.instance.squishOf("pouchL"), 1e-6f);

		float[] restRot = rig.instance.rotationOf("pouchL");
		double pressX = mass.x() + 0.25;

		// a player pushes into the mass from the side: its axis is a quarter block from the mass, the sum of radii is 0.42
		for (int i = 0; i < 40; i++) {
			Bodies.nextFrame();
			Bodies.publishHitbox(50, Bodies.PLAYERS, pressX, mass.y() - 0.9, mass.z(), 1.8, 0.3);
			rig.frame(0, 64, 0, 0f);
		}

		float[] pressed = rig.instance.rotationOf("pouchL");
		float turn = Math.abs(pressed[0] - restRot[0]) + Math.abs(pressed[1] - restRot[1]) + Math.abs(pressed[2] - restRot[2]);

		assertTrue(turn > 0.02f, "the bone did not turn away from the press: " + turn);
		assertTrue(rig.instance.squishOf("pouchL") > 0.02f, "not flattened: " + rig.instance.squishOf("pouchL"));

		float sx = rig.pose.getScaleX(bone);
		float sy = rig.pose.getScaleY(bone);
		float sz = rig.pose.getScaleZ(bone);

		assertTrue(Math.abs(sx - 1f) + Math.abs(sy - 1f) + Math.abs(sz - 1f) > 0.02f, "the scale did not change");
		assertEquals(1.0, sx * sy * sz, 1e-3, "the volume is kept");

		// the player walks off: the bone springs back and the flattening goes
		for (int i = 0; i < 240; i++) {
			Bodies.nextFrame();
			rig.frame(0, 64, 0, 0f);
		}

		assertEquals(0f, rig.instance.squishOf("pouchL"), 1e-3f);
		assertEquals(1f, rig.pose.getScaleX(bone), 1e-3f);
		assertEquals(1f, rig.pose.getScaleY(bone), 1e-3f);
		assertEquals(1f, rig.pose.getScaleZ(bone), 1e-3f);
	}

	@Test
	void aBoneThatListsNoLayersIsNeverPressed() throws Exception {
		BakedStrutModel model = dummy();
		Rig rig = new Rig(model, "{\"bones\": {\"pouchL\": {\"preset\": \"jiggle-soft\", \"length\": 0.2}}}", 1);

		for (int i = 0; i < 30; i++)
			rig.frame(0, 64, 0, 0f);

		List<PhysicsInstance.Pickable> parts = new ArrayList<>();

		rig.instance.pickables(parts);

		PhysicsInstance.Pickable mass = parts.get(0);

		for (int i = 0; i < 30; i++) {
			Bodies.nextFrame();
			Bodies.publishHitbox(50, Bodies.PLAYERS, mass.x(), mass.y() - 0.9, mass.z(), 1.8, 0.3);
			rig.frame(0, 64, 0, 0f);
		}

		assertEquals(0f, rig.instance.squishOf("pouchL"), 1e-9f);

		float[] rot = rig.instance.rotationOf("pouchL");

		assertEquals(0f, Math.abs(rot[0]) + Math.abs(rot[1]) + Math.abs(rot[2]), 1e-4f, "nothing moved it");
	}

	// ---- spring bones against blocks ----------------------------------------------------------------------------------------------------------------

	private static final String WORLD_SOFT = "{\"bones\": {\"pouchL\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4, "
			+ "\"collidesWith\": [%s]}}}";

	/** Runs a soft bone with a wall just inside its mass for two seconds, and returns how far it turned and how flattened it got */
	private static float[] pressAgainstWall(BakedStrutModel model, String layers, boolean springSwitch) {
		Rig probe = new Rig(model, String.format(WORLD_SOFT, ""), 1);

		for (int i = 0; i < 30; i++)
			probe.frame(0, 64, 0, 0f);

		List<PhysicsInstance.Pickable> parts = new ArrayList<>();

		probe.instance.pickables(parts);

		PhysicsInstance.Pickable mass = parts.stream().filter(p -> p.part().equals("pouchL")).findFirst().orElseThrow();
		Rig rig = new Rig(model, String.format(WORLD_SOFT, layers), 2);
		double wall = mass.x() + 0.05;

		rig.instance.world((x0, y0, z0, x1, y1, z1, sink) -> sink.box(wall, mass.y() - 1, mass.z() - 1, wall + 1, mass.y() + 1, mass.z() + 1));
		PhysicsOptions.springWorldCollision(springSwitch);

		try {
			for (int i = 0; i < 120; i++)
				rig.frame(0, 64, 0, 0f);
		}
		finally {
			PhysicsOptions.springWorldCollision(true);
		}

		float[] rot = rig.instance.rotationOf("pouchL");

		return new float[] {Math.abs(rot[0]) + Math.abs(rot[1]) + Math.abs(rot[2]), rig.instance.squishOf("pouchL")};
	}

	@Test
	void aSpringBoneListingWorldIsPushedOffABlockAndFlattenedByIt() throws Exception {
		float[] result = pressAgainstWall(dummy(), "\"world\"", true);

		assertTrue(result[0] > 0.02f, "the bone did not turn away from the wall: " + result[0]);
		assertTrue(result[1] > 0.02f, "not flattened against the wall: " + result[1]);
	}

	@Test
	void aSpringBoneIgnoresBlocksUnlessItListsWorldAndTheSettingIsOn() throws Exception {
		BakedStrutModel model = dummy();

		assertEquals(0f, pressAgainstWall(model, "", true)[0], 1e-6f, "not listed: no collision, as before");
		assertEquals(0f, pressAgainstWall(model, "\"world\"", false)[0], 1e-6f, "the setting is off");
	}
}
