package com.sandymandy.strut.physics.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquidTest {
	private final Liquid water = new Liquid(60, 62, 0.5f, 0f, false);

	@Test
	void aParticleIsInOnlyBelowTheSurface() {
		assertEquals(0, water.submersion(62.5), 1e-9);
		assertEquals(1, water.submersion(61), 1e-9);
		assertTrue(water.submersion(61.9) > 0 && water.submersion(61.9) < 1);
	}

	@Test
	void waterLiftsHairAndSlowsIt() {
		assertTrue(water.gravity(9.8, 1) < 0);
		assertEquals(9.8, water.gravity(9.8, 0), 1e-9);
		assertTrue(water.keep(1) < 1);
		assertEquals(1, water.keep(0), 1e-9);
	}

	@Test
	void theCurrentPushesOnlyWhatIsIn() {
		assertTrue(water.pushX(1) > 0);
		assertEquals(0, water.pushX(0), 1e-9);
		assertEquals(0, water.pushZ(1), 1e-9);
	}
}
