package com.sandymandy.strut.animatable.stateless;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.animation.PlayState;
import com.sandymandy.strut.animation.RawAnimation;

/**
 * Stateless wrapper for {@link AnimationController}
 *
 * @see StatelessAnimatable
 */
public class StatelessAnimationController extends AnimationController<StrutAnimatable> {
    @Nullable
    protected RawAnimation currentAnim = null;

    public StatelessAnimationController(StrutAnimatable animatable, String name) {
        super(animatable, name, state -> PlayState.STOP);
    }

    /**
     * Set the current animation for this controller
     * <p>
     * This will be used to handle the {@link AnimationState} at each render pass
     */
    public void setCurrentAnimation(@Nullable RawAnimation animation) {
        this.currentAnim = animation;
    }

    /**
     * Get the current animation state for this controller
     */
    @Nullable
    public RawAnimation getCurrentAnim() {
        return this.currentAnim;
    }

    @Override
    public AnimationStateHandler<StrutAnimatable> getStateHandler() {
        return this::overrideStateHandler;
    }

    @ApiStatus.Internal
    protected PlayState overrideStateHandler(AnimationState<StrutAnimatable> test) {
        return getCurrentAnim() == null ? PlayState.STOP : test.setAndContinue(getCurrentAnim());
    }
}
