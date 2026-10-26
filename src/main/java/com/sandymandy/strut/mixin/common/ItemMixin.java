package com.sandymandy.strut.mixin.common;

import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.StrutCacheHolder;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every item a slot for its animatable instance cache, so a {@code StrutItem} needs no field of its own
 */
@Mixin(Item.class)
public abstract class ItemMixin implements StrutCacheHolder {
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
