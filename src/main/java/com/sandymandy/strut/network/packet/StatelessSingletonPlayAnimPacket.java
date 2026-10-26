package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.stateless.StatelessStrutSingletonAnimatable;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.util.StrutUtil;

import java.util.function.Consumer;

public record StatelessSingletonPlayAnimPacket(String syncableId, long instanceId, RawAnimation animation) implements MultiloaderPacket {
    public static final Id<StatelessSingletonPlayAnimPacket> TYPE = new Id<>(StrutConstants.id("stateless_singleton_play_anim"));
    public static final PacketCodec<PacketByteBuf, StatelessSingletonPlayAnimPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StatelessSingletonPlayAnimPacket::syncableId,
            PacketCodecs.VAR_LONG, StatelessSingletonPlayAnimPacket::instanceId,
            RawAnimation.STREAM_CODEC, StatelessSingletonPlayAnimPacket::animation,
            StatelessSingletonPlayAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            StrutAnimatable animatable = StrutUtil.getSyncedAnimatable(this.syncableId);

            if (animatable instanceof StatelessStrutSingletonAnimatable statelessAnimatable)
                statelessAnimatable.handleClientAnimationPlay(animatable, this.instanceId, this.animation);
        });
    }
}
