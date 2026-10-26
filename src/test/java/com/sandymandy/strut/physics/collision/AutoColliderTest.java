package com.sandymandy.strut.physics.collision;

import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.loading.binary.BinaryModelBaker;
import com.sandymandy.strut.loading.binary.BinaryModelReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.PhysicsInstance;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.tools.PhysicsControls;

/**
 * Collision shapes made from the model's own geometry, so a jiggle bone or chain collides with the rest of its model with no authored colliders: what they
 * are, that a detailed bone is reduced to one low-poly proxy, and which of them a part leaves out
 */
class AutoColliderTest {
	private static BakedStrutModel dummy() throws Exception {
		Path file = Path.of("src", "main", "resources", "assets", "strut", "strut", "models", "entity", "test_dummy.strutmodel");

		Assumptions.assumeTrue(Files.exists(file), "needs Strut's test dummy model next to the module");

		return BinaryModelBaker.bake(BinaryModelReader.read(Files.readAllBytes(file), "test_dummy"), "test_dummy");
	}

	@Test
	void theDummyGetsShapesOnRealBonesWithinItsOwnSize() throws Exception {
		BakedStrutModel model = dummy();
		List<Collider> shapes = AutoColliders.generate(model, AutoColliders.Options.DEFAULT);

		assertFalse(shapes.isEmpty());
		assertTrue(shapes.size() <= AutoColliders.Options.DEFAULT.maxShapes());

		for (Collider c : shapes) {
			assertTrue(model.getBone(c.bone()).isPresent(), c.bone());
			assertEquals("body", c.layer());
			assertTrue(Math.abs(c.a().x) < 64 && Math.abs(c.a().y) < 64 && Math.abs(c.a().z) < 64, c.bone() + " is far from its own pivot: " + c.a());
		}
	}

	@Test
	void aDetailedBoneIsReducedToOneProxyAndAFewCubesKeepTheirOwnBoxes() throws Exception {
		BakedStrutModel model = dummy();
		Map<String, Integer> perBone = new HashMap<>();

		for (Collider c : AutoColliders.generate(model, new AutoColliders.Options(null, 0, List.of(), "body", 1000)))
			perBone.merge(c.bone(), 1, Integer::sum);

		// maxCubes 0 simplifies every bone: never more than one shape each
		perBone.forEach((bone, count) -> assertEquals(1, count, bone));

		Map<String, Integer> exact = new HashMap<>();

		for (Collider c : AutoColliders.generate(model, new AutoColliders.Options(null, 1000, List.of(), "body", 1000)))
			exact.merge(c.bone(), 1, Integer::sum);

		for (GroupBoneDefinition bone : model.flatBones()) {
			if (bone != null && exact.containsKey(bone.getName()) && bone.getMeshes().isEmpty())
				assertTrue(exact.get(bone.getName()) <= bone.getCubes().size(), bone.getName());
		}
	}

	@Test
	void excludePatternsAndTheShapeCapApply() throws Exception {
		BakedStrutModel model = dummy();
		List<Collider> all = AutoColliders.generate(model, new AutoColliders.Options(null, 3, List.of(), "body", 1000));

		assertFalse(all.isEmpty());

		String first = all.get(0).bone();
		List<Collider> without = AutoColliders.generate(model, new AutoColliders.Options(null, 3, List.of(first), "body", 1000));

		assertTrue(without.stream().noneMatch(c -> c.bone().equals(first)), "the excluded bone has no shape");
		assertEquals(Math.min(2, all.size()), AutoColliders.generate(model, new AutoColliders.Options(null, 3, List.of(), "tuft", 2)).size(), "capped, the biggest first");
		assertTrue(AutoColliders.generate(model, new AutoColliders.Options(null, 3, List.of(), "tuft", 2)).stream().allMatch(c -> c.layer().equals("tuft")));
	}

	@Test
	void aPartLeavesOutGeneratedShapesOnItsOwnBonesParentsAndChildrenButNeverAuthoredOnes() throws Exception {
		BakedStrutModel model = dummy();
		List<Collider> generated = AutoColliders.generate(model, AutoColliders.Options.DEFAULT);
		GroupBoneDefinition part = model.getBone("pouchL").orElseThrow();
		// an authored collider on the torso, which is an ancestor of the pouch: it stays
		Collider authored = new Collider("torso", Collider.Shape.SPHERE, new Vector3f(), new Vector3f(), 1f, "body");
		ColliderRig rig = new ColliderRig(model, List.of(authored), generated, new ArrayList<>());
		boolean[] skip = rig.relatedMask(part);

		assertEquals(rig.count(), skip.length);
		assertFalse(skip[0], "the authored one is never left out");

		int skipped = 0;
		int kept = 0;

		for (int i = 1; i < skip.length; i++) {
			if (skip[i])
				skipped++;
			else
				kept++;
		}

		assertTrue(skipped > 0, "the pouch's own ancestors are left out");
		assertTrue(kept > 0, "the rest of the body is still there to collide with");
	}

