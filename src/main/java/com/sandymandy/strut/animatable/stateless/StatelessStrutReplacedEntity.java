package com.sandymandy.strut.animatable.stateless;

import net.minecraft.entity.Entity;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutReplacedEntity;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.network.packet.StatelessEntityPlayAnimPacket;
import com.sandymandy.strut.network.packet.StatelessEntityStopAnimPacket;

/**
 * Extension of {@link StatelessAnimatable} for {@link StrutReplacedEntity} animatables
 */
public interface StatelessStrutReplacedEntity extends StatelessStrutSingletonAnimatable, StrutReplacedEntity {
    /**
     * Start or continue an animation, letting its pre-defined loop type determine whether it should loop or not
     */
    default void playAnimation(String animation, Entity relatedEntity) {
        playAnimation(animation, relatedEntity, relatedEntity.getId());
    }

    /**
     * Start or continue an animation, forcing it to loop continuously until stopped
     */
    default void playLoopingAnimation(String animation, Entity relatedEntity) {
        playLoopingAnimation(animation, relatedEntity, relatedEntity.getId());
    }

    /**
     * Start or continue an animation, then hold the pose at the end of the animation until otherwise stopped
     */
    default void playAndHoldAnimation(String animation, Entity relatedEntity) {
        playAndHoldAnimation(animation, relatedEntity, relatedEntity.getId());
    }

    /**
     * Stop an already-playing animation
     */
    default void stopAnimation(RawAnimation animation, Entity relatedEntity) {
        stopAnimation(animation, relatedEntity, relatedEntity.getId());
    }

    /**
     * Start or continue a pre-defined animation
     */
    default void playAnimation(RawAnimation animation, Entity relatedEntity) {
        playAnimation(animation, relatedEntity, relatedEntity.getId());
    }

    /**
     * Stop an already-playing animation
     */
    default void stopAnimation(String animation, Entity relatedEntity) {
        stopAnimation(animation, relatedEntity, relatedEntity.getId());
    }

    @Override
    default void playAnimation(RawAnimation animation, Entity relatedEntity, long instanceId) {
        if (relatedEntity.getWorld().isClient) {
            handleClientAnimationPlay(this, instanceId, animation);
        }
        else {
            StrutServices.NETWORK.sendToAllPlayersTrackingEntity(new StatelessEntityPlayAnimPacket((int)instanceId, true, animation), relatedEntity);
        }
    }

    @Override
    default void stopAnimation(String animation, Entity relatedEntity, long instanceId) {
        if (relatedEntity.getWorld().isClient) {
            handleClientAnimationStop(this, instanceId, animation);
        }
        else {
            StrutServices.NETWORK.sendToAllPlayersTrackingEntity(new StatelessEntityStopAnimPacket((int)instanceId, true, animation), relatedEntity);
        }
    }
}
