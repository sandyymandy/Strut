package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.util.StrutUtil;

import java.util.function.Consumer;

public record StopTriggeredSingletonAnimPacket(String syncableId, long instanceId, String controllerName, String animName) implements MultiloaderPacket {
    public static final Id<StopTriggeredSingletonAnimPacket> TYPE = new Id<>(StrutConstants.id("stop_triggered_singleton_anim"));
    public static final PacketCodec<PacketByteBuf, StopTriggeredSingletonAnimPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, StopTriggeredSingletonAnimPacket::syncableId,
            PacketCodecs.VAR_LONG, StopTriggeredSingletonAnimPacket::instanceId,
            PacketCodecs.STRING, StopTriggeredSingletonAnimPacket::controllerName,
            PacketCodecs.STRING, StopTriggeredSingletonAnimPacket::animName,
            StopTriggeredSingletonAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            StrutAnimatable animatable = StrutUtil.getSyncedAnimatable(this.syncableId);

            if (animatable != null) {
                AnimatableManager<StrutAnimatable> animatableManager = animatable.getAnimatableInstanceCache().getManagerForId(this.instanceId);

                if (animatableManager != null)
                    animatableManager.stopTriggeredAnimation(this.controllerName.isEmpty() ? null : this.controllerName, this.animName.isEmpty() ? null : this.animName);
            }
        });
    }
}