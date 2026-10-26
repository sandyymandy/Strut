package com.sandymandy.strut.animation;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code animTime}, the frame-time clock it is derived from, and the
 * re-render short-circuit all used to live on the shared {@link com.sandymandy.strut.model.StrutModel}
 * - one clock per animatable <em>type</em>, not per instance. Two instances of one type animated at
 * different speeds or phases would accumulate onto the same {@code animTime}, and the re-render
 * guard could miss or false-skip one instance depending on what else rendered in between.
 * <p>
 * They now live on {@link AnimatableManager}, which is already one-per-instance. This proves that
 * scoping directly: two managers advanced by different amounts must end up with different
 * {@code animTime}s, and the re-render guard must be evaluated per manager, not shared
 */
class AnimatableManagerClockTest {
	@Test
	@DisplayName("two managers of one type advance their own animTime independently")
	void twoManagersDoNotShareAClock() {
		AnimatableManager<FakeAnimatable> first = new AnimatableManager<>(new FakeAnimatable());
		AnimatableManager<FakeAnimatable> second = new AnimatableManager<>(new FakeAnimatable());

		first.advanceAnimTime(5);
		second.advanceAnimTime(1);

		assertEquals(5, first.getAnimTime(), "the first manager's clock must reflect only its own advances");
		assertEquals(1, second.getAnimTime(), "the second manager's clock must not have accumulated the first manager's time");

		first.advanceAnimTime(12);
		second.advanceAnimTime(2);

		assertEquals(12, first.getAnimTime(), "advancing one manager again must not touch the other");
		assertEquals(2, second.getAnimTime(), "each manager's delta is against its own last call, not a shared one");
	}

	@Test
	@DisplayName("the re-render guard is scoped per manager, not shared across instances of one type")
	void reRenderGuardIsPerManager() {
		AnimatableManager<FakeAnimatable> first = new AnimatableManager<>(new FakeAnimatable());
		AnimatableManager<FakeAnimatable> second = new AnimatableManager<>(new FakeAnimatable());

		first.markRendered(1L);

		assertTrue(first.wasLastRendered(1L), "a manager must recognise its own instance id as already rendered");
		assertFalse(second.wasLastRendered(1L),
				"a different manager must not report another instance's id as already rendered, " +
						"even though the old shared field would have if it were the last one written");

		second.markRendered(2L);

		assertTrue(first.wasLastRendered(1L), "marking the second manager must not clear the first manager's own record");
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
