package com.sandymandy.strut.service;

import net.minecraft.component.ComponentType;

import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Loader-agnostic service interface for general loader-specific functions
 */
public interface StrutPlatform {
    /**
     * @return Whether the current runtime is an in-dev (non-production) environment, for running debug-only tasks
     */
    boolean isDevelopmentEnvironment();

    /**
     * @return Whether the current runtime is on the client side regardless of logical context
     */
    boolean isPhysicalClient();

    /**
     * @return The root game directory (./run)
     */
    Path getGameDir();

    /**
     * Register a {@link ComponentType}
     * <p>
     * This is mostly just used for storing the animatable ID on ItemStacks
     */
    <T> Supplier<ComponentType<T>> registerDataComponent(String id, UnaryOperator<ComponentType.Builder<T>> builder);
}
