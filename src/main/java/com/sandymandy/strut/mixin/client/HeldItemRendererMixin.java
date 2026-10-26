package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.player.client.FirstPersonBody;
import com.sandymandy.strut.player.client.PlayerRenderers;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first-person arm and held map are drawn by asking the dispatcher for the player's renderer and casting it to the vanilla player renderer. A Strut body's
 * renderer would fail that cast, so while those are drawn the dispatcher answers with the vanilla one
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
	/**
	 * A player looking out of their own immersive body sees that body's arms, not the vanilla ones
	 */
	@Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
			at = @At("HEAD"), cancellable = true)
	private void strut$noVanillaHandsInTheBody(float tickDelta, MatrixStack matrices, VertexConsumerProvider.Immediate vertexConsumers,
										   ClientPlayerEntity player, int light, CallbackInfo ci) {
		if (FirstPersonBody.immersiveBodyOf(player) != null)
			ci.cancel();
	}

	@Inject(method = {"renderArm", "renderArmHoldingItem", "renderMapInOneHand", "renderMapInBothHands"}, at = @At("HEAD"))
	private void strut$vanillaArmStart(CallbackInfo ci) {
		PlayerRenderers.enterVanilla();
	}

	@Inject(method = {"renderArm", "renderArmHoldingItem", "renderMapInOneHand", "renderMapInBothHands"}, at = @At("RETURN"))
	private void strut$vanillaArmEnd(CallbackInfo ci) {
		PlayerRenderers.leaveVanilla();
	}
}
