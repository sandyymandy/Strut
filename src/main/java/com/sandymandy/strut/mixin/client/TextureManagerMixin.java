package com.sandymandy.strut.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.sandymandy.strut.cache.texture.AnimatableTexture;

/**
 * Injection into TextureManager's access point for runtime-derived textures to allow Strut to swap them out with {@link AnimatableTexture} for animated texture purposes
 * <p>
 * Because AnimatedTexture extends {@link net.minecraft.client.texture.ResourceTexture ResourceTexture}, the replacement should be seamless
 */
@Mixin(value = TextureManager.class, priority = 2000)
public abstract class TextureManagerMixin {
	@Shadow
	public abstract void registerTexture(Identifier path, AbstractTexture texture);

	@WrapOperation(method = "getTexture(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/texture/AbstractTexture;",
			at = @At(value = "NEW", target = "(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/texture/ResourceTexture;"),
			require = 0)
	private ResourceTexture strut$replaceAnimatableTexture(Identifier location, Operation<ResourceTexture> original) {
		AnimatableTexture animatableTexture = new AnimatableTexture(location);

		registerTexture(location, animatableTexture);

		return animatableTexture.isAnimated() ? animatableTexture : new ResourceTexture(location);
	}

	@WrapWithCondition(method = "getTexture(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/texture/AbstractTexture;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/texture/TextureManager;registerTexture(Lnet/minecraft/util/Identifier;Lnet/minecraft/client/texture/AbstractTexture;)V"),
			require = 0)
	private boolean strut$skipAnimatableTextureRegistration(TextureManager textureManager, Identifier location, AbstractTexture texture) {
		return !(texture instanceof AnimatableTexture);
	}
}
