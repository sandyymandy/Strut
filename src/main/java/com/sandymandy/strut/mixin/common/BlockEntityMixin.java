package com.sandymandy.strut.mixin.common;

import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.StrutCacheHolder;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every block entity a slot for its animatable instance cache, so a {@code StrutBlockEntity} needs no field of its own
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements StrutCacheHolder {
	@Unique
	private AnimatableInstanceCache strut$cache;

	@Override
	public AnimatableInstanceCache strut$getCache() {
		return this.strut$cache;
	}

	@Override
	public void strut$setCache(AnimatableInstanceCache cache) {
		this.strut$cache = cache;
	}
}
