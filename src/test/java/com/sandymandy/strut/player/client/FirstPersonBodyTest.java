package com.sandymandy.strut.player.client;

import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the camera goes for a body in first person: the eye bone's place relative to the player, from the transforms the body was drawn with
 */
class FirstPersonBodyTest {
	private static void assertVec(double x, double y, double z, Vec3d actual) {
		assertEquals(x, actual.x, 1.0e-5);
		assertEquals(y, actual.y, 1.0e-5);
		assertEquals(z, actual.z, 1.0e-5);
	}

	/** The transform a player is drawn at, relative to the camera: nothing but where the player is */
	private static Matrix4f at(float x, float y, float z) {
		return new Matrix4f().translate(x, y, z);
	}

	@Test
	void aBoneAtRestIsItsHeightAboveThePlayerWhereverThePlayerIs() {
		Matrix4f entity = at(10, -3, 7);
		Matrix4f model = new Matrix4f(entity);
		Matrix4f bone = new Matrix4f().translate(0, 1.6f, 0);

		assertVec(0, 1.6, 0, FirstPersonBody.offsetOfBone(bone, model, entity));
	}

	@Test
	void theBodyTurningToFaceTurnsTheOffsetWithIt() {
		Matrix4f entity = at(10, -3, 7);
		Matrix4f model = new Matrix4f(entity).rotateY((float) Math.toRadians(90));
		Matrix4f bone = new Matrix4f().translate(0.3f, 1.6f, 0);

		// a quarter turn about Y takes +X to -Z
		assertVec(0, 1.6, -0.3, FirstPersonBody.offsetOfBone(bone, model, entity));
	}

	@Test
	void theBodysScaleIsPartOfTheOffset() {
		Matrix4f entity = at(2, 2, 2);
		Matrix4f model = new Matrix4f(entity).scale(1.5f);
		Matrix4f bone = new Matrix4f().translate(0, 1f, 0);

		assertVec(0, 1.5, 0, FirstPersonBody.offsetOfBone(bone, model, entity));
	}

	@Test
	void anAnimatedBoneMovesTheOffset() {
		Matrix4f entity = at(0, 0, 0);
		Matrix4f model = new Matrix4f(entity).translate(0, 0.01f, 0);
		Matrix4f bone = new Matrix4f().translate(0, 1.4f, 0.2f);

		assertVec(0, 1.41, 0.2, FirstPersonBody.offsetOfBone(bone, model, entity));
	}
}
