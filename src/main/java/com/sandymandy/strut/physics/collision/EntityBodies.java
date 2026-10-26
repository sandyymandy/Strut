package com.sandymandy.strut.physics.collision;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.solver.MotionSampler;

/**
 * Publishes the hitbox of every living entity near the camera as a capsule for {@link Bodies}, so a player's body or a mob presses on soft parts. The other
 * place the physics touches the world, next to {@link MotionSampler}
 */
public final class EntityBodies {
	private EntityBodies() {}

	/** Called once at the start of each world render, after {@link Bodies#nextFrame} */
	public static void publish() {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.world == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null)
			return;

		if (!PhysicsOptions.enabled() || !PhysicsOptions.otherCollision() && !PhysicsOptions.playerCollision())
			return;

		float delta = client.getRenderTickCounter().getTickDelta(false);
		Vec3d camera = client.gameRenderer.getCamera().getPos();
		double limit = PhysicsOptions.effectiveRange() + 4.0;

		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || living.isSpectator() || living.isRemoved())
				continue;

			Vec3d pos = living.getLerpedPos(delta);

			if (pos.squaredDistanceTo(camera) > limit * limit)
				continue;

			boolean player = living instanceof PlayerEntity;

			Bodies.publishHitbox(living.getId(), player ? Bodies.PLAYERS : Bodies.MOBS, pos.x, pos.y, pos.z, living.getHeight(), living.getWidth() * 0.5);
		}
	}
}
