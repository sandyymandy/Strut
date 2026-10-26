package com.sandymandy.strut.scene;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScenePlacementTest {
	private static void assertAt(Vec3d expected, Vec3d actual) {
		assertEquals(expected.x, actual.x, 1.0E-9, "x");
		assertEquals(expected.y, actual.y, 1.0E-9, "y");
		assertEquals(expected.z, actual.z, 1.0E-9, "z");
	}

	@Test
	void aSceneFacingSouthPutsAheadOnPositiveZAndLeftOnPositiveX() {
		Vec3d origin = new Vec3d(10, 64, 20);

		assertAt(new Vec3d(10, 64, 21), ScenePlacement.offset(0, 0, 1).position(origin, 0f));
		assertAt(new Vec3d(11, 65, 20), ScenePlacement.offset(1, 1, 0).position(origin, 0f));
	}

	@Test
	void turningTheSceneCarriesEveryoneAroundTheOrigin() {
		Vec3d origin = new Vec3d(0, 0, 0);

		// facing west (yaw 90) is -x: ahead is west, left is south
		assertAt(new Vec3d(-1, 0, 0), ScenePlacement.offset(0, 0, 1).position(origin, 90f));
		assertAt(new Vec3d(0, 0, 1), ScenePlacement.offset(1, 0, 0).position(origin, 90f));
		// facing north (180): ahead is -z
		assertAt(new Vec3d(0, 0, -2), ScenePlacement.offset(0, 0, 2).position(origin, 180f));
	}

	@Test
	void aParticipantFacesTheScenesWayPlusItsOwnTurn() {
		assertEquals(270f, ScenePlacement.ORIGIN.facing(180f).yaw(90f));
		assertEquals(90f, ScenePlacement.ORIGIN.yaw(90f));
	}
}
