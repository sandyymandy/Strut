package com.sandymandy.strut.physics.tools;

import java.util.function.Consumer;
import net.minecraft.util.Identifier;

import java.util.List;
import com.sandymandy.strut.physics.collision.Bodies;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import com.sandymandy.strut.physics.config.PhysicsConfigs;

/**
 * The in-game test scenarios: one per physics system, each a ready-made physics config for Strut's own test dummy plus a short instruction of what to do and what
 * you should see (every new physics system ships a scenario). They are applied as an override to a model's config, so nothing on disk
 * changes, and they show in the cursor tool's buttons and in {@code /strutphysics test}
 * <p>
 * A new system adds a scenario here in the same change that adds the system, and the test that loads every scenario against the real test dummy model keeps
 * them honest: a scenario that names a bone the model lacks, or JSON that warns, fails the build
 */
public final class PhysicsScenarios {
	/** The model every scenario is written for: Strut's test dummy */
	static final Identifier DUMMY = Identifier.of("strut", "strut/models/entity/test_dummy");

	/**
	 * @param setup What to do to the entity's controls when the scenario starts, or null: the ragdoll scenario lets the body go, so the test that shakes every
	 *              scenario has something to shake
	 */
	public record Scenario(String id, String title, String system, List<String> steps, List<String> expect, String json, Consumer<PhysicsControls> setup) {
		Scenario(String id, String title, String system, List<String> steps, List<String> expect, String json) {
			this(id, title, system, steps, expect, json, null);
		}
	}

	private PhysicsScenarios() {}

	private static final String ARM_CHAINS = "\"chains\": {"
			+ "\"armL\": {\"bones\": [\"armL\", \"lowerArmL\"], \"preset\": \"tail\", \"frequency\": 1.3, \"damping\": 0.18, \"tipLength\": 0.3, \"radius\": 0.06, \"maxAngle\": 75},"
			+ "\"armR\": {\"bones\": [\"armR\", \"lowerArmR\"], \"preset\": \"tail\", \"frequency\": 1.3, \"damping\": 0.18, \"tipLength\": 0.3, \"radius\": 0.06, \"maxAngle\": 75}}";

