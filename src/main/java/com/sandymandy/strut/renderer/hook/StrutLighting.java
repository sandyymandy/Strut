package com.sandymandy.strut.renderer.hook;

import net.minecraft.block.Block;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The lights that shape and colour a model, which a mod can add to
 * <ul>
 *     <li>{@link #addProvider}: lights of its own, with a colour, from anything ({@link LightProvider})</li>
 *     <li>{@link #setBlockLightColour}: the colour the light of a glowing block has (white unless set), so a lamp of one colour lights a model that colour</li>
 * </ul>
 * A model is lit by up to four lights at once: the sky's, and the three strongest of the block lights and what providers add (the weaker are added together into the
 * last). A light of a colour tints the side of the model facing it, the way a coloured lamp does to a white wall
 */
public final class StrutLighting {
	private static final List<LightProvider> PROVIDERS = new CopyOnWriteArrayList<>();
	private static final Map<Block, Integer> BLOCK_COLOURS = new ConcurrentHashMap<>();

	private StrutLighting() {}

	/** Adds {@code provider}; it is asked about every model that is lit */
	public static void addProvider(LightProvider provider) {
		PROVIDERS.add(provider);
	}

	/** Takes {@code provider} out again */
	public static void removeProvider(LightProvider provider) {
		PROVIDERS.remove(provider);
	}

	/** The providers, for the renderer */
	public static List<LightProvider> providers() {
		return PROVIDERS;
	}

	/** Sets the colour ({@code 0xRRGGBB}) of the light {@code block} gives; the default is white, which does not tint */
	public static void setBlockLightColour(Block block, int rgb) {
		BLOCK_COLOURS.put(block, rgb & 0xFFFFFF);
	}

	/** The colour of the light {@code block} gives */
	public static int blockLightColour(Block block) {
		return BLOCK_COLOURS.getOrDefault(block, 0xFFFFFF);
	}
}