	@Test
	void theAutoOptionParsesAndLayers() {
		PhysicsConfig off = PhysicsConfig.parse("{\"autoColliders\": false, \"bones\": {\"a\": {}}}", "t");
		PhysicsConfig tuned = PhysicsConfig.parse("{\"autoColliders\": {\"maxCubes\": 1, \"exclude\": [\"hair*\"], \"layer\": \"body\", \"maxShapes\": 5}}", "t");
		PhysicsConfig bad = PhysicsConfig.parse("{\"autoColliders\": {\"bogus\": 1}}", "t");

		assertTrue(off.warnings().isEmpty());
		assertEquals(Boolean.FALSE, off.auto().enabled());
		assertEquals(1, tuned.auto().maxCubes());
		assertEquals(List.of("hair*"), tuned.auto().exclude());
		assertEquals(5, tuned.auto().maxShapes());
		assertFalse(bad.warnings().isEmpty());
		assertNull(PhysicsConfig.parse("{}", "t").auto());
		assertEquals(Boolean.FALSE, PhysicsConfig.parse("{\"autoColliders\": true}", "t").overlaid(off).auto().enabled(), "an overlay's setting wins");
	}

	private static float[] run(BakedStrutModel model, String json) {
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();

		Pose animated = modelInstance.renderedPose();
		Pose pose = new Pose(animated.boneCount());
		PhysicsInstance instance = new PhysicsInstance(model, PhysicsConfig.parse(json, "t"));

		instance.entityId(1);

		for (int i = 0; i < 90; i++) {
			pose.copyFrom(animated);
			instance.update(model, animated, pose, new PhysicsInstance.Sample(0, 64, 0, 0f, i / 60.0), new PhysicsControls());
		}

		float[] rot = instance.rotationOf("pouchL");

		return new float[] {Math.abs(rot[0]) + Math.abs(rot[1]) + Math.abs(rot[2]), instance.squishOf("pouchL")};
	}

	private static final String BONE = "\"pouchL\": {\"preset\": \"jiggle-soft\", \"length\": 0.2, \"radius\": 0.15, \"squish\": 0.5, \"collidesWith\": [\"body\"]}";

	@Test
	void aSpringBoneListingBodyIsPushedByAnAuthoredShapeOfItsOwnModel() throws Exception {
		// a big authored ball on the torso with the pouch inside it: an authored shape is never left out, so it pushes the bone outward
		float[] pressed = run(dummy(), "{\"autoColliders\": false, \"colliders\": [{\"bone\": \"torso\", \"shape\": \"sphere\", \"center\": [0, 4, 0], \"radius\": 6, \"layer\": \"body\"}], \"bones\": {" + BONE + "}}");

		assertTrue(pressed[0] > 1e-3f || pressed[1] > 1e-3f, "the bone was not pushed: " + pressed[0] + ", " + pressed[1]);
	}

	@Test
	void generatedShapesAlreadyTouchingTheBoneAtRestDoNotPushOrSquishItSoAnIdleModelIsExactlyAsAnimated() throws Exception {
		float[] idle = run(dummy(), "{\"bones\": {" + BONE + "}}");

		assertEquals(0f, idle[0], 1e-4f, "an idle model is not pushed by its own body");
		assertEquals(0f, idle[1], 1e-6f);
	}

	@Test
	void perBoneModesTurnABoneOffForceItsCubesOrReduceItToOneProxyAndOverrideTheExcludes() throws Exception {
		BakedStrutModel model = dummy();
		// the torso has one cube, the tuft six
		Function<Map<String, String>, Map<String, Integer>> count = modes -> {
			Map<String, Integer> perBone = new HashMap<>();

			for (Collider c : AutoColliders.generate(model, new AutoColliders.Options(null, 3, List.of(), "body", 1000, modes)))
				perBone.merge(c.bone(), 1, Integer::sum);

			return perBone;
		};

		assertTrue(count.apply(Map.of()).containsKey("torso"));
		assertFalse(count.apply(Map.of("torso", "off")).containsKey("torso"), "off");
		// the tuft has six cubes: more than three, so one proxy by default
		assertEquals(1, count.apply(Map.of()).get("tuft"), "a detailed bone is one proxy by default");
		assertTrue(count.apply(Map.of("tuft", "exact")).get("tuft") > 1, "exact gives every cube its box");
		assertEquals(1, count.apply(Map.of("torso", "simple")).get("torso"));

		// armor is left out by the built-in rule, and a mode brings it back
		String armor = model.flatBones().length > 0 ? Arrays.stream(model.flatBones()).filter(b -> b != null && b.getName().startsWith("armor") && !b.getCubes().isEmpty()).map(GroupBoneDefinition::getName).findFirst().orElse(null) : null;

		Assumptions.assumeTrue(armor != null);
		assertFalse(count.apply(Map.of()).containsKey(armor));
		assertTrue(count.apply(Map.of(armor, "simple")).containsKey(armor));
	}

