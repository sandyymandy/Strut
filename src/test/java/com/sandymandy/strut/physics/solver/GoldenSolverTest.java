package com.sandymandy.strut.physics.solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsOptions;

/**
 * The golden record the Blockbench plugin's TypeScript solvers are held to (`plugin/animation_utils/src/tests/physics/golden.test.ts` reads the same file):
 * a scripted motion through {@link SpringSolver} and {@link ChainSolver}, inputs and every step's result. This test fails when the Java solvers stop
 * producing what the file says, so the two cannot drift silently. After a deliberate solver change, delete the file and run this test to record it again,
 * then run the plugin's tests to see whether the port still agrees
 */
class GoldenSolverTest {
	private static final Path FILE = Path.of("src", "test", "resources", "golden", "solvers.json");

	private static JsonArray vec(double... v) {
		JsonArray a = new JsonArray();

		for (double d : v)
			a.add(d);

		return a;
	}

	private static JsonObject record() {
		JsonObject root = new JsonObject();

		// ---- a spring bone: thrown about for five seconds ----
		float[][] limit = {{-0.5f, 0.5f}, null, {-0.3f, 0.3f}};
		SpringParams sp = new SpringParams(1.7f, 0.24f, 1f, 0f, true, true, new Vector3f(0, -1, 0), 0.2f, limit, 1.5f, 1f, 1f, Set.of(), 0.1f, 0f);
		JsonObject spring = new JsonObject();
		JsonObject sparams = new JsonObject();

		sparams.addProperty("frequency", sp.frequency());
		sparams.addProperty("dampingRatio", sp.dampingRatio());
		sparams.add("tip", vec(0, -1, 0));
		sparams.addProperty("length", sp.length());
		sparams.addProperty("translationLimit", sp.translationLimit());

		JsonArray limits = new JsonArray();

		for (float[] l : limit)
			limits.add(l == null ? null : vec(l[0], l[1]));

		sparams.add("rotationLimit", limits);
		spring.add("params", sparams);

		SpringSolver.State state = new SpringSolver.State();
		Vector3f scratch = new Vector3f();
		JsonArray forces = new JsonArray();
		JsonArray results = new JsonArray();

		for (int i = 0; i < 300; i++) {
			Vector3f force = new Vector3f((float)(6 * Math.sin(i * 0.05)), (float)(-9.8 * Math.cos(i * 0.02)), (float)(4 * Math.cos(i * 0.031)));

			SpringSolver.step(state, sp, SpringSolver.STEP, force, scratch);
			forces.add(vec(force.x, force.y, force.z));

			JsonObject r = new JsonObject();

			r.add("rot", vec(state.rot[0], state.rot[1], state.rot[2]));
			r.add("rotVel", vec(state.rotVel[0], state.rotVel[1], state.rotVel[2]));
			r.add("pos", vec(state.pos[0], state.pos[1], state.pos[2]));
			results.add(r);
		}

		spring.add("forces", forces);
		spring.add("steps", results);
		root.add("spring", spring);

		// ---- a chain: a root that swings side to side above a floor ----
		ChainParams cp = new ChainParams(List.of("a", "b", "c"), 3f, 0.3f, 0.8f, 9.8f, 0.05f, 1.0f, 0.15f, 1f, Set.of("world"), 0.2f);
		JsonObject chain = new JsonObject();
		JsonObject cparams = new JsonObject();

		cparams.addProperty("frequency", cp.frequency());
		cparams.addProperty("dampingRatio", cp.dampingRatio());
		cparams.addProperty("inertia", cp.inertia());
		cparams.addProperty("gravity", cp.gravity());
		cparams.addProperty("radius", cp.radius());
		cparams.addProperty("maxAngle", cp.maxAngle());
		cparams.addProperty("friction", cp.friction());
		chain.add("params", cparams);
		chain.add("rest", vec(0.3, 0.3, 0.3));

		Collider.Placed floor = new Collider.Placed();

		floor.shape = Collider.Shape.BOX;
		floor.p[0] = 0;
		floor.p[1] = 0.67;
		floor.p[2] = 0;
		floor.half[0] = 3;
		floor.half[1] = 0.05;
		floor.half[2] = 3;
		floor.axes.identity();

		JsonObject box = new JsonObject();

		box.add("p", vec(0, 0.67, 0));
		box.add("half", vec(3, 0.05, 3));
		chain.add("box", box);

		ChainSolver.State cs = new ChainSolver.State(4);
		JsonArray anims = new JsonArray();
		JsonArray deltas = new JsonArray();
		JsonArray xs = new JsonArray();
		double previousX = 0;

		for (int i = 0; i < 300; i++) {
			double x = 0.6 * Math.sin(i * 0.06);
			double[][] anim = new double[4][3];
			JsonArray animJson = new JsonArray();

			for (int k = 0; k < 4; k++) {
				anim[k][0] = x;
				anim[k][1] = 1.6 - k * 0.3;
				anim[k][2] = 0.1 * Math.cos(i * 0.04);
				animJson.add(vec(anim[k][0], anim[k][1], anim[k][2]));
			}

			if (!cs.initialised)
				cs.snapTo(anim);

			double[] delta = {x - previousX, 0, 0};

			previousX = x;
			ChainSolver.step(cs, cp, SpringSolver.STEP, anim, new double[] {0.3, 0.3, 0.3}, delta, List.of(floor));
			anims.add(animJson);
			deltas.add(vec(delta[0], delta[1], delta[2]));

			JsonArray step = new JsonArray();

			for (double[] p : cs.x)
				step.add(vec(p[0], p[1], p[2]));

			xs.add(step);
		}

		chain.add("anim", anims);
		chain.add("delta", deltas);
		chain.add("steps", xs);
		root.add("chain", chain);

		PhysicsOptions.quality(PhysicsOptions.Quality.MEDIUM);
		root.add("curtain", recordCurtain());
		root.add("ball", recordBall());

		return root;
	}

