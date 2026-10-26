package com.sandymandy.strut.network.packet;

import net.minecraft.util.math.BlockPos;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.animatable.stateless.StatelessStrutBlockEntity;
import com.sandymandy.strut.animation.RawAnimation;
import com.sandymandy.strut.network.packet.MultiloaderPacket;
import com.sandymandy.strut.util.ClientUtil;

import java.util.function.Consumer;

public record StatelessBlockEntityPlayAnimPacket(BlockPos blockPos, RawAnimation animation) implements MultiloaderPacket {
    public static final Id<StatelessBlockEntityPlayAnimPacket> TYPE = new Id<>(StrutConstants.id("stateless_block_entity_play_anim"));
    public static final PacketCodec<PacketByteBuf, StatelessBlockEntityPlayAnimPacket> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, StatelessBlockEntityPlayAnimPacket::blockPos,
            RawAnimation.STREAM_CODEC, StatelessBlockEntityPlayAnimPacket::animation,
            StatelessBlockEntityPlayAnimPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            if (ClientUtil.getLevel().getBlockEntity(this.blockPos) instanceof StrutBlockEntity blockEntity && blockEntity instanceof StatelessStrutBlockEntity statelessAnimatable)
                statelessAnimatable.handleClientAnimationPlay(blockEntity, 0, this.animation);
        });
    }
}
