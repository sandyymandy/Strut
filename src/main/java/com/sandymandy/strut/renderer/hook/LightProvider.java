package com.sandymandy.strut.renderer.hook;

import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Adds lights to the ones the game's own light gives a model (the sun or the moon, and the blocks that glow): a mob that glows blue, a spell that lights what it
 * passes, the colour of a lamp. Register with {@link StrutLighting#addProvider}
 * <p>
 * Called on the render thread, a few times a second for each model, so it should be cheap: look at what is near, add what is there
 */
@FunctionalInterface
public interface LightProvider {
	/**
	 * Adds, with {@code sink}, the lights that reach the model of {@code animatable} (an entity, or a block entity) which is at {@code (x, y, z)} in {@code world}
	 * ({@code animatable} is null for a model that belongs to neither)
	 */
	void provide(@Nullable Object animatable, World world, double x, double y, double z, LightSink sink);
}
