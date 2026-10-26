package com.sandymandy.strut.animatable.stateless;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.block.entity.BlockEntity;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.network.packet.StatelessBlockEntityPlayAnimPacket;
import com.sandymandy.strut.network.packet.StatelessBlockEntityStopAnimPacket;

/**
 * Extension of {@link StatelessAnimatable} for {@link StrutBlockEntity} animatables
 */
public non-sealed interface StatelessStrutBlockEntity extends StatelessAnimatable, StrutBlockEntity {
    /**
     * Start or continue a pre-defined animation
     */
    @Override
    default void playAnimation(RawAnimation animation) {
        if (!(this instanceof BlockEntity self))
            throw new ClassCastException("Cannot use StatelessStrutBlockEntity on a non-blockentity animatable!");

        if (self.getWorld() instanceof ServerWorld level) {
            StrutServices.NETWORK.sendToAllPlayersTrackingBlock(new StatelessBlockEntityPlayAnimPacket(self.getPos(), animation), level, self.getPos());
        }
        else {
            handleClientAnimationPlay(this, 0, animation);
        }
    }

    /**
     * Stop an already-playing animation
     */
    @Override
    default void stopAnimation(String animation) {
        if (!(this instanceof BlockEntity self))
            throw new ClassCastException("Cannot use StatelessStrutBlockEntity on a non-blockentity animatable!");

        if (self.getWorld() instanceof ServerWorld level) {
            StrutServices.NETWORK.sendToAllPlayersTrackingBlock(new StatelessBlockEntityStopAnimPacket(self.getPos(), animation), level, self.getPos());
        }
        else {
            handleClientAnimationStop(this, 0, animation);
        }
    }
}