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

public record BlockEntityAnimTriggerPacket(BlockPos pos, String controllerName, String animName) implements MultiloaderPacket {
    public static final CustomPayload.Id<BlockEntityAnimTriggerPacket> TYPE = new Id<>(StrutConstants.id("blockentity_anim_trigger"));
    public static final PacketCodec<PacketByteBuf, BlockEntityAnimTriggerPacket> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, BlockEntityAnimTriggerPacket::pos,
            PacketCodecs.STRING, BlockEntityAnimTriggerPacket::controllerName,
            PacketCodecs.STRING, BlockEntityAnimTriggerPacket::animName,
            BlockEntityAnimTriggerPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            if (ClientUtil.getLevel().getBlockEntity(this.pos) instanceof StrutBlockEntity blockEntity)
                blockEntity.triggerAnim(this.controllerName.isEmpty() ? null : this.controllerName, this.animName);
        });
    }
}