	private static final List<Scenario> ALL = List.of(
			new Scenario("springs", "Spring bones", "springs, inertia",
					List.of("Right-drag the dummy quickly along the ground: it slides with the cursor.",
							"Stop suddenly, and turn it by dragging in a circle.",
							"Left-drag one of the yellow dots (on the chest, the jowls) to pull it, then let go."),
					List.of("The chest, jowls and belly lag behind when the dummy speeds up, swing forward when it stops, and settle in about a second.",
							"A dot you hold follows the cursor; let go and it springs back and settles."),
					"{\"bones\": {"
							+ "\"pouchL\": {\"preset\": \"jiggle-soft\", \"frequency\": 1.2, \"damping\": 0.12, \"length\": 0.2, \"limit\": {\"x\": [-40, 40], \"y\": [-40, 40], \"z\": [-40, 40]}},"
							+ "\"pouchR\": {\"preset\": \"jiggle-soft\", \"frequency\": 1.2, \"damping\": 0.12, \"length\": 0.2, \"limit\": {\"x\": [-40, 40], \"y\": [-40, 40], \"z\": [-40, 40]}},"
							+ "\"jowlL\": {\"preset\": \"jiggle-soft\", \"frequency\": 1.2, \"damping\": 0.12, \"length\": 0.15},"
							+ "\"jowlR\": {\"preset\": \"jiggle-soft\", \"frequency\": 1.2, \"damping\": 0.12, \"length\": 0.15}}}"),

			new Scenario("chains", "Chains", "chains",
					List.of("Right-drag the dummy back and forth and in circles.",
							"Left-drag a yellow dot on an arm and let go."),
					List.of("The arms swing like ropes: they trail behind the body, sway on turns, and hang down again when it stops.",
							"A held arm particle follows the cursor and the arm bends with it; segments never stretch."),
					"{\"autoColliders\": false, " + ARM_CHAINS + "}"),

			new Scenario("colliders", "Colliders", "colliders on bones",
					List.of("Turn on debug lines (button) to see the chains in red.",
							"Left-drag an arm particle straight into the torso or the head, and hold it there."),
					List.of("The arm cannot enter the torso or the head: it slides around them.",
							"Swinging the dummy fast never pushes an arm through the body."),
					"{\"autoColliders\": false, " + ARM_CHAINS + ", \"colliders\": ["
							+ "{\"bone\": \"torso\", \"shape\": \"capsule\", \"from\": [0, -3, 0], \"to\": [0, 10, 0], \"radius\": 2.5, \"layer\": \"body\"},"
							+ "{\"bone\": \"head\", \"shape\": \"sphere\", \"center\": [0, 4, 0], \"radius\": 4.5, \"layer\": \"body\"}]}"),

			new Scenario("world", "World collision", "blocks",
					List.of("Place a few blocks and a slab and a stair on the ground next to the dummy.",
							"Right-drag the dummy so an arm swings into a block; hold Shift while dragging to lift it, and lower it onto a block so the arms drape over the top.",
							"Turn slow motion on (button) to watch it."),
					List.of("The arms stop at block faces, rest on tops and lie on slabs and stairs by their real shapes, and do not slide away forever (friction).",
							"Nothing goes through a wall, even dragged fast."),
					"{" + ARM_CHAINS.replace("\"maxAngle\": 75}", "\"maxAngle\": 90, \"friction\": 0.35}") + ", \"autoColliders\": false}"),

				new Scenario("bodies", "Bodies pressing on each other", "other models, players, squish",
						List.of("Spawn a second test dummy next to the first (both take this scenario).",
								"Right-drag one dummy into the other so an arm swings against its torso or head.",
								"Walk your own body into a dummy's arm or against its chest.",
								"Run /strutphysics collide players false, walk into it again, then set it back to true."),
						List.of("An arm that swings into the other dummy slides around its torso and head instead of going through.",
								"Your body pushes the arms aside, and presses the chest (it flattens along the push and bulges across it, then springs back).",
								"With player collision off, you pass through without moving anything."),
						"{\"autoColliders\": false, " + ARM_CHAINS.replace("\"maxAngle\": 75}", "\"maxAngle\": 90, \"collidesWith\": [\"body\", \"world\", \"other\", \"players\", \"mobs\"]}") + ", \"colliders\": ["
								+ "{\"bone\": \"torso\", \"shape\": \"capsule\", \"from\": [0, -3, 0], \"to\": [0, 10, 0], \"radius\": 2.5, \"layer\": \"body\"},"
								+ "{\"bone\": \"head\", \"shape\": \"sphere\", \"center\": [0, 4, 0], \"radius\": 4.5, \"layer\": \"body\"},"
								+ "{\"bone\": \"armL\", \"shape\": \"capsule\", \"from\": [0, 0, 0], \"to\": [0, -5, 0], \"radius\": 1.6, \"layer\": \"limb\"},"
								+ "{\"bone\": \"lowerArmL\", \"shape\": \"capsule\", \"from\": [0, 0, 0], \"to\": [0, -5, 0], \"radius\": 1.6, \"layer\": \"limb\"},"
								+ "{\"bone\": \"armR\", \"shape\": \"capsule\", \"from\": [0, 0, 0], \"to\": [0, -5, 0], \"radius\": 1.6, \"layer\": \"limb\"},"
								+ "{\"bone\": \"lowerArmR\", \"shape\": \"capsule\", \"from\": [0, 0, 0], \"to\": [0, -5, 0], \"radius\": 1.6, \"layer\": \"limb\"}],"
								+ "\"bones\": {"
								+ "\"pouchL\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4},"
								+ "\"pouchR\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4}}}"),

				new Scenario("springworld", "Spring bones and blocks", "spring bones collide with blocks",
						List.of("Place a stone block or a wall right next to the dummy's chest, and one beside a jowl.",
								"Right-drag the dummy sideways so its chest presses into the block (Shift lifts it).",
								"Turn Spring bones collide with blocks off in Settings, and repeat."),
						List.of("The chest and jowls are pushed back by the block instead of sinking into it, and flatten a little against it.",
								"With the setting off they pass through the block as before."),
						"{\"bones\": {"
								+ "\"pouchL\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4, \"collidesWith\": [\"world\"]},"
								+ "\"pouchR\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4, \"collidesWith\": [\"world\"]},"
								+ "\"jowlL\": {\"preset\": \"jiggle-soft\", \"frequency\": 1.2, \"damping\": 0.12, \"length\": 0.15, \"radius\": 0.08, \"collidesWith\": [\"world\"]},"
								+ "\"jowlR\": {\"preset\": \"jiggle-soft\", \"frequency\": 1.2, \"damping\": 0.12, \"length\": 0.15, \"radius\": 0.08, \"collidesWith\": [\"world\"]}}}"),

				new Scenario("selfcollide", "Self collision (auto colliders)", "self collision: springs and chains against their own model",
						List.of("Turn on debug lines. No colliders are authored: the shapes come from the model's own cubes (a few cubes: a box each; a detailed bone: one low-poly capsule or box).",
								"Right-drag the dummy quickly so the arms swing across the body, and drag an arm particle (yellow) into the other arm or the legs.",
								"Left-drag a chest dot sideways into the other side of the chest or an arm."),
						List.of("An arm cannot pass through the other arm or the legs, and the chest is pushed off the arm that presses it, and flattens.",
								"Nothing is pushed while the dummy stands still: shapes a part already overlaps at rest (its own torso, its parent bones) are ignored."),
						"{" + ARM_CHAINS.replace("\"maxAngle\": 75}", "\"maxAngle\": 90}") + ", \"bones\": {"
								+ "\"pouchL\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4},"
								+ "\"pouchR\": {\"preset\": \"soft-bone\", \"frequency\": 1.6, \"damping\": 0.2, \"length\": 0.2, \"radius\": 0.12, \"squish\": 0.4}}}"),

				new Scenario("ragdoll", "Ragdoll", "ragdoll",
						List.of("The dummy lets go as soon as this loads and falls to the ground.",
								"Left-drag any yellow dot (a joint) to pick the body up by it, and let go to drop it.",
								"Press Stand up (or /strutphysics ragdoll stand): the muscle comes back and it gets to its feet as the animation.",
								"Try Weak muscle (/strutphysics ragdoll weak): it stays upright but sags and gets pushed about. Press Shove to hit it."),
						List.of("It drops, crumples and lies on the ground. Its limbs keep their length, its knees and elbows do not fold through themselves, and nothing goes through the floor.",
								"A held joint carries the whole body, hanging from it.",
								"Standing up is smooth, ending exactly in the animation. A weak-muscled body wobbles and recovers when shoved."),
						"{\"autoColliders\": false, \"ragdoll\": {\"bones\": [\"upperBody\", \"torso\", \"armR\", \"lowerArmR\", \"armL\", \"lowerArmL\", \"head\", \"legR\", \"shinR\", \"legL\", \"shinL\"],"
								+ " \"parents\": {\"legR\": \"upperBody\", \"legL\": \"upperBody\"}}}",
						controls -> controls.ragdoll(0f)),

				new Scenario("cloth", "Cloth", "cloth",
						List.of("A skirt of mesh vertices is put on the dummy's hips for this scenario (the dummy is made of cubes, and cloth needs a mesh).",
								"Right-drag the dummy back and forth and in circles, and stop suddenly.",
								"Left-drag a yellow dot on the skirt, pull it out and let go. Drag one into a leg.",
								"Turn debug lines on to see the cloth's edges in blue."),
						List.of("The waistband stays on the hips; the rest of the skirt lags behind when the dummy speeds up, swings forward when it stops, and settles in a second or two.",
								"A held dot follows the cursor and the cloth is pulled after it; its edges keep their length.",
								"The skirt drapes over the thighs and is pushed out of the legs and the body, not through them.",
								"Turning cloth off in Settings draws the skirt as modelled; nothing else changes."),
						"{\"cloth\": {\"skirt\": {\"bone\": \"hip\", \"preset\": \"cloth\", \"pinHeight\": 0.4, \"anchor\": 0.6, \"radius\": 0.025, \"iterations\": 8}}}"),

				new Scenario("flesh", "Flesh and jelly", "flesh and jelly",
						List.of("Two soft balls are put at the dummy hips for this scenario (the dummy is made of cubes, and flesh needs a mesh).",
								"Right-drag the dummy about and stop suddenly.",
								"Left-drag a yellow dot on a ball, pull it out and let go. Walk your own body into a ball, or put another dummy against it.",
								"Turn debug lines on to see the edges in blue."),
						List.of("The balls wobble when the dummy speeds up and stops, and spring back to their shape: they are jelly.",
								"A held dot stretches the ball, which gets thinner across as it gets longer; pressed in, it bulges at the sides (it keeps its volume).",
								"Your body and other dummies press into them instead of passing through."),
						"{\"flesh\": {\"ball\": {\"bone\": \"hip\", \"preset\": \"jelly\"}}}")
	);

