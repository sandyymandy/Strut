package com.sandymandy.strut.network.packet;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.SingletonStrutAnimatable;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.util.ClientUtil;
import com.sandymandy.strut.util.StrutUtil;

import java.util.function.Consumer;

public record SingletonDataSyncPacket<D>(String syncableId, long instanceId, SerializableDataTicket<D> dataTicket, D data) implements MultiloaderPacket {
    public static final CustomPayload.Id<SingletonDataSyncPacket<?>> TYPE = new Id<>(StrutConstants.id("singleton_data_sync"));
    public static final PacketCodec<RegistryByteBuf, SingletonDataSyncPacket<?>> CODEC = PacketCodec.of((packet, buf) -> {
        SerializableDataTicket.STREAM_CODEC.encode(buf, packet.dataTicket);
        buf.writeString(packet.syncableId);
        buf.writeVarLong(packet.instanceId);
        ((PacketCodec)packet.dataTicket.streamCodec()).encode(buf, packet.data);
    }, buf -> {
        final SerializableDataTicket dataTicket = SerializableDataTicket.STREAM_CODEC.decode(buf);

        return new SingletonDataSyncPacket<>(buf.readString(), buf.readVarLong(), dataTicket, dataTicket.streamCodec().decode(buf));
    });

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            StrutAnimatable animatable = StrutUtil.getSyncedAnimatable(this.syncableId);

            if (animatable instanceof SingletonStrutAnimatable singleton)
                singleton.setAnimData(ClientUtil.getClientPlayer(), this.instanceId, this.dataTicket, this.data);
        });
    }
}
