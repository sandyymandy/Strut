package com.sandymandy.strut.mixin.common;

import com.sandymandy.strut.player.EyeGuard;
import com.sandymandy.strut.player.ServerEyes;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The server's end of {@code EntityEyeMixin}: a player who looks out of an immersive body aims from the eye bone there too
 */
@Mixin(Entity.class)
public abstract class PlayerEyeMixin {
	@Inject(method = "getCameraPosVec", at = @At("HEAD"), cancellable = true)
	private void strut$aimFromTheEyeBone(float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
		strut$eye(cir);
	}

	@Inject(method = "getEyePos", at = @At("HEAD"), cancellable = true)
	private void strut$eyeAtTheEyeBone(CallbackInfoReturnable<Vec3d> cir) {
		strut$eye(cir);
	}

	private void strut$eye(CallbackInfoReturnable<Vec3d> cir) {
		if (!EyeGuard.vanillaEye() && (Object) this instanceof ServerPlayerEntity player) {
			Vec3d eye = ServerEyes.eyePosition(player);

			if (eye != null)
				cir.setReturnValue(eye);
		}
	}
}
