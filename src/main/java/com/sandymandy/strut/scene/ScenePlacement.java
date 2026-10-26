package com.sandymandy.strut.scene;

import net.minecraft.util.math.Vec3d;

/**
 * Where a participant of a scene stands: a spot in the scene's own space, turned and moved to the scene's origin
 * <p>
 * The offset is in the scene's space, which faces the way the scene's yaw says: {@code z} is ahead of that way, {@code x} to its left (the game's own handedness),
 * {@code y} up. So a scene authored with the host at the origin facing +z and the partner one block ahead of her works wherever and however it is placed
 *
 * @param x blocks to the left
 * @param y blocks up
 * @param z blocks ahead
 * @param yaw degrees the participant is turned from the scene's facing
 */
public record ScenePlacement(double x, double y, double z, float yaw) {
	/** At the origin, facing the way the scene does */
	public static final ScenePlacement ORIGIN = new ScenePlacement(0, 0, 0, 0f);

	public static ScenePlacement offset(double left, double up, double ahead) {
		return new ScenePlacement(left, up, ahead, 0f);
	}

	public ScenePlacement facing(float degreesFromTheScene) {
		return new ScenePlacement(this.x, this.y, this.z, degreesFromTheScene);
	}

	/** The world position of this spot for a scene at {@code origin} facing {@code sceneYaw} degrees */
	public Vec3d position(Vec3d origin, float sceneYaw) {
		double radians = Math.toRadians(sceneYaw);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);

		// facing is (-sin, cos); left of it is (cos, sin)
		return new Vec3d(origin.x + this.x * cos - this.z * sin, origin.y + this.y, origin.z + this.x * sin + this.z * cos);
	}

	/** The yaw a participant here faces, in degrees */
	public float yaw(float sceneYaw) {
		return sceneYaw + this.yaw;
	}
}
