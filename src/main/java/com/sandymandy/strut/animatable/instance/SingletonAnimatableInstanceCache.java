package com.sandymandy.strut.animatable.instance;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimatableManager;

import java.util.function.Supplier;

/**
 * AnimatableInstanceCache implementation for singleton/flyweight objects such as Items. Utilises a keyed map to differentiate different instances of the object
 * <p>
 * You should <b><u>NOT</u></b> be instantiating this directly unless you know what you are doing.
 * Use {@link com.sandymandy.strut.util.StrutUtil#createInstanceCache StrutUtil.createInstanceCache} instead
 */
public class SingletonAnimatableInstanceCache extends AnimatableInstanceCache {
	/**
	 * How many distinct instance ids this cache holds onto before evicting the least recently used
	 * one
	 * <p>
	 * Unlike {@link InstancedAnimatableInstanceCache}, which is owned directly by its one animatable
	 * and released whenever that animatable is, this cache is shared by every instance of a
	 * singleton object (e.g. every ItemStack of one Item) and has no lifecycle hook telling it when
	 * the object behind a given id is gone - stacks are transient value objects, never tracked
	 * globally. Left unbounded, {@link #managers} leaked one {@link AnimatableManager} (its
	 * controllers, snapshots, {@code SkinningState}, {@code ModelInstance}/{@code Pose}) per id for
	 * the rest of the session - every stack ever rendered, forever. Bounding by recency of use
	 * instead: comfortably above the number of stacks or armor slots actually on screen at once, so
	 * eviction only ever removes ids that are no longer relevant
	 */
	private static final int MAX_TRACKED_INSTANCES = 4096;

	protected final Long2ObjectLinkedOpenHashMap<AnimatableManager<?>> managers = new Long2ObjectLinkedOpenHashMap<>();

	public SingletonAnimatableInstanceCache(StrutAnimatable animatable) {
		super(animatable);
	}

	/**
	 * Gets an {@link AnimatableManager} instance from this cache, cached under the id provided, or a new one if one doesn't already exist
	 * <p>
	 * This subclass assumes that all animatable instances will be sharing this cache instance, and so differentiates data by ids
	 */
	@Override
	public AnimatableManager<?> getManagerForId(long uniqueId) {
		return computeIfAbsent(uniqueId, () -> new AnimatableManager<>(this.animatable));
	}

	/**
	 * Gets this cache's manager for {@code uniqueId}, creating it via {@code factory} if it isn't
	 * already cached, and marking it as the most recently used id either way
	 * <p>
	 * Shared with subclasses (e.g. {@link com.sandymandy.strut.animatable.StrutItem.ContextBasedAnimatableInstanceCache})
	 * that need a different manager type, so the eviction policy above lives in one place rather
	 * than being reimplemented per subclass
	 */
	protected final AnimatableManager<?> computeIfAbsent(long uniqueId, Supplier<AnimatableManager<?>> factory) {
		AnimatableManager<?> manager = this.managers.getAndMoveToLast(uniqueId);

		if (manager == null) {
			manager = factory.get();

			this.managers.putAndMoveToLast(uniqueId, manager);

			while (this.managers.size() > MAX_TRACKED_INSTANCES) {
				this.managers.removeFirst();
			}
		}

		return manager;
	}

	/**
	 * How many distinct instance ids this cache currently holds a manager for
	 * <p>
	 * Package-visible for {@code SingletonAnimatableInstanceCacheEvictionTest} - not meant as a
	 * public API, since callers have no use for the raw count
	 */
	int trackedInstanceCount() {
		return this.managers.size();
	}
}
