package com.sandymandy.strut.cache;

import net.minecraft.registry.RegistryWrapper;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import com.sandymandy.strut.animatable.instance.SingletonAnimatableInstanceCache;

/**
 * Storage class that keeps track of the last animatable id used, and provides new ones on request
 * <p>
 * Generally only used for {@code Items}, but any {@link SingletonAnimatableInstanceCache singleton} will likely use this.
 */
public final class AnimatableIdCache extends PersistentState {
	private static final Type<AnimatableIdCache> FACTORY = new Type<>(AnimatableIdCache::new, AnimatableIdCache::new, null);
	private static final String DATA_KEY = "strut_id_cache";
	private long lastId;

	private AnimatableIdCache() {}

	private AnimatableIdCache(NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
		this.lastId = tag.getLong("last_id");
	}

	/**
	 * Get the next free id from the id cache
	 *
	 * @param level An arbitrary ServerWorld. It doesn't matter which one
	 * @return The next free ID, which is immediately reserved for use after calling this method
	 */
	public static long getFreeId(ServerWorld level) {
		return getCache(level).getNextId();
	}

	private long getNextId() {
		markDirty();

		return ++this.lastId;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
		tag.putLong("last_id", this.lastId);

		return tag;
	}

	private static AnimatableIdCache getCache(ServerWorld level) {
		return level.getServer().getOverworld().getPersistentStateManager().getOrCreate(FACTORY, DATA_KEY);
	}
}
