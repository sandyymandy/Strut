package com.sandymandy.strut.physics.tools;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu's entry point (loaded only by Mod Menu): the Cloth Config screen if that mod is present, the plain options screen otherwise
 */
public final class PhysicsModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> FabricLoader.getInstance().isModLoaded("cloth-config") ? ClothConfigScreens.create(parent) : new PhysicsOptionsScreen(parent);
	}
}
