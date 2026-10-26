package com.sandymandy.strut.armour;

import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * The colours the game's armour trim materials are drawn in: the middle of each material's palette, which is what a trim pattern drawn in grey takes. A set can
 * give its own ({@code trims.colours} in its file), and a modded material is white unless it does
 */
final class TrimColours {
	private static final Map<String, Integer> VANILLA = Map.ofEntries(
			Map.entry("quartz", 0xE3D4C4), Map.entry("iron", 0xB0B0B0), Map.entry("netherite", 0x625859), Map.entry("redstone", 0x971607),
			Map.entry("copper", 0xB4684D), Map.entry("gold", 0xDEB12D), Map.entry("emerald", 0x11A036), Map.entry("diamond", 0x6EECD2),
			Map.entry("lapis", 0x416E97), Map.entry("amethyst", 0x9A5CC6));

	private TrimColours() {}

	/** The colour (packed RGB) of a vanilla trim material, white for any other */
	static int vanilla(Identifier material) {
		return material.getNamespace().equals("minecraft") ? VANILLA.getOrDefault(material.getPath(), 0xFFFFFF) : 0xFFFFFF;
	}
}
