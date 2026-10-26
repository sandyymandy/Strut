package com.sandymandy.strut.network.packet;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Multiloader implementation of a packet base, for loader-agnostic packet handling
 */
public interface MultiloaderPacket extends CustomPayload {
    /**
     * Handle the message after being received and decoded
     * <p>
     * This method is side-agnostic, so make sure you call out to client proxies as needed
     * <p>
     * The player may be null if the packet is being sent before the player loads in
     */
    void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue);
}
