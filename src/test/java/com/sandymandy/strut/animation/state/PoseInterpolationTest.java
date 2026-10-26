package com.sandymandy.strut.animation.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Pose#interpolate} is the core operation of the three-buffer
 * pose pipeline - blending {@code previous} toward
 * {@code current} by partial tick, linearly for translation/scale and by quaternion slerp for
 * rotation so a bone spinning past a wrap boundary blends the short way round instead of snapping
 * backwards
 * <p>
 * Exercises {@link Pose#interpolate} and {@link Pose#copyFrom} directly, without needing a real
 * animatable or {@link com.sandymandy.strut.animation.AnimationProcessor} tick -
 * {@code AnimationProcessorPoseInterpolationTest} covers the wiring that actually calls these
 */
class PoseInterpolationTest {
	@Test
	@DisplayName("copyFrom overwrites every value, including visibility bits")
	void copyFromCopiesEverything() {
		Pose source = new Pose(2);
		source.setPosition(0, 1, 2, 3);
		source.setRotation(0, 0.1f, 0.2f, 0.3f);
		source.setScale(0, 2, 3, 4);
		source.setPivotDeltaX(1, 5);
		source.setHidden(1, true);
		source.setChildrenHidden(0, true);

		Pose dest = new Pose(2);

		dest.copyFrom(source);

		assertEquals(1, dest.getPosX(0));
		assertEquals(2, dest.getPosY(0));
		assertEquals(3, dest.getPosZ(0));
		assertEquals(0.1f, dest.getRotX(0));
		assertEquals(0.2f, dest.getRotY(0));
		assertEquals(0.3f, dest.getRotZ(0));
		assertEquals(2, dest.getScaleX(0));
		assertEquals(3, dest.getScaleY(0));
		assertEquals(4, dest.getScaleZ(0));
		assertEquals(5, dest.getPivotDeltaX(1));
		assertTrue(dest.isHidden(1));
		assertTrue(dest.isChildrenHidden(0));
	}

	@Test
	@DisplayName("interpolating position and scale is linear by partial tick")
	void positionAndScaleAreLinear() {
		Pose previous = new Pose(1);
		Pose current = new Pose(1);
		Pose dest = new Pose(1);

		previous.setPosition(0, 0, 0, 0);
		current.setPosition(0, 10, 20, -10);
		previous.setScale(0, 1, 1, 1);
		current.setScale(0, 3, 1, 5);

		Pose.interpolate(previous, current, 0.25f, dest);

		assertEquals(2.5f, dest.getPosX(0), 1e-5);
		assertEquals(5f, dest.getPosY(0), 1e-5);
		assertEquals(-2.5f, dest.getPosZ(0), 1e-5);
		assertEquals(1.5f, dest.getScaleX(0), 1e-5);
		assertEquals(1f, dest.getScaleY(0), 1e-5);
		assertEquals(2f, dest.getScaleZ(0), 1e-5);
	}

	@Test
	@DisplayName("partial tick 0 reproduces previous exactly, partial tick 1 reproduces current exactly")
	void endpointsAreExact() {
		Pose previous = new Pose(1);
		Pose current = new Pose(1);
		Pose dest = new Pose(1);

		previous.setPosition(0, 1, 2, 3);
		previous.setRotation(0, 0.5f, -0.5f, 0.25f);
		current.setPosition(0, 9, 8, 7);
		current.setRotation(0, 1.5f, 1.5f, -1.5f);

		Pose.interpolate(previous, current, 0f, dest);

		assertEquals(previous.getPosX(0), dest.getPosX(0), 1e-5);
		assertEquals(previous.getRotX(0), dest.getRotX(0), 1e-4);
		assertEquals(previous.getRotY(0), dest.getRotY(0), 1e-4);
		assertEquals(previous.getRotZ(0), dest.getRotZ(0), 1e-4);

		Pose.interpolate(previous, current, 1f, dest);

		assertEquals(current.getPosX(0), dest.getPosX(0), 1e-5);
		assertEquals(current.getRotX(0), dest.getRotX(0), 1e-4);
		assertEquals(current.getRotY(0), dest.getRotY(0), 1e-4);
		assertEquals(current.getRotZ(0), dest.getRotZ(0), 1e-4);
	}

	@Test
	@DisplayName("rotation blends the short way round a wrap boundary instead of snapping backwards")
	void rotationTakesTheShortWayAroundAWrapBoundary() {
		Pose previous = new Pose(1);
		Pose current = new Pose(1);
		Pose dest = new Pose(1);

		float almostFullTurn = (float)(2 * Math.PI - 0.1);
		float justPastZero = 0.1f;

		previous.setRotation(0, almostFullTurn, 0, 0);
		current.setRotation(0, justPastZero, 0, 0);

		Pose.interpolate(previous, current, 0.5f, dest);

		// The naive lerp of the raw floats would land near the middle of the circle (~PI); slerping
		// the equivalent quaternions blends through the nearby wrap point instead, landing near 0
		float blended = dest.getRotX(0);
		float distanceFromWrapPoint = Math.min(Math.abs(blended), (float)(2 * Math.PI) - Math.abs(blended));

		assertTrue(distanceFromWrapPoint < 0.2f,
				"expected the blended angle to land near the 0/2π wrap point (short way round), but got " + blended);
	}

	/**
	 * Gimbal lock: at {@code Y = ±90°} the X and Z axes coincide, and the ordinary decomposition's
	 * {@code atan2} calls have both arguments collapse to zero, so each returns whatever rounding
	 * noise decides. Live-verified against a real rig - {@code animation.base.strip} rotates
	 * {@code armR} to exactly {@code (0°, 90°, 15.5°)} at t=0.52s, which used to decompose to
	 * {@code (0°, 89.97°, 0°)}: the 15.5° of Z silently dropped, reproducing a materially different
	 * orientation, with the neighbouring frames landing on wildly different triples. On screen the
	 * arm snapped to a wrong pose for the moment it passed through 90°
	 */
	@Test
	@DisplayName("a rotation at the Y = 90 gimbal-lock pole survives the pose round trip")
	void gimbalLockPoleRoundTripsExactly() {
		for (float poleDegrees : new float[] {90, -90}) {
			Pose previous = new Pose(1);
			Pose current = new Pose(1);
			Pose dest = new Pose(1);

			float y = (float)Math.toRadians(poleDegrees);
			float z = (float)Math.toRadians(15.5);

			previous.setRotation(0, 0, y, z);
			current.setRotation(0, 0, y, z);

			Pose.interpolate(previous, current, 0.5f, dest);

			assertEquals(0, dest.getRotX(0), 1e-4, "X at the pole folds into Z rather than being left to noise");
			assertEquals(y, dest.getRotY(0), 1e-4);
			assertEquals(z, dest.getRotZ(0), 1e-4, "the Z rotation must not be silently dropped at the pole");
		}
	}

	/**
	 * The pole must not be a discontinuity either: blending right up to it has to approach the same
	 * answer the pole itself decomposes to, or the bone visibly snaps on the frame it arrives
	 */
	@Test
	@DisplayName("blending into the gimbal-lock pole stays continuous with the frames before it")
	void approachingTheGimbalLockPoleIsContinuous() {
		Pose previous = new Pose(1);
		Pose current = new Pose(1);
		Pose dest = new Pose(1);

		previous.setRotation(0, 0, 0, (float)Math.toRadians(-10));
		current.setRotation(0, 0, (float)Math.toRadians(90), (float)Math.toRadians(15.5));

		Pose.interpolate(previous, current, 0.9f, dest);

		float nearPoleY = dest.getRotY(0);
		float nearPoleZ = dest.getRotZ(0);

		Pose.interpolate(previous, current, 1f, dest);

		assertEquals(Math.toRadians(90), dest.getRotY(0), 1e-4);
		assertTrue(Math.abs(dest.getRotY(0) - nearPoleY) < 0.2f,
				"Y jumped on arrival at the pole: " + Math.toDegrees(nearPoleY) + " -> " + Math.toDegrees(dest.getRotY(0)));
		assertTrue(Math.abs(dest.getRotZ(0) - nearPoleZ) < 0.5f,
				"Z jumped on arrival at the pole: " + Math.toDegrees(nearPoleZ) + " -> " + Math.toDegrees(dest.getRotZ(0)));
	}

	@Test
	@DisplayName("visibility is not interpolated - the destination always takes current's value")
	void visibilityIsNotInterpolated() {
		Pose previous = new Pose(1);
		Pose current = new Pose(1);
		Pose dest = new Pose(1);

		previous.setHidden(0, false);
		current.setHidden(0, true);

		Pose.interpolate(previous, current, 0.01f, dest);

		assertTrue(dest.isHidden(0), "visibility should take effect immediately on the update that caused it, not fade in over the blend");
	}
}