	@Test
	void aBoneWithAuthoredShapesGetsNoMadeOnesAndTheModesParse() throws Exception {
		BakedStrutModel model = dummy();

		assertTrue(AutoColliders.generate(model, AutoColliders.Options.DEFAULT, Set.of("torso")).stream().noneMatch(c -> c.bone().equals("torso")));

		PhysicsConfig config = PhysicsConfig.parse("{\"autoColliders\": {\"bones\": {\"torso\": \"off\", \"belly\": \"exact\", \"x\": \"weird\"}}}", "t");

		assertEquals(Map.of("torso", "off", "belly", "exact"), config.auto().modes());
		assertEquals(1, config.warnings().size(), config.warnings().toString());
	}

	@Test
	void aTurnedBoxPushesOutAlongItsOwnAxes() throws Exception {
		BakedStrutModel model = dummy();
		// a flat box on the root bone, turned 90 degrees about z: its thin side now faces x
		Collider turned = new Collider("body", Collider.Shape.BOX, new Vector3f(), new Vector3f(16, 1, 16), 0f, "body", new Vector3f(0, 0, 90));
		ColliderRig rig = new ColliderRig(model, List.of(turned), new ArrayList<>());
		ModelInstance modelInstance = new ModelInstance();

		modelInstance.poseFor(model);
		modelInstance.resetPoseHistory();
		rig.prepare(modelInstance.renderedPose());

		List<Collider.Placed> placed = new ArrayList<>();

		rig.place(0, 0, 0, 180f, null, placed);

		Collider.Placed box = placed.get(0);
		// a point a quarter block up from the centre is inside the turned box (it is half a block tall that way now) and leaves sideways
		double[] up = {box.p[0], box.p[1] + 0.25, box.p[2]};

		assertTrue(Collider.pushOut(box, up, 0.01));
		assertEquals(box.p[1] + 0.25, up[1], 1e-5, "not pushed up: the box is thin along x now");
		assertTrue(Math.abs(up[0] - box.p[0]) > 0.03, "pushed out sideways");
	}

	@Test
	void aTurnedBoxParsesItsRotation() {
		PhysicsConfig config = PhysicsConfig.parse("{\"colliders\": [{\"bone\": \"a\", \"shape\": \"box\", \"center\": [0, 0, 0], \"size\": [2, 2, 2], \"rotation\": [10, 20, 30]}]}", "t");

		assertEquals(new Vector3f(10, 20, 30), config.colliders().get(0).rotation());
		assertTrue(config.warnings().isEmpty());
	}

	@Test
	void aPartsAllowAndDenyListsChooseWhichOfItsOwnModelsBonesItFeels() throws Exception {
		BakedStrutModel model = dummy();
		// a forearm chain would feel the made shapes of most of the body
		List<Collider> made = AutoColliders.generate(model, AutoColliders.Options.DEFAULT);
		ColliderRig rig = new ColliderRig(model, List.of(), made, new ArrayList<>());
		GroupBoneDefinition arm = model.getBone("lowerArmL").orElseThrow();
		Function<PhysicsConfig.BoneFilter, Set<String>> felt = filter -> {
			boolean[] skip = rig.mask(filter, arm);
			Set<String> bones = new HashSet<>();

			for (int i = 0; i < skip.length; i++) {
				if (!skip[i])
					bones.add(made.get(i).bone());
			}

			return bones;
		};

		Set<String> all = felt.apply(PhysicsConfig.BoneFilter.NONE);

		// the torso is the forearm's ancestor, so its made shape is left out anyway; the hip and the head are not
		assertTrue(all.contains("hip") && all.contains("head"), all.toString());
		assertEquals(Set.of("hip"), felt.apply(new PhysicsConfig.BoneFilter(List.of("hip"), List.of())), "only the hip");
		assertFalse(felt.apply(new PhysicsConfig.BoneFilter(List.of(), List.of("head"))).contains("head"), "everything but the head");
		assertTrue(felt.apply(new PhysicsConfig.BoneFilter(List.of(), List.of("leg*"))).stream().noneMatch(b -> b.startsWith("leg")), "a pattern");

		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"pouchL\": {\"ignoreBones\": [\"armL\"]}}, \"chains\": {\"tuft\": {\"bones\": [\"tuft\"], \"collideBones\": [\"torso\"]}}}", "t");

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());
		assertEquals(List.of("armL"), config.filter("pouchL").ignore());
		assertEquals(List.of("torso"), config.filter("tuft").only());
		assertTrue(config.filter("nothing").allows("anything"));
	}
}
