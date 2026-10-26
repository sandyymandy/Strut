package com.sandymandy.strut.physics.fluid;

import com.google.gson.JsonParser;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.tools.FluidCommands;

/**
 * How the liquid is started: the cone particles leave in, and the preset files
 */
class FluidEmitTest {
	private static double dot(float[] v, double dx, double dy, double dz) {
		double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);

		return (v[0] * dx + v[1] * dy + v[2] * dz) / len;
	}

	@Test
	@DisplayName("spread 0 is the direction itself at the speed; wider spread stays inside the cone; jitter varies only the speed")
	void coneVelocity() {
		Random random = new Random(4);
		float[] v = new float[3];

		for (int i = 0; i < 200; i++) {
			StrutFluids.coneVelocity(random, 0, 0, 2, 3f, 0f, 0f, v);
			assertEquals(0, v[0], 1e-5);
			assertEquals(0, v[1], 1e-5);
			assertEquals(3, v[2], 1e-5);
		}

		double worst = 1;
		double slowest = 99;
		double fastest = 0;

		for (int i = 0; i < 2000; i++) {
			StrutFluids.coneVelocity(random, 0.3, 1, -0.2, 2f, 0.3f, 0.25f, v);

			double len = Math.sqrt(0.3 * 0.3 + 1 + 0.04);

			worst = Math.min(worst, dot(v, 0.3 / len, 1 / len, -0.2 / len));

			double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);

			slowest = Math.min(slowest, speed);
			fastest = Math.max(fastest, speed);
		}

		assertTrue(worst >= 0.7 - 1e-6, "inside the cone: the widest angle has cos " + worst);
		assertTrue(worst < 0.8, "and it does use the width of the cone: " + worst);
		assertTrue(slowest >= 1.5 - 1e-3 && fastest <= 2.5 + 1e-3, slowest + " to " + fastest);
		assertTrue(fastest - slowest > 0.5, "the speed does vary");
	}

	@Test
	@DisplayName("a hemisphere spread never throws backwards, and a zero direction does not produce NaN")
	void hemisphereAndDegenerate() {
		Random random = new Random(8);
		float[] v = new float[3];

		for (int i = 0; i < 2000; i++) {
			StrutFluids.coneVelocity(random, 1, 0, 0, 1f, 1f, 0f, v);
			assertTrue(dot(v, 1, 0, 0) >= -1e-5);
		}

		StrutFluids.coneVelocity(random, 0, 0, 0, 1f, 0.5f, 0f, v);

		for (float c : v)
			assertTrue(Float.isFinite(c));
	}

	@Test
	@DisplayName("a preset file keeps THICK's value for whatever it leaves out, reads a hex colour, and keeps its limits")
	void presetFiles() {
		FluidParams p = FluidParams.parse(JsonParser.parseString("{\"viscosity\": 0.9, \"color\": \"CCFF8844\", \"cell\": 0.001}").getAsJsonObject());

		assertEquals(0.9f, p.viscosity(), 1e-6);
		assertEquals(0xCCFF8844, p.color());
		assertEquals(FluidParams.THICK.radius(), p.radius(), 1e-6);
		assertTrue(p.cell() >= p.radius() / 6f - 1e-6, "a cell finer than a sixth of the reach would be thousands of points a particle");

		FluidParams numeric = FluidParams.parse(JsonParser.parseString("{\"color\": -1}").getAsJsonObject());

		assertEquals(-1, numeric.color());
	}

	@Test
	@DisplayName("registered presets are found by id, and an unknown one is the thick liquid")
	void registry() {
		Identifier id = Identifier.of("test", "honey");
		FluidParams honey = new FluidParams(0.2f, 0.09f, 0.05f, 0.5f, 0.5f, 9.8f, 0.4f, 20f, 2f, 0xFFD8A030, 0.35f, 0.09f);

		StrutFluids.register(id, honey);

		assertSame(honey, StrutFluids.preset(id));
		assertSame(FluidParams.THICK, StrutFluids.preset(Identifier.of("test", "nothing")));
		assertSame(FluidParams.THICK, StrutFluids.preset(StrutFluids.THICK));
	}

	@Test
	@DisplayName("an emission from an event binding reads its fields and gives the rest their defaults")
	void emissionFromJson() {
		StrutFluids.Emission e = FluidCommands.emissionOf(JsonParser.parseString("{\"bone\": \"tip\", \"count\": 30, \"duration\": 0.5, \"direction\": [0, 1, 0], \"offset\": [0, 0, 0.1]}").getAsJsonObject());

		assertEquals(30, e.count());
		assertEquals(0.5f, e.duration(), 1e-6);
		assertEquals(StrutFluids.THICK, e.preset());
		assertEquals(1.5f, e.speed(), 1e-6);
		assertArrayEquals(new float[] {0, 1, 0}, e.direction());
		assertArrayEquals(new float[] {0, 0, 0.1f}, e.offset());
	}
}
