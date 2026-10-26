package com.sandymandy.strut.network.packet;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animatable.StrutReplacedEntity;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.util.ClientUtil;
import com.sandymandy.strut.util.RenderUtil;

import java.util.function.Consumer;

public record EntityDataSyncPacket<D>(int entityId, boolean isReplacedEntity, SerializableDataTicket<D> dataTicket, D data) implements MultiloaderPacket {
    public static final CustomPayload.Id<EntityDataSyncPacket<?>> TYPE = new Id<>(StrutConstants.id("entity_data_sync"));
    public static final PacketCodec<RegistryByteBuf, EntityDataSyncPacket<?>> CODEC = PacketCodec.of((packet, buf) -> {
        SerializableDataTicket.STREAM_CODEC.encode(buf, packet.dataTicket);
        buf.writeVarInt(packet.entityId);
        buf.writeBoolean(packet.isReplacedEntity);
        ((PacketCodec)packet.dataTicket.streamCodec()).encode(buf, packet.data);
    }, buf -> {
        final SerializableDataTicket dataTicket = SerializableDataTicket.STREAM_CODEC.decode(buf);

        return new EntityDataSyncPacket<>(buf.readVarInt(), buf.readBoolean(), dataTicket, dataTicket.streamCodec().decode(buf));
    });

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    @Override
    public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
        workQueue.accept(() -> {
            Entity entity = ClientUtil.getLevel().getEntityById(this.entityId);

            if (entity == null)
                return;

            if (!this.isReplacedEntity) {
                if (entity instanceof StrutEntity strutEntity)
                    strutEntity.setAnimData(this.dataTicket, this.data);

                return;
            }

            if (RenderUtil.getReplacedAnimatable(entity.getType()) instanceof StrutReplacedEntity replacedEntity)
                replacedEntity.setAnimData(entity, this.dataTicket, this.data);
        });
    }
}
