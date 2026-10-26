package com.sandymandy.strut.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.math.BlockPos;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import com.sandymandy.strut.StrutClient;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.network.packet.MultiloaderPacket;
import com.sandymandy.strut.service.StrutNetworking;

/**
 * Fabric service implementation for Strut's networking functionalities
 */
public final class StrutNetworkingFabric implements StrutNetworking {
    /**
     * Register a Strut packet for use
     * <p>
     * <b><u>FOR STRUT USE ONLY</u></b>
     */
    @Override
    public <B extends PacketByteBuf, P extends MultiloaderPacket> void registerPacketInternal(CustomPayload.Id<P> payloadType, PacketCodec<B, P> codec, boolean isClientBound) {
        if (isClientBound) {
            PayloadTypeRegistry.playS2C().register(payloadType, (PacketCodec<PacketByteBuf, P>)codec);

            if (StrutServices.PLATFORM.isPhysicalClient())
                StrutClient.registerPacket(payloadType);
        }
        else {
            PayloadTypeRegistry.playC2S().register(payloadType, (PacketCodec<PacketByteBuf, P>)codec);
            ServerPlayNetworking.registerGlobalReceiver(payloadType, (packet, context) -> packet.receiveMessage(context.player(), context.player().getServer()::execute));
        }
    }

    /**
     * Send a packet to all players currently tracking a given entity
     * <p>
     * Good as a shortcut for sending a packet to all players that may have an interest in a given entity or its dealings
     * <p>
     * Will also send the packet to the entity itself if the entity is also a player
     */
    @Override
    public void sendToAllPlayersTrackingEntity(MultiloaderPacket packet, Entity trackingEntity) {
        if (trackingEntity instanceof ServerPlayerEntity pl)
            sendToPlayer(packet, pl);

        for (ServerPlayerEntity player : PlayerLookup.tracking(trackingEntity)) {
            sendToPlayer(packet, player);
        }
    }

    /**
     * Send a packet to all players tracking a given block position
     */
    @Override
    public void sendToAllPlayersTrackingBlock(MultiloaderPacket packet, ServerWorld level, BlockPos pos) {
        for (ServerPlayerEntity player : PlayerLookup.tracking(level, pos)) {
            sendToPlayer(packet, player);
        }
    }

    /**
     * Send a packet to the given player
     */
    @Override
    public void sendToPlayer(MultiloaderPacket packet, ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, packet);
    }
}
