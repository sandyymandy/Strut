package com.sandymandy.strut.animatable.stateless;

import net.minecraft.entity.Entity;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.network.packet.StatelessEntityPlayAnimPacket;
import com.sandymandy.strut.network.packet.StatelessEntityStopAnimPacket;

/**
 * Extension of {@link StatelessAnimatable} for {@link StrutEntity} animatables
 */
public non-sealed interface StatelessStrutEntity extends StatelessAnimatable, StrutEntity {
    /**
     * Start or continue a pre-defined animation
     */
    @Override
    default void playAnimation(RawAnimation animation) {
        if (!(this instanceof Entity self))
            throw new ClassCastException("Cannot use StatelessStrutEntity on a non-entity animatable!");

        if (self.getWorld().isClient) {
            handleClientAnimationPlay(this, self.getId(), animation);
        }
        else {
            StrutServices.NETWORK.sendToAllPlayersTrackingEntity(new StatelessEntityPlayAnimPacket(self.getId(), false, animation), self);
        }
    }

    /**
     * Stop an already-playing animation
     */
    @Override
    default void stopAnimation(String animation) {
        if (!(this instanceof Entity self))
            throw new ClassCastException("Cannot use StatelessStrutEntity on a non-entity animatable!");

        if (self.getWorld().isClient) {
            handleClientAnimationStop(this, self.getId(), animation);
        }
        else {
            StrutServices.NETWORK.sendToAllPlayersTrackingEntity(new StatelessEntityStopAnimPacket(self.getId(), false, animation), self);
        }
    }
}
