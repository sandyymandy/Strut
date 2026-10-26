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
import com.sandymandy.strut.util.StrutUtil;

import java.util.function.Consumer;

public record StatelessSingletonStopAnimPacket(String syncableId, long instanceId, String animation) implements MultiloaderPacket {
    public static final Id<StatelessSingletonStopAnimPacket> TYPE = new Id<>(StrutConstants.id("stateless_singleton_stop_anim"));
    public static final PacketCodec<PacketByteBuf, StatelessSingletonStopAnimPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StatelessSingletonStopAnimPacket::syncableId,
            PacketCodecs.VAR_LONG, StatelessSingletonStopAnimPacket::instanceId,
            PacketCodecs.STRING, StatelessSingletonStopAnimPacket::animation,
            StatelessSingletonStopAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            StrutAnimatable animatable = StrutUtil.getSyncedAnimatable(this.syncableId);

            if (animatable instanceof StatelessStrutSingletonAnimatable statelessAnimatable)
                statelessAnimatable.handleClientAnimationStop(animatable, this.instanceId, this.animation);
        });
    }
}
