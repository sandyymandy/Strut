package com.sandymandy.strut.util;

import com.sandymandy.strut.cache.object.StrutCube;
import com.sandymandy.strut.cache.object.StrutQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RenderUtil#applyPivotRotation} replaced a {@link MatrixStack} push/translate/rotate/pop
 * sequence with the same math applied directly to a {@link Matrix4f}/{@link Matrix3f} pair, for
 * the reasons given in {@link com.sandymandy.strut.renderer.StrutRenderer#renderCube}'s
 * own doc comment on why. These two paths have to agree exactly, for every combination of pivot
 * and rotation a real cube can carry, or this "optimisation" would be a silent rendering
 * regression - the exact failure mode this project's own history warns is invisible to any test
 * that does not compare against the old behaviour directly
 * <p>
 * {@link MatrixStack} needs no Minecraft bootstrap to construct or use - it is a plain
 * {@link java.util.Deque} of JOML matrices - so the old push-based sequence is usable here as the
 * reference implementation, not just a mental model of what the new code should do
 */
class RenderUtilTest {
	private static final float EPSILON = 1.0e-5f;

	static Stream<Vec3d> rotations() {
		return Stream.of(
				new Vec3d(0, 0, 0),
				new Vec3d(0.7, 0, 0),
				new Vec3d(0, 0.7, 0),
				new Vec3d(0, 0, 0.7),
				new Vec3d(0.3, -0.6, 0),
				new Vec3d(0, 0.3, -0.6),
				new Vec3d(-0.6, 0, 0.3),
				new Vec3d(0.4, -0.9, 1.2),
				new Vec3d(Math.PI / 2, 0.1, 0.2) // through the gimbal-lock pole, for good measure
		);
	}

	@ParameterizedTest
	@MethodSource("rotations")
	void matchesThePushBasedSequenceForEveryRotationShape(Vec3d rotation) {
		Vec3d pivot = new Vec3d(4, -2, 6);
		StrutCube cube = new StrutCube(new StrutQuad[0], pivot, rotation,
				new Vec3d(8, 8, 8), 0, false);

		// A non-trivial starting transform, not identity - the two paths have to agree from an
		// arbitrary bone-level pose, not only from the origin
		MatrixStack reference = new MatrixStack();

		reference.translate(1.5f, -0.25f, 3.0f);
		reference.multiply(new Quaternionf().rotationY(0.3f));

		Matrix4f basePose = new Matrix4f(reference.peek().getPositionMatrix());
		Matrix3f baseNormal = new Matrix3f(reference.peek().getNormalMatrix());

		// The old path: push, translate to pivot, rotate, translate away, pop
		reference.push();
		RenderUtil.translateToPivotPoint(reference, cube);
		RenderUtil.rotateMatrixAroundCube(reference, cube);
		RenderUtil.translateAwayFromPivotPoint(reference, cube);

		Matrix4f expectedPose = new Matrix4f(reference.peek().getPositionMatrix());
		Matrix3f expectedNormal = new Matrix3f(reference.peek().getNormalMatrix());

		reference.pop();

		// The new path: direct Matrix4f/Matrix3f math, no MatrixStack involved
		Matrix4f actualPose = new Matrix4f(basePose);
		Matrix3f actualNormal = new Matrix3f(baseNormal);

		RenderUtil.applyPivotRotation(actualPose, actualNormal, cube.pivot(), cube.rotation());

		assertMatrixEquals(expectedPose, actualPose);
		assertMatrixEquals(expectedNormal, actualNormal);
	}

	@Test
	void zeroRotationIsExactlyANoOp() {
		Matrix4f pose = new Matrix4f().translate(1, 2, 3).rotateY(0.4f);
		Matrix3f normal = new Matrix3f().rotateY(0.4f);
		Matrix4f posePristine = new Matrix4f(pose);
		Matrix3f normalPristine = new Matrix3f(normal);

		RenderUtil.applyPivotRotation(pose, normal, new Vec3d(4, -2, 6), Vec3d.ZERO);

		assertMatrixEquals(posePristine, pose);
		assertMatrixEquals(normalPristine, normal);
	}

	private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON,
						"element (" + row + "," + column + ")");
			}
		}
	}

	private static void assertMatrixEquals(Matrix3f expected, Matrix3f actual) {
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				assertEquals(expected.get(column, row), actual.get(column, row), EPSILON,
						"element (" + row + "," + column + ")");
			}
		}
	}
}
