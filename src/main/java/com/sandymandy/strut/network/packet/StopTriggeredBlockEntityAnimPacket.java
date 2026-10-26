package com.sandymandy.strut.network.packet;

import net.minecraft.util.math.BlockPos;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.util.ClientUtil;

import java.util.function.Consumer;

public record StopTriggeredBlockEntityAnimPacket(BlockPos pos, String controllerName, String animName) implements MultiloaderPacket {
    public static final Id<StopTriggeredBlockEntityAnimPacket> TYPE = new Id<>(StrutConstants.id("stop_triggered_blockentity_anim"));
    public static final PacketCodec<PacketByteBuf, StopTriggeredBlockEntityAnimPacket> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, StopTriggeredBlockEntityAnimPacket::pos,
            PacketCodecs.STRING, StopTriggeredBlockEntityAnimPacket::controllerName,
            PacketCodecs.STRING, StopTriggeredBlockEntityAnimPacket::animName,
            StopTriggeredBlockEntityAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            if (ClientUtil.getLevel().getBlockEntity(this.pos) instanceof StrutBlockEntity blockEntity)
                blockEntity.stopTriggeredAnim(this.controllerName.isEmpty() ? null : this.controllerName, this.animName.isEmpty() ? null : this.animName);
        });
    }
}