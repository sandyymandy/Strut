package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.assetsync.client.SyncedAssetPackProvider;
import net.minecraft.client.resource.DefaultClientResourcePackProvider;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adds Strut's synced-asset provider to the <b>client's</b> resource pack manager
 * <p>
 * The manager copies its providers into an immutable set at construction, so there is nothing to register with later.
 * The client's own manager is told apart from the server's data pack manager (this class is used for both) by the
 * client-only default provider it is built with. Fabric's own resource loader adds its providers the same way
 */
@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {
	@Mutable
	@Final
	@Shadow
	private Set<ResourcePackProvider> providers;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void strut$addSyncedAssets(ResourcePackProvider[] providers, CallbackInfo ci) {
		for (ResourcePackProvider provider : this.providers) {
			if (provider instanceof DefaultClientResourcePackProvider) {
				Set<ResourcePackProvider> withSynced = new LinkedHashSet<>(this.providers);

				withSynced.add(SyncedAssetPackProvider.INSTANCE);
				this.providers = withSynced;

				return;
			}
		}
	}
}
