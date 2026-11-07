package com.sandymandy.strut.physics.solver;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import com.sandymandy.strut.physics.PhysicsInstance;

/**
 * Reads an entity's interpolated position, body yaw and walk for the frame being drawn, on the game clock. This is the only place the physics touches the world
 */
public final class MotionSampler {
	private MotionSampler() {}

	/**
	 * The entity's state at this frame, or null if it is gone or the game is paused (the springs then keep where they are)
	 */
	public static PhysicsInstance.Sample sample(int entityId) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.world == null || client.isPaused())
			return null;

		Entity entity = client.world.getEntityById(entityId);

		if (entity == null)
			return null;

		float delta = client.getRenderTickCounter().getTickDelta(false);
		Vec3d pos = entity.getLerpedPos(delta);
		float yaw = entity instanceof LivingEntity living ? MathHelper.lerpAngleDegrees(delta, living.prevBodyYaw, living.bodyYaw) : entity.getYaw(delta);

		// how fast and how far the legs have walked, as the walking animation measures them: what bounces a soft body on each step
		float legSpeed = entity instanceof LivingEntity living ? living.limbAnimator.getSpeed(delta) : 0f;
		float legPosition = entity instanceof LivingEntity living ? living.limbAnimator.getPos(delta) : 0f;

		return new PhysicsInstance.Sample(pos.x, pos.y, pos.z, yaw, (client.world.getTime() + delta) / 20.0, legSpeed, legPosition);
	}

	/** Whether the entity is the one the camera belongs to (the player's own model, seen from behind or in a mirror) */
	public static boolean isViewer(int entityId) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null)
			return false;

		Entity camera = client.getCameraEntity();

		return (camera != null && camera.getId() == entityId) || (client.player != null && client.player.getId() == entityId);
	}

	/** The distance from the camera to the entity's last known place, or 0 if unknown */
	public static double distanceFromCamera(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null)
			return 0.0;

		return client.gameRenderer.getCamera().getPos().distanceTo(new Vec3d(x, y, z));
	}
}
