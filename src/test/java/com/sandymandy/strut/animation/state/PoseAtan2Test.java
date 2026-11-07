package com.sandymandy.strut.animation.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The quick {@code atan2} the pose blend uses stays within a thousandth of a degree of the library's, in every quadrant and on the axes */
class PoseAtan2Test {
	@Test
	void agreesWithTheLibraryEverywhere() {
		for (int i = 0; i < 20000; i++) {
			double angle = (i / 20000.0) * 2 * Math.PI - Math.PI;
			float y = (float)(Math.sin(angle) * (0.1 + (i % 7)));
			float x = (float)(Math.cos(angle) * (0.1 + (i % 7)));

			assertEquals(Math.atan2(y, x), Pose.atan2(y, x), 2e-5, "at " + angle);
		}

		assertEquals(0f, Pose.atan2(0f, 0f));
		assertEquals(Math.PI / 2, Pose.atan2(1f, 0f), 2e-5);
		assertEquals(-Math.PI / 2, Pose.atan2(-1f, 0f), 2e-5);
		assertEquals(Math.PI, Pose.atan2(0f, -1f), 2e-5);
		assertEquals(0.0, Pose.atan2(0f, 1f), 2e-5);
	}

	@Test
	void theQuickSlerpAgreesWithJomls() {
		java.util.Random random = new java.util.Random(7);

		for (int i = 0; i < 5000; i++) {
			org.joml.Quaternionf a = new org.joml.Quaternionf().rotationZYX(random.nextFloat() * 6 - 3, random.nextFloat() * 3 - 1.5f, random.nextFloat() * 6 - 3);
			org.joml.Quaternionf b = new org.joml.Quaternionf().rotationZYX(random.nextFloat() * 6 - 3, random.nextFloat() * 3 - 1.5f, random.nextFloat() * 6 - 3);
			float t = random.nextFloat();
			org.joml.Quaternionf expected = new org.joml.Quaternionf(a).slerp(b, t);
			org.joml.Quaternionf got = new org.joml.Quaternionf(a);

			Pose.slerp(got, b, t);

			// the same rotation, whichever sign the quaternion is written with
			assertEquals(1.0, Math.abs(expected.dot(got)), 1e-4);
		}
	}
}
