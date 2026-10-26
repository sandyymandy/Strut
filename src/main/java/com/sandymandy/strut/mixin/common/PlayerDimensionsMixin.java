package com.sandymandy.strut.mixin.common;

import com.sandymandy.strut.player.BodyAssignment;
import com.sandymandy.strut.player.BodyDimensions;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * The hitbox and eye height of a player whose body gives them dimensions, on both sides, and the recalculation when the body they have changes (the assignment
 * reaches a client some time after the player exists, so the player notices it as they tick)
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerDimensionsMixin {
	@Unique
	private BodyAssignment strut$dimensionsFor;

	@Inject(method = "getBaseDimensions", at = @At("RETURN"), cancellable = true)
	private void strut$bodyDimensions(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		PlayerBody body = StrutPlayers.body((PlayerEntity) (Object) this);
		BodyDimensions dimensions = body == null ? null : body.dimensions(StrutPlayers.variant((PlayerEntity) (Object) this));

		if (dimensions == null)
			return;

		// standing is the body's size; the game's other poses keep their shape, in proportion
		cir.setReturnValue(pose == EntityPose.STANDING ? EntityDimensions.changing(dimensions.width(), dimensions.height()).withEyeHeight(dimensions.eyeHeight())
				: cir.getReturnValue().scaled(dimensions.widthRatio(), dimensions.heightRatio()));
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void strut$bodyChanged(CallbackInfo ci) {
		PlayerEntity player = (PlayerEntity) (Object) this;
		BodyAssignment now = StrutPlayers.assignment(player);

		if (!Objects.equals(now, this.strut$dimensionsFor)) {
			this.strut$dimensionsFor = now;
			player.calculateDimensions();
		}
	}
}
