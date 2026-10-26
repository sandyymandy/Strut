package com.sandymandy.strut.animatable.stateless;

import net.minecraft.entity.Entity;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.SingletonStrutAnimatable;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.network.packet.StatelessSingletonPlayAnimPacket;
import com.sandymandy.strut.network.packet.StatelessSingletonStopAnimPacket;
import com.sandymandy.strut.util.StrutUtil;

/**
 * Extension of {@link StatelessAnimatable} for {@link SingletonStrutAnimatable} animatables.
 * <p>
 * Animatables <b><u>MUST</u></b> be registered with {@link SingletonStrutAnimatable#registerSyncedAnimatable} to use this interface
 */
public non-sealed interface StatelessStrutSingletonAnimatable extends StatelessAnimatable, SingletonStrutAnimatable {
    /**
     * Start or continue an animation, letting its pre-defined loop type determine whether it should loop or not
     */
    default void playAnimation(String animation, Entity relatedEntity, long instanceId) {
        playAnimation(RawAnimation.begin().thenPlay(animation), relatedEntity, instanceId);
    }

    /**
     * Start or continue an animation, forcing it to loop continuously until stopped
     */
    default void playLoopingAnimation(String animation, Entity relatedEntity, long instanceId) {
        playAnimation(RawAnimation.begin().thenLoop(animation), relatedEntity, instanceId);
    }

    /**
     * Start or continue an animation, then hold the pose at the end of the animation until otherwise stopped
     */
    default void playAndHoldAnimation(String animation, Entity relatedEntity, long instanceId) {
        playAnimation(RawAnimation.begin().thenPlayAndHold(animation), relatedEntity, instanceId);
    }

    /**
     * Stop an already-playing animation
     */
    default void stopAnimation(RawAnimation animation, Entity relatedEntity, long instanceId) {
        stopAnimation(animation.getStageCount() == 1 ? animation.getAnimationStages().getFirst().animationName() : animation.toString(), relatedEntity, instanceId);
    }

    /**
     * Start or continue a pre-defined animation
     */
    default void playAnimation(RawAnimation animation, Entity relatedEntity, long instanceId) {
        if (relatedEntity.getWorld().isClient) {
            handleClientAnimationPlay(this, instanceId, animation);
        }
        else {
            StrutServices.NETWORK.sendToAllPlayersTrackingEntity(new StatelessSingletonPlayAnimPacket(StrutUtil.getSyncedSingletonAnimatableId(this), instanceId, animation), relatedEntity);
        }
    }

    /**
     * Stop an already-playing animation
     */
    default void stopAnimation(String animation, Entity relatedEntity, long instanceId) {
        if (relatedEntity.getWorld().isClient) {
            handleClientAnimationStop(this, instanceId, animation);
        }
        else {
            StrutServices.NETWORK.sendToAllPlayersTrackingEntity(new StatelessSingletonStopAnimPacket(StrutUtil.getSyncedSingletonAnimatableId(this), instanceId, animation), relatedEntity);
        }
    }

    // Unsupported method handlers below; do not use

    /**
     * @deprecated Use {@link #playAnimation(String, Entity, long)} instead.
     */
    @Deprecated
    default void playAnimation(String animation) {
        throw new IllegalStateException("Cannot use non-level method handlers on StatelessSingletonStrutAnimatable");
    }

    /**
     * @deprecated Use {@link #playLoopingAnimation(String, Entity, long)} instead.
     */
    @Deprecated
    default void playLoopingAnimation(String animation) {
        throw new IllegalStateException("Cannot use non-level method handlers on StatelessSingletonStrutAnimatable");
    }

    /**
     * @deprecated Use {@link #playAndHoldAnimation(String, Entity, long)} instead.
     */
    @Deprecated
    default void playAndHoldAnimation(String animation) {
        throw new IllegalStateException("Cannot use non-level method handlers on StatelessSingletonStrutAnimatable");
    }

    /**
     * @deprecated Use {@link #stopAnimation(RawAnimation, Entity, long)} instead.
     */
    @Deprecated
    default void stopAnimation(RawAnimation animation) {
        throw new IllegalStateException("Cannot use non-level method handlers on StatelessSingletonStrutAnimatable");
    }

    /**
     * @deprecated Use {@link #playAnimation(RawAnimation, Entity, long)} instead.
     */
    @Deprecated
    default void playAnimation(RawAnimation animation) {
        throw new IllegalStateException("Cannot use non-level method handlers on StatelessSingletonStrutAnimatable");
    }

    /**
     * @deprecated Use {@link #stopAnimation(String, Entity, long)} instead.
     */
    @Deprecated
    default void stopAnimation(String animation) {
        throw new IllegalStateException("Cannot use non-level method handlers on StatelessSingletonStrutAnimatable");
    }
}
