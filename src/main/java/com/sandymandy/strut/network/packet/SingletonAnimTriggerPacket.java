package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.util.StrutUtil;

import java.util.function.Consumer;

public record SingletonAnimTriggerPacket(String syncableId, long instanceId, String controllerName, String animName) implements MultiloaderPacket {
    public static final CustomPayload.Id<SingletonAnimTriggerPacket> TYPE = new Id<>(StrutConstants.id("singleton_anim_trigger"));
    public static final PacketCodec<PacketByteBuf, SingletonAnimTriggerPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SingletonAnimTriggerPacket::syncableId,
            PacketCodecs.VAR_LONG, SingletonAnimTriggerPacket::instanceId,
            PacketCodecs.STRING, SingletonAnimTriggerPacket::controllerName,
            PacketCodecs.STRING, SingletonAnimTriggerPacket::animName,
            SingletonAnimTriggerPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            StrutAnimatable animatable = StrutUtil.getSyncedAnimatable(this.syncableId);

            if (animatable != null)
                animatable.getAnimatableInstanceCache().getManagerForId(this.instanceId).tryTriggerAnimation(this.controllerName, this.animName);
        });
    }
}
