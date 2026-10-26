package com.sandymandy.strut.network.packet;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animatable.StrutReplacedEntity;
import com.sandymandy.strut.util.ClientUtil;
import com.sandymandy.strut.util.RenderUtil;

import java.util.function.Consumer;

public record StopTriggeredEntityAnimPacket(int entityId, boolean isReplacedEntity, String controllerName, String animName) implements MultiloaderPacket {
    public static final Id<StopTriggeredEntityAnimPacket> TYPE = new Id<>(StrutConstants.id("stop_triggered_entity_anim"));
    public static final PacketCodec<PacketByteBuf, StopTriggeredEntityAnimPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, StopTriggeredEntityAnimPacket::entityId,
            PacketCodecs.BOOL, StopTriggeredEntityAnimPacket::isReplacedEntity,
            PacketCodecs.STRING, StopTriggeredEntityAnimPacket::controllerName,
            PacketCodecs.STRING, StopTriggeredEntityAnimPacket::animName,
            StopTriggeredEntityAnimPacket::new);

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
                    strutEntity.stopTriggeredAnim(this.controllerName.isEmpty() ? null : this.controllerName, this.animName.isEmpty() ? null : this.animName);

                return;
            }

            if (RenderUtil.getReplacedAnimatable(entity.getType()) instanceof StrutReplacedEntity replacedEntity)
                replacedEntity.stopTriggeredAnim(entity, this.controllerName.isEmpty() ? null : this.controllerName, this.animName.isEmpty() ? null : this.animName);
        });
    }
}