	static List<Scenario> all() {
		return ALL;
	}

	static Scenario byId(String id) {
		for (Scenario scenario : ALL) {
			if (scenario.id().equals(id))
				return scenario;
		}

		return null;
	}

	/** Makes {@code scenario} the physics config of the model with resource id {@code stem} */
	static void apply(Identifier stem, Scenario scenario) {
		// the prop first: the new config builds its cloth from the mesh as soon as the next frame draws
		DebugSkirt.sync(stem, propOf(scenario));
		PhysicsConfigs.override(stem, PhysicsConfig.parse(scenario.json(), "scenario " + scenario.id()));
	}

	/** Which test prop the scenario needs on the dummy (see {@link DebugSkirt}), or null */
	static String propOf(Scenario scenario) {
		return scenario.id().equals("cloth") ? DebugSkirt.DRESS : scenario.id().equals("flesh") ? DebugSkirt.JELLY : null;
	}

	/** Runs the scenario's start-up on an entity's controls (nothing for most scenarios) */
	static void start(Scenario scenario, PhysicsControls controls) {
		if (scenario.setup() != null)
			scenario.setup().accept(controls);
	}

	/** Puts the model back to its own config (a sidecar, its file, or none) */
	static void clear(Identifier stem) {
		DebugSkirt.sync(stem, (String)null);
		PhysicsConfigs.clearOverride(stem);
	}
}
