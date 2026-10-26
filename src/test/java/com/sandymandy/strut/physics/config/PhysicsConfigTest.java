package com.sandymandy.strut.physics.config;

import java.util.List;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.physics.solver.SpringParams;

/**
 * The physics JSON: presets, explicit keys over presets, layering of a sidecar over a section, and warnings rather than exceptions
 */
class PhysicsConfigTest {
	@Test
	void aPresetSuppliesValuesAndExplicitKeysOverrideThemWhateverTheOrder() {
		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"b\": {\"frequency\": 4, \"preset\": \"jiggle-soft\"}}}", "t");
		SpringParams p = config.bones().get("b");

		assertEquals(4f, p.frequency(), 0f, "the explicit frequency wins even though the preset key came second");
		assertEquals(0.24f, p.dampingRatio(), 1e-6f, "the rest comes from the preset");
		assertTrue(config.warnings().isEmpty(), config.warnings().toString());
	}

	@Test
	void defaultsApplyToEveryBoneAndABoneOverridesThem() {
		PhysicsConfig config = PhysicsConfig.parse("{\"defaults\": {\"inertia\": 0.5}, \"bones\": {\"a\": {}, \"b\": {\"inertia\": 1}}}", "t");

		assertEquals(0.5f, config.bones().get("a").inertia(), 0f);
		assertEquals(1f, config.bones().get("b").inertia(), 0f);
	}

	@Test
	void limitsAreReadInDegreesAndStoredInRadians() {
		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"b\": {\"limit\": {\"x\": [-10, 20]}}}}", "t");
		float[][] limit = config.bones().get("b").rotationLimit();

		assertEquals(Math.toRadians(-10), limit[0][0], 1e-6);
		assertEquals(Math.toRadians(20), limit[0][1], 1e-6);
		assertNull(limit[1], "an axis with no limit stays free");
	}

	@Test
	void badInputIsAWarningNotAnException() {
		PhysicsConfig config = PhysicsConfig.parse("{\"surprise\": 1, \"bones\": {\"b\": {\"preset\": \"nope\", \"frequency\": -3, \"colour\": 1, \"tip\": [0,0,0]}}}", "t");

		assertEquals(1, config.bones().size(), "the bone is still there with safe values");
		assertTrue(config.bones().get("b").frequency() > 0f);

		String all = String.join("\n", config.warnings());

		assertTrue(all.contains("surprise"), all);
		assertTrue(all.contains("unknown preset"), all);
		assertTrue(all.contains("must be above 0"), all);
		assertTrue(all.contains("unknown key \"colour\""), all);
		assertTrue(all.contains("zero vector"), all);

		PhysicsConfig broken = PhysicsConfig.parse("this is not json", "t");

		assertTrue(broken.isEmpty());
		assertFalse(broken.warnings().isEmpty());
	}

	@Test
	void aSidecarReplacesBonesItNamesAndKeepsTheRest() {
		PhysicsConfig section = PhysicsConfig.parse("{\"bones\": {\"a\": {\"frequency\": 1}, \"b\": {\"frequency\": 1}}}", "section");
		PhysicsConfig sidecar = PhysicsConfig.parse("{\"bones\": {\"b\": {\"frequency\": 3}, \"c\": {}}}", "sidecar");
		PhysicsConfig merged = section.overlaid(sidecar);

		assertEquals(List.of("a", "b", "c"), List.copyOf(merged.bones().keySet()));
		assertEquals(1f, merged.bones().get("a").frequency(), 0f);
		assertEquals(3f, merged.bones().get("b").frequency(), 0f);
	}
}
