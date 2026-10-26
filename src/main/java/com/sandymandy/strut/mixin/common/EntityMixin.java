package com.sandymandy.strut.mixin.common;

import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.StrutCacheHolder;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives every entity a slot for its animatable instance cache, so a {@code StrutEntity} needs no field of its own
 * <p>
 * One reference per entity, {@code null} until a Strut entity asks for its cache, so an entity that never animates pays
 * nothing more than the pointer
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements StrutCacheHolder {
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
