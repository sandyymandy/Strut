package com.sandymandy.strut.mixin.common;

import com.sandymandy.strut.player.EyeGuard;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suffocation is worked out from the eye. A body's eye bone is where the camera is, and as the head turns toward a wall it moves into it: that must not hurt, so
 * the wall check measures from the game's own eye (see {@link EyeGuard})
 */
@Mixin(Entity.class)
public abstract class WallCheckMixin {
	@Inject(method = "isInsideWall", at = @At("HEAD"))
	private void strut$enterWallCheck(CallbackInfoReturnable<Boolean> cir) {
		EyeGuard.enter();
	}

	@Inject(method = "isInsideWall", at = @At("RETURN"))
	private void strut$leaveWallCheck(CallbackInfoReturnable<Boolean> cir) {
		EyeGuard.exit();
	}
}
