package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.player.client.FirstPersonBody;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the first-person camera at the eye bone of a player's own {@code IMMERSIVE} body. Only the camera moves: the player's eye position, the one aiming
 * and projectiles use, is untouched
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPos(Vec3d pos);

	@Inject(method = "update", at = @At("TAIL"))
	private void strut$cameraAtTheEyeBone(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (thirdPerson || !(focusedEntity instanceof AbstractClientPlayerEntity player))
			return;

		Vec3d position = FirstPersonBody.cameraPosition(player, tickDelta);

		if (position != null)
			setPos(position);
	}
}
