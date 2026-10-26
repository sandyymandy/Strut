package com.sandymandy.strut.animatable.instance;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimatableManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SingletonAnimatableInstanceCache#managers} used to be an
 * unbounded map - every distinct instance id (in practice, every ItemStack ever rendered) leaked
 * an {@link AnimatableManager} for the rest of the session, since there is no lifecycle hook that
 * tells this cache when the object behind an id is gone.
 * <p>
 * This proves the fix stays a fix: rendering far more distinct ids than the tracking cap must not
 * grow the cache without bound, and an id evicted for being least-recently-used must come back as
 * a fresh manager, not one holding onto stale state
 */
class SingletonAnimatableInstanceCacheEvictionTest {
	private static final int MAX_TRACKED_INSTANCES = 4096;

	@Test
	@DisplayName("rendering many distinct ids does not grow the manager map without bound")
	void manyDistinctIdsDoNotGrowTheCacheUnbounded() {
		SingletonAnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(new FakeAnimatable());

		for (long id = 0; id < MAX_TRACKED_INSTANCES * 4L; id++) {
			cache.getManagerForId(id);
		}

		assertEquals(MAX_TRACKED_INSTANCES, cache.trackedInstanceCount(),
				"the cache must settle at its tracking cap, not grow with every new id it has ever seen");
	}

	@Test
	@DisplayName("an id evicted for being least recently used comes back as a fresh manager")
	void evictedIdGetsAFreshManager() {
		SingletonAnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(new FakeAnimatable());

		AnimatableManager<?> original = cache.getManagerForId(0);

		for (long id = 1; id <= MAX_TRACKED_INSTANCES; id++) {
			cache.getManagerForId(id);
		}

		AnimatableManager<?> afterEviction = cache.getManagerForId(0);

		assertNotSame(original, afterEviction,
				"id 0 was never touched again while every other id filled the cache past its cap, " +
						"so it must have been evicted as least-recently-used rather than kept forever");
	}

	@Test
	@DisplayName("touching an id keeps it from being the next one evicted")
	void touchingAnIdProtectsItFromEviction() {
		SingletonAnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(new FakeAnimatable());

		AnimatableManager<?> zero = cache.getManagerForId(0);

		for (long id = 1; id < MAX_TRACKED_INSTANCES; id++) {
			cache.getManagerForId(id);
		}

		cache.getManagerForId(0); // touch id 0 again so it's no longer the least recently used
		cache.getManagerForId(MAX_TRACKED_INSTANCES); // pushes the cache one past its cap

		assertSame(zero, cache.getManagerForId(0),
				"id 0 was re-touched most recently among the original entries, so id 1 - not id 0 - should have been evicted");
	}

	private static final class FakeAnimatable implements StrutAnimatable {
		private final AnimatableInstanceCache cache = new InstancedAnimatableInstanceCache(this);

		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return this.cache;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}
}
