package com.sandymandy.strut.physics.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRotationConfigTest {
	@Test
	void localRotationIsReadFromTheDefaultsAndFromABone() {
		PhysicsConfig config = PhysicsConfig.parse("{\"defaults\": {\"localRotation\": true}, \"bones\": {\"pouchL\": {\"preset\": \"soft-bone\"}, \"belly\": {\"preset\": \"soft-bone\", \"localRotation\": false}}}", "test");

		assertTrue(config.warnings().isEmpty(), config.warnings().toString());
		assertTrue(config.bones().get("pouchL").localRotation());
		assertFalse(config.bones().get("belly").localRotation(), "a bone can say no over the defaults");
	}

	@Test
	void itIsOffUnlessSaid() {
		PhysicsConfig config = PhysicsConfig.parse("{\"bones\": {\"pouchL\": {\"preset\": \"soft-bone\"}}}", "test");

		assertFalse(config.bones().get("pouchL").localRotation());
	}
}
