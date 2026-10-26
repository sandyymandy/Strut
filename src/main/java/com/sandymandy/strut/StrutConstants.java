package com.sandymandy.strut;

import com.mojang.serialization.Codec;
import net.minecraft.component.ComponentType;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

/**
 * Holder class for several properties and/or handlers inherent to Strut
 */
public final class StrutConstants {
    public static final Logger LOGGER = LogManager.getLogger("Strut");
    public static final String MODID = "strut";

    public static final Supplier<ComponentType<Long>> STACK_ANIMATABLE_ID_COMPONENT = StrutServices.PLATFORM.registerDataComponent("stack_animatable_id", builder -> builder.codec(Codec.LONG).packetCodec(PacketCodecs.VAR_LONG));

    public static void init() {}

    /**
     * Helper method to create a Identifier predefined with Strut's {@link #MODID}
     */
    public static Identifier id(String path) {
        return Identifier.of(StrutConstants.MODID, path);
    }

    /**
     * Throw an exception pertaining to a specific resource
     * <p>
     * This mostly serves as a helper for consistent formatting of exceptions
     *
     * @param resource The location or id of the resource the error pertains to
     * @param message The error message to display
     */
    public static RuntimeException exception(Identifier resource, String message) {
        return new RuntimeException(resource + ": " + message);
    }

    /**
     * Throw an exception pertaining to a specific resource
     * <p>
     * This mostly serves as a helper for consistent formatting of exceptions
     *
     * @param resource The location or id of the resource the error pertains to
     * @param message The error message to display
     * @param exception The exception to throw
     */
    public static RuntimeException exception(Identifier resource, String message, Throwable exception) {
        return new RuntimeException(resource + ": " + message, exception);
    }
}
