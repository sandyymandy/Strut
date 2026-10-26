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

public record EntityAnimTriggerPacket(int entityId, boolean isReplacedEntity, String controllerName, String animName) implements MultiloaderPacket {
    public static final CustomPayload.Id<EntityAnimTriggerPacket> TYPE = new Id<>(StrutConstants.id("entity_anim_trigger"));
    public static final PacketCodec<PacketByteBuf, EntityAnimTriggerPacket> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, EntityAnimTriggerPacket::entityId,
            PacketCodecs.BOOL, EntityAnimTriggerPacket::isReplacedEntity,
            PacketCodecs.STRING, EntityAnimTriggerPacket::controllerName,
            PacketCodecs.STRING, EntityAnimTriggerPacket::animName,
            EntityAnimTriggerPacket::new);

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
                    strutEntity.triggerAnim(this.controllerName.isEmpty() ? null : this.controllerName, this.animName);

                return;
            }

            if (RenderUtil.getReplacedAnimatable(entity.getType()) instanceof StrutReplacedEntity replacedEntity)
                replacedEntity.triggerAnim(entity, this.controllerName.isEmpty() ? null : this.controllerName, this.animName);
        });
    }
}