	private static JsonObject clothJson(ClothParams cp, double[][] rest, int[] triangles, ClothSolver.State st, JsonArray anims, JsonArray deltas, JsonArray steps) {
		JsonObject o = new JsonObject();
		JsonObject params = new JsonObject();

		params.addProperty("stiffness", cp.stiffness());
		params.addProperty("bend", cp.bend());
		params.addProperty("damping", cp.damping());
		params.addProperty("inertia", cp.inertia());
		params.addProperty("gravity", cp.gravity());
		params.addProperty("radius", cp.radius());
		params.addProperty("anchor", cp.anchor());
		params.addProperty("friction", cp.friction());
		params.addProperty("iterations", cp.iterations());
		params.addProperty("volume", cp.volume());
		o.add("params", params);

		JsonArray restJson = new JsonArray();

		for (double[] r : rest)
			restJson.add(vec(r[0], r[1], r[2]));

		JsonArray tri = new JsonArray();

		for (int t : triangles)
			tri.add(t);

		JsonArray pins = new JsonArray();

		for (float pin : st.pin)
			pins.add(pin);

		o.add("rest", restJson);
		o.add("triangles", tri);
		o.add("pin", pins);
		o.add("anim", anims);
		o.add("delta", deltas);
		o.add("steps", steps);

		return o;
	}

