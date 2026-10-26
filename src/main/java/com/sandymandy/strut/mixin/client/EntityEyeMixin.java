package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.player.EyeGuard;
import com.sandymandy.strut.player.client.FirstPersonBody;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a player looking out of their own {@code IMMERSIVE} body aims from: the eye bone, so the crosshair, what is hit and what is shot start where the
 * camera is, not at the height of the vanilla eye. Client only; the server still checks what was done against the player's own eye
 */
@Mixin(Entity.class)
public abstract class EntityEyeMixin {
	@Inject(method = "getCameraPosVec", at = @At("HEAD"), cancellable = true)
	private void strut$aimFromTheEyeBone(float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
		Vec3d eye = this.strut$eye();

		if (eye != null)
			cir.setReturnValue(eye);
	}

	@Inject(method = "getEyePos", at = @At("HEAD"), cancellable = true)
	private void strut$eyeAtTheEyeBone(CallbackInfoReturnable<Vec3d> cir) {
		Vec3d eye = this.strut$eye();

		if (eye != null)
			cir.setReturnValue(eye);
	}

	private Vec3d strut$eye() {
		if (EyeGuard.vanillaEye())
			return null;

		return FirstPersonBody.eyePosition((Entity) (Object) this);
	}
}
