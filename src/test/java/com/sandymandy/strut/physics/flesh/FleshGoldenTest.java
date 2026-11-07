package com.sandymandy.strut.physics.flesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sandymandy.strut.physics.collision.Collider;
import com.sandymandy.strut.physics.config.PhysicsConfig;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The golden record the Blockbench plugin's TypeScript flesh is held to ({@code tests/fixtures/flesh.json} there is a copy of the file this test keeps): a cage
 * fitted to a ball of points, or a pair of them, carried through a scripted motion, with the swing and a few nodes after every step. The test fails when the
 * solver stops producing what the file says, so the two cannot drift silently. After a deliberate change, delete the file and run this test to record it again, then
 * copy it to the plugin and run its tests to see whether the port still agrees
 */
class FleshGoldenTest {
	private static final Path FILE = Path.of("src", "test", "resources", "golden", "flesh.json");
	private static final double H = FleshSolver.STEP;
	private static final int STEPS = 360;
	private static final int[] SHOWN = {0, 40, 80, 120, 161, 162};

	private static JsonArray vec(double... v) {
		JsonArray a = new JsonArray();

		for (double d : v)
			a.add(d);

		return a;
	}

	/** Points on a ball of radius {@code r} about {@code (cx, cy, cz)}, the way the plugin makes them */
	private static double[][] ball(double cx, double cy, double cz, double r, int count) {
		double[][] points = new double[count][];
		double golden = Math.PI * (3 - Math.sqrt(5));

		for (int i = 0; i < count; i++) {
			double y = 1 - 2.0 * (i + 0.5) / count;
			double ring = Math.sqrt(1 - y * y);
			double a = golden * i;

			points[i] = new double[] {cx + r * ring * Math.cos(a), cy + r * y, cz + r * ring * Math.sin(a)};
		}

		return points;
	}

	@SuppressWarnings("unchecked")
	private static JsonObject scenario(String entry, double[] centres) {
		PhysicsConfig config = PhysicsConfig.parse("{\"flesh\": {\"s\": " + entry + "}}", "test");

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());

		FleshParams params = config.flesh().get("s");
		FleshSolver.Region[] regions = new FleshSolver.Region[centres.length];
		List<Collider.Placed>[] none = new List[centres.length];
		List<Collider.Placed>[] noPress = new List[centres.length];

		for (int i = 0; i < centres.length; i++) {
			regions[i] = new FleshSolver.Region(FleshCage.fit(ball(centres[i], 1.3, -0.2, 0.12, 600), params.tip(), params.up(), new Matrix4f(), params));
			none[i] = List.of();
			noPress[i] = List.of();
		}

		JsonObject out = new JsonObject();
		JsonArray points = new JsonArray();
		JsonArray steps = new JsonArray();

		out.addProperty("entry", entry);
		out.add("centres", vec(centres));
		out.addProperty("count", 600);
		out.addProperty("radius", 0.12);