	/** A curtain sewn along its top, swung side to side above a floor that its hem touches */
	private static JsonObject recordCurtain() {
		int cols = 5;
		int rows = 5;
		int n = (cols + 1) * (rows + 1);
		double[][] rest = new double[n][3];
		List<Integer> triangles = new ArrayList<>();

		for (int j = 0; j <= rows; j++) {
			for (int i = 0; i <= cols; i++) {
				rest[j * (cols + 1) + i] = new double[] {-0.4 + 0.8 * i / cols, 1.0 - 0.8 * j / rows, 0.3};

				if (i < cols && j < rows) {
					int a = j * (cols + 1) + i;
					int b = a + 1;
					int c = a + cols + 1;
					int d = c + 1;

					triangles.addAll(List.of(a, c, d, a, d, b));
				}
			}
		}

		int[] tri = triangles.stream().mapToInt(Integer::intValue).toArray();
		ClothParams cp = new ClothParams("c", -1, 0.9f, 0.15f, 0.6f, 0.6f, 9.8f, 0.02f, 0.1f, 0f, 1f, Set.of("world"), 0.3f, 6, 600, 0f, null, 0f, List.of());
		ClothSolver.Topology topo = ClothSolver.Topology.build(rest, tri);
		ClothSolver.State st = new ClothSolver.State(n);

		for (int k = 0; k < n; k++)
			st.pin[k] = rest[k][1] >= 1.0 - 0.1 * 0.8 - 1e-6 ? 1f : 0f;

		Collider.Placed floor = new Collider.Placed();

		floor.shape = Collider.Shape.BOX;
		floor.p[0] = 0;
		floor.p[1] = 0.2;
		floor.p[2] = 0.3;
		floor.half[0] = 3;
		floor.half[1] = 0.05;
		floor.half[2] = 3;
		floor.axes.identity();

		JsonArray anims = new JsonArray();
		JsonArray deltas = new JsonArray();
		JsonArray steps = new JsonArray();
		double previousX = 0;

		for (int i = 0; i < 240; i++) {
			double x = 0.5 * Math.sin(i * 0.07);
			double[][] anim = new double[n][3];
			JsonArray animJson = new JsonArray();

			for (int k = 0; k < n; k++) {
				anim[k] = new double[] {rest[k][0] + x, rest[k][1], rest[k][2] + 0.05 * Math.cos(i * 0.05)};
				animJson.add(vec(anim[k][0], anim[k][1], anim[k][2]));
			}

			if (!st.initialised)
				st.snapTo(anim);

			double[] delta = {x - previousX, 0, 0};

			previousX = x;
			ClothSolver.step(topo, st, cp, SpringSolver.STEP, anim, delta, List.of(floor));
			anims.add(animJson);
			deltas.add(vec(delta[0], delta[1], delta[2]));

			JsonArray step = new JsonArray();

			for (double[] p : st.x)
				step.add(vec(p[0], p[1], p[2]));

			steps.add(step);
		}

		JsonObject o = clothJson(cp, rest, tri, st, anims, deltas, steps);
		JsonObject box = new JsonObject();

		box.add("p", vec(0, 0.2, 0.3));
		box.add("half", vec(3, 0.05, 3));
		o.add("box", box);

		return o;
	}

	/** A closed ball with a soft middle that blends to sewn on, anchored, keeping its volume, shaken about */
	private static JsonObject recordBall() {
		int around = 12;
		int rows = 6;
		List<double[]> points = new ArrayList<>();

		points.add(new double[] {0, 0.72, 0.3});

		for (int j = 1; j < rows; j++) {
			double lat = Math.PI * j / rows;

			for (int i = 0; i < around; i++) {
				double angle = Math.PI * 2 * i / around;

				points.add(new double[] {Math.sin(angle) * Math.sin(lat) * 0.12, 0.6 + Math.cos(lat) * 0.12, 0.3 + Math.cos(angle) * Math.sin(lat) * 0.12});
			}
		}

		points.add(new double[] {0, 0.48, 0.3});

		int n = points.size();
		List<Integer> triangles = new ArrayList<>();

		for (int i = 0; i < around; i++) {
			int a = 1 + i;
			int b = 1 + (i + 1) % around;

			triangles.addAll(List.of(0, b, a));
		}

		for (int j = 1; j + 1 < rows; j++) {
			for (int i = 0; i < around; i++) {
				int a = 1 + (j - 1) * around + i;
				int b = 1 + (j - 1) * around + (i + 1) % around;
				int c = a + around;
				int d = b + around;

				triangles.addAll(List.of(a, b, d, a, d, c));
			}
		}

		for (int i = 0; i < around; i++) {
			int a = 1 + (rows - 2) * around + i;
			int b = 1 + (rows - 2) * around + (i + 1) % around;

			triangles.addAll(List.of(a, b, n - 1));
		}

		double[][] rest = points.toArray(new double[0][]);
		int[] tri = triangles.stream().mapToInt(Integer::intValue).toArray();
		ClothParams cp = new ClothParams("b", -1, 0.95f, 0f, 1.2f, 1f, 1.5f, 0.02f, 0f, 4f, 1f, Set.of(), 0.3f, 4, 600, 0.9f, new float[] {0f, 0.6f, 0.18f}, 0.2f, List.of());
		ClothSolver.Topology topo = ClothSolver.Topology.build(rest, tri);
		ClothSolver.State st = new ClothSolver.State(n);

		for (int k = 0; k < n; k++) {
			double d = Math.sqrt(Math.pow(rest[k][0] - 0, 2) + Math.pow(rest[k][1] - 0.6, 2) + Math.pow(rest[k][2] - 0.18, 2));
			double t = Math.min(1.0, d / 0.2);

			st.pin[k] = (float)(t * t * (3 - 2 * t));
		}

		JsonArray anims = new JsonArray();
		JsonArray deltas = new JsonArray();
		JsonArray steps = new JsonArray();
		double previousX = 0;

		for (int i = 0; i < 240; i++) {
			double x = 0.4 * Math.sin(i * 0.09);
			double y = 0.1 * Math.sin(i * 0.13);
			double[][] anim = new double[n][3];
			JsonArray animJson = new JsonArray();

			for (int k = 0; k < n; k++) {
				anim[k] = new double[] {rest[k][0] + x, rest[k][1] + y, rest[k][2]};
				animJson.add(vec(anim[k][0], anim[k][1], anim[k][2]));
			}

			if (!st.initialised)
				st.snapTo(anim);

			double[] delta = {x - previousX, 0, 0};

			previousX = x;
			ClothSolver.step(topo, st, cp, SpringSolver.STEP, anim, delta, List.of());
			anims.add(animJson);
			deltas.add(vec(delta[0], delta[1], delta[2]));

			JsonArray step = new JsonArray();

			for (double[] p : st.x)
				step.add(vec(p[0], p[1], p[2]));

			steps.add(step);
		}

		return clothJson(cp, rest, tri, st, anims, deltas, steps);
	}

