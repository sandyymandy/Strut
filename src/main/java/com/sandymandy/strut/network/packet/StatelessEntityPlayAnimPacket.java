package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.stateless.StatelessAnimatable;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.util.ClientUtil;
import com.sandymandy.strut.util.RenderUtil;

import java.util.function.Consumer;

public record StatelessEntityPlayAnimPacket(int entityId, boolean isReplacedEntity, RawAnimation animation) implements MultiloaderPacket {
    public static final Id<StatelessEntityPlayAnimPacket> TYPE = new Id<>(StrutConstants.id("stateless_entity_play_anim"));
    public static final PacketCodec<PacketByteBuf, StatelessEntityPlayAnimPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, StatelessEntityPlayAnimPacket::entityId,
            PacketCodecs.BOOL, StatelessEntityPlayAnimPacket::isReplacedEntity,
            RawAnimation.STREAM_CODEC, StatelessEntityPlayAnimPacket::animation,
            StatelessEntityPlayAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            Entity entity = ClientUtil.getLevel().getEntityById(this.entityId);

            if (entity == null)
                return;

            StrutAnimatable animatable = this.isReplacedEntity ?
                                       RenderUtil.getReplacedAnimatable(entity.getType()) :
                                       entity instanceof StrutAnimatable entityAnimatable ? entityAnimatable : null;

            if (animatable instanceof StatelessAnimatable statelessAnimatable)
                statelessAnimatable.handleClientAnimationPlay(animatable, this.entityId, this.animation);
        });
    }
}
