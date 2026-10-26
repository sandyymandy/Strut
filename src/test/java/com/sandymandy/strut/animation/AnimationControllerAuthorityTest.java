package com.sandymandy.strut.animation;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animation.authority.AuthorityMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default-authority computation: a {@link StrutEntity} controller defaults to
 * {@link AuthorityMode#SERVER}, everything else to {@link AuthorityMode#CLIENT_COSMETIC}
 * <p>
 * {@code AnimationController#processServerAuthoritative} itself is not exercised here - it needs a live {@code Entity}
 * and {@code World} (to read {@code World#getTime()}), the same bootstrap wall every other
 * live-object-dependent class has hit. Its two riskiest pieces already have direct
 * coverage of their own: the clock math in {@code AnimationPlaybackTest} and the
 * sequence-guarded mirror lookup in {@code ClientAnimationStateTest}. What is new and
 * testable in isolation here is specifically which default a controller picks
 */
class AnimationControllerAuthorityTest {
	/** A bare {@link StrutEntity} that is deliberately not an {@code Entity} - just enough to exercise the default-authority check in isolation, without a live bootstrap */
	private static class FakeStrutEntity implements StrutEntity {
		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return null;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}

	/** A plain {@link StrutAnimatable}, implementing neither {@link StrutEntity} nor any other specific animatable kind */
	private static class FakeStrutAnimatable implements StrutAnimatable {
		@Override
		public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

		@Override
		public AnimatableInstanceCache getAnimatableInstanceCache() {
			return null;
		}

		@Override
		public double getTick(Object object) {
			return 0;
		}
	}

	@Test
	@DisplayName("a StrutEntity controller defaults to SERVER authority")
	void strutEntityDefaultsToServer() {
		AnimationController<FakeStrutEntity> controller =
				new AnimationController<>(new FakeStrutEntity(), "test", state -> PlayState.CONTINUE);

		assertEquals(AuthorityMode.SERVER, controller.getAuthorityMode());
	}

	/**
	 * The narrowing from §3: block entities, items, GUI previews and singletons are not
	 * {@link StrutEntity}, so they keep the original behaviour of deciding locally
	 */
	@Test
	@DisplayName("a non-StrutEntity controller defaults to CLIENT_COSMETIC")
	void nonStrutEntityDefaultsToClientCosmetic() {
		AnimationController<FakeStrutAnimatable> controller =
				new AnimationController<>(new FakeStrutAnimatable(), "test", state -> PlayState.CONTINUE);

		assertEquals(AuthorityMode.CLIENT_COSMETIC, controller.getAuthorityMode());
	}

	/**
	 * The escape hatch §3 promises: a {@link StrutEntity} controller that wants to stay
	 * client-decided (idle fidgeting, a look-at - anything purely cosmetic per-viewer) can opt back
	 * out explicitly
	 */
	@Test
	@DisplayName("authorityMode() overrides the default, fluently")
	void authorityModeOverridesDefault() {
		AnimationController<FakeStrutEntity> controller =
				new AnimationController<>(new FakeStrutEntity(), "test", state -> PlayState.CONTINUE);

		AnimationController<FakeStrutEntity> result = controller.authorityMode(AuthorityMode.CLIENT_COSMETIC);

		assertSame(controller, result, "the setter is fluent, returning this");
		assertEquals(AuthorityMode.CLIENT_COSMETIC, controller.getAuthorityMode());
	}
}
