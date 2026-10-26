package com.sandymandy.strut.platform;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registry;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.service.StrutPlatform;

import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Fabric service for general loader-specific functions
 */
public final class StrutFabric implements StrutPlatform {
    /**
     * @return Whether the current runtime is an in-dev (non-production) environment, for running debug-only tasks
     */
    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /**
     * @return The root game directory (./run)
     */
    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    /**
     * @return Whether the current runtime is on the client side regardless of logical context
     */
    @Override
    public boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    /**
     * Register a {@link ComponentType}
     * <p>
     * This is mostly just used for storing the animatable ID on ItemStacks
     */
    @Override
    public <T> Supplier<ComponentType<T>> registerDataComponent(String id, UnaryOperator<ComponentType.Builder<T>> builder) {
        final ComponentType<T> componentType = Registry.register(Registries.DATA_COMPONENT_TYPE, StrutConstants.id(id).toString(), builder.apply(ComponentType.builder()).build());

        return () -> componentType;
    }
}
