package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import com.sandymandy.strut.player.client.PlayerRenderers;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLoader;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers for a player who has a Strut body: the renderer the dispatcher gives for them is the body's, so the world, the inventory screen and anything else
 * that draws a player through the dispatcher draws the body. A player without one gets the vanilla renderer, exactly as before
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Shadow
	@Final
	private ItemRenderer itemRenderer;

	@Shadow
	@Final
	private BlockRenderManager blockRenderManager;

	@Shadow
	@Final
	private HeldItemRenderer heldItemRenderer;

	@Shadow
	@Final
	private TextRenderer textRenderer;

	@Shadow
	@Final
	private EntityModelLoader modelLoader;

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "getRenderer", at = @At("HEAD"), cancellable = true)
	private <T extends Entity> void strut$playerBody(T entity, CallbackInfoReturnable<EntityRenderer<? super T>> cir) {
		if (!(entity instanceof AbstractClientPlayerEntity player) || PlayerRenderers.vanillaOnly())
			return;

		PlayerBody body = StrutPlayers.body(player);

		if (body != null)
			cir.setReturnValue((EntityRenderer) PlayerRenderers.forBody(body, this::strut$context));
	}

	@Inject(method = "reload", at = @At("TAIL"))
	private void strut$forgetBodies(ResourceManager manager, CallbackInfo ci) {
		PlayerRenderers.clear();
	}

	@Unique
	private EntityRendererFactory.Context strut$context() {
		return new EntityRendererFactory.Context((EntityRenderDispatcher) (Object) this, this.itemRenderer, this.blockRenderManager,
				this.heldItemRenderer, MinecraftClient.getInstance().getResourceManager(), this.modelLoader, this.textRenderer);
	}
}