	private static void compare(String path, JsonElement expected, JsonElement actual) {
		if (expected.isJsonArray()) {
			assertEquals(expected.getAsJsonArray().size(), actual.getAsJsonArray().size(), path);

			for (int i = 0; i < expected.getAsJsonArray().size(); i++)
				compare(path + "[" + i + "]", expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i));
		}
		else if (expected.isJsonObject()) {
			for (String key : expected.getAsJsonObject().keySet())
				compare(path + "." + key, expected.getAsJsonObject().get(key), actual.getAsJsonObject().get(key));
		}
		else if (expected.isJsonNull()) {
			assertTrue(actual.isJsonNull(), path);
		}
		else {
			assertEquals(expected.getAsDouble(), actual.getAsDouble(), 1e-6, path);
		}
	}

	@Test
	void theSolversStillProduceTheRecordedResults() throws Exception {
		JsonObject now = record();

		if (!Files.exists(FILE)) {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, now.toString(), StandardCharsets.UTF_8);

			return;
		}

		compare("golden", JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)), now);
	}

	@Test
	void theRecordedMotionReallyExercisesLimitsAndContact() {
		JsonObject now = record();
		double peak = 0;
		double lowest = Double.MAX_VALUE;

		for (var step : now.getAsJsonObject("spring").getAsJsonArray("steps"))
			peak = Math.max(peak, Math.abs(step.getAsJsonObject().getAsJsonArray("rot").get(0).getAsDouble()));

		// the floor top is at 0.72, so a particle resting on it sits at 0.77 (its radius above); the animated tip is at 0.70, so the tip is pressed on it; anything lower went through, and a record that never gets
		// near it proves nothing about collision
		double nearest = Double.MAX_VALUE;

		for (var step : now.getAsJsonObject("chain").getAsJsonArray("steps")) {
			for (var p : step.getAsJsonArray()) {
				double y = p.getAsJsonArray().get(1).getAsDouble();

				lowest = Math.min(lowest, y);
				nearest = Math.min(nearest, Math.abs(y - 0.77));
			}
		}

		assertTrue(peak > 0.1, "the spring swung: " + peak);
		assertTrue(peak <= 0.5 + 1e-6, "and never past its limit");
		assertTrue(lowest >= 0.77 - 1e-6, "no particle went through the floor: " + lowest);
		assertTrue(nearest < 0.02, "the chain reached the floor: " + nearest);
	}
}
