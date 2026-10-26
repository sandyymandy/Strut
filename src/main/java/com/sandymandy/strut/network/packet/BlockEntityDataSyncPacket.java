package com.sandymandy.strut.network.packet;

import net.minecraft.util.math.BlockPos;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutBlockEntity;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.util.ClientUtil;

import java.util.function.Consumer;

public record BlockEntityDataSyncPacket<D>(BlockPos pos, SerializableDataTicket<D> dataTicket, D data) implements MultiloaderPacket {
    public static final CustomPayload.Id<BlockEntityDataSyncPacket<?>> TYPE = new Id<>(StrutConstants.id("blockentity_data_sync"));
    public static final PacketCodec<RegistryByteBuf, BlockEntityDataSyncPacket<?>> CODEC = PacketCodec.of((packet, buf) -> {
        SerializableDataTicket.STREAM_CODEC.encode(buf, packet.dataTicket);
        buf.writeBlockPos(packet.pos);
        ((PacketCodec)packet.dataTicket.streamCodec()).encode(buf, packet.data);
    }, buf -> {
        final SerializableDataTicket dataTicket = SerializableDataTicket.STREAM_CODEC.decode(buf);

        return new BlockEntityDataSyncPacket<>(buf.readBlockPos(), dataTicket, dataTicket.streamCodec().decode(buf));
    });

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            if (ClientUtil.getLevel().getBlockEntity(this.pos) instanceof StrutBlockEntity blockEntity)
                blockEntity.setAnimData(this.dataTicket, this.data);
        });
    }
}
