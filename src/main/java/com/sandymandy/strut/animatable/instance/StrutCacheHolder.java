package com.sandymandy.strut.animatable.instance;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A slot for an {@link AnimatableInstanceCache} that Strut's mixins add to {@code Entity}
 * <p>
 * This is what lets a {@link com.sandymandy.strut.animatable.StrutEntity} skip the cache field, the getter and the
 * {@code createInstanceCache} call that every animatable used to declare: the entity already has the slot, and
 * {@code StrutEntity#getAnimatableInstanceCache} fills it on first use. A class that still declares its own cache
 * keeps working, because its override wins over the default
 * <p>
 * Not for mods to implement or call
 */
@ApiStatus.Internal
public interface StrutCacheHolder {
	@Nullable
	AnimatableInstanceCache strut$getCache();

	void strut$setCache(AnimatableInstanceCache cache);
}
