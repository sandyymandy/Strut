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
import com.sandymandy.strut.animatable.stateless.StatelessStrutBlockEntity;
import com.sandymandy.strut.network.packet.MultiloaderPacket;
import com.sandymandy.strut.util.ClientUtil;

import java.util.function.Consumer;

public record StatelessBlockEntityStopAnimPacket(BlockPos blockPos, String animation) implements MultiloaderPacket {
    public static final Id<StatelessBlockEntityStopAnimPacket> TYPE = new Id<>(StrutConstants.id("stateless_block_entity_stop_anim"));
    public static final PacketCodec<PacketByteBuf, StatelessBlockEntityStopAnimPacket> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, StatelessBlockEntityStopAnimPacket::blockPos,
            PacketCodecs.STRING, StatelessBlockEntityStopAnimPacket::animation,
            StatelessBlockEntityStopAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            if (ClientUtil.getLevel().getBlockEntity(this.blockPos) instanceof StrutBlockEntity blockEntity && blockEntity instanceof StatelessStrutBlockEntity statelessAnimatable)
                statelessAnimatable.handleClientAnimationStop(blockEntity, 0, this.animation);
        });
    }
}