		for (int s = 0; s < STEPS; s++) {
			double t = s * H;
			double x = 0.3 * Math.sin(5 * t);
			double y = 0.15 * Math.sin(7 * t + 1);
			double z = 0.2 * Math.sin(3 * t);
			double angle = 1.2 * Math.sin(2 * t);
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			JsonArray frame = new JsonArray();

			for (FleshSolver.Region r : regions) {
				for (int i = 0; i < FleshCageGeometry.NODES; i++) {
					double rx = r.cage.rest[i * 3];
					double ry = r.cage.rest[i * 3 + 1];
					double rz = r.cage.rest[i * 3 + 2];

					r.target[i * 3] = cos * rx + sin * rz + x;
					r.target[i * 3 + 1] = ry + y;
					r.target[i * 3 + 2] = -sin * rx + cos * rz + z;
				}

				r.tipDirection[0] = cos * r.cage.tip[0] + sin * r.cage.tip[2];
				r.tipDirection[1] = r.cage.tip[1];
				r.tipDirection[2] = -sin * r.cage.tip[0] + cos * r.cage.tip[2];
				r.up[0] = cos * r.cage.up[0] + sin * r.cage.up[2];
				r.up[1] = r.cage.up[1];
				r.up[2] = -sin * r.cage.up[0] + cos * r.cage.up[2];
				r.animatedHeight = r.target[FleshCageGeometry.CENTRE * 3 + 1];
				// the targets of a new step are measured against: the game does this once a frame, the record once a step
				r.linksStale = true;
			}

			FleshSolver.step(regions, params, none, noPress, -100, H);

			for (FleshSolver.Region r : regions) {
				JsonObject one = new JsonObject();
				JsonArray nodes = new JsonArray();

				one.add("lump", vec(r.lump[0], r.lump[1], r.lump[2]));

				for (int n : SHOWN)
					nodes.add(vec(r.x[n * 3], r.x[n * 3 + 1], r.x[n * 3 + 2]));

				one.add("nodes", nodes);
				frame.add(one);
			}

			steps.add(frame);
		}

		JsonArray last = new JsonArray();

		for (FleshSolver.Region r : regions) {
			JsonArray all = new JsonArray();

			for (int i = 0; i < FleshCageGeometry.NODES * 3; i++)
				all.add(r.x[i]);

			last.add(all);
		}

		for (FleshSolver.Region r : regions) {
			JsonArray rest = new JsonArray();

			for (int i = 0; i < FleshCageGeometry.NODES * 3; i++)
				rest.add(r.cage.rest[i]);

			points.add(rest);
		}

		out.add("cageRest", points);
		out.add("steps", steps);
		out.add("final", last);

		return out;
	}

	private static JsonObject record() {
		JsonObject root = new JsonObject();

		root.add("single", scenario("{\"bones\": [\"b\"]}", new double[] {0.0}));
		root.add("pair", scenario("{\"bones\": [\"l\", \"r\"], \"preset\": \"seat\"}", new double[] {-0.13, 0.13}));
		root.add("zone", scenario("{\"bones\": [\"b\"], \"preset\": \"belly\", \"softCenter\": [0, 1.3, -0.2], \"softRadius\": 0.1, \"meat\": 1, \"tip\": \"-z\"}", new double[] {0.0}));

		return root;
	}

	private static void compare(String where, JsonElement expected, JsonElement actual) {
		if (expected.isJsonObject()) {
			for (var e : expected.getAsJsonObject().entrySet())
				compare(where + "." + e.getKey(), e.getValue(), actual.getAsJsonObject().get(e.getKey()));
		}
		else if (expected.isJsonArray()) {
			assertEquals(expected.getAsJsonArray().size(), actual.getAsJsonArray().size(), where);

			for (int i = 0; i < expected.getAsJsonArray().size(); i++)
				compare(where + "[" + i + "]", expected.getAsJsonArray().get(i), actual.getAsJsonArray().get(i));
		}
		else if (expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isNumber()) {
			assertEquals(expected.getAsDouble(), actual.getAsDouble(), 1e-9, where);
		}
		else {
			assertEquals(expected, actual, where);
		}
	}

	@Test
	void theSolverStillProducesTheRecordedResults() throws Exception {
		JsonObject now = record();

		if (!Files.exists(FILE)) {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, now.toString(), StandardCharsets.UTF_8);

			return;
		}

		compare("golden", JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)), now);
	}

	@Test
	void theRecordedMotionReallySwingsTheFlesh() {
		JsonObject now = record();
		double peak = 0;

		for (var step : now.getAsJsonObject("single").getAsJsonArray("steps")) {
			var lump = step.getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("lump");

			peak = Math.max(peak, Math.abs(lump.get(0).getAsDouble()) + Math.abs(lump.get(2).getAsDouble()));
		}

		assertTrue(peak > 0.01, "the lump swung: " + peak);
	}
}
