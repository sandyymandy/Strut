package com.sandymandy.strut.physics.tools;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.config.PhysicsSettings;

/**
 * The Cloth Config version of the options screen. Only this class touches Cloth Config, and it is only reached when that mod is loaded
 */
public final class ClothConfigScreens {
	private ClothConfigScreens() {}

	static Screen create(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Text.literal("Strut Physics")).setSavingRunnable(PhysicsSettings::save);
		ConfigCategory general = builder.getOrCreateCategory(Text.literal("Physics"));
		ConfigEntryBuilder entries = builder.entryBuilder();

		general.addEntry(entries.startBooleanToggle(Text.literal("Physics"), PhysicsOptions.enabled()).setDefaultValue(true)
				.setTooltip(Text.literal("The master switch: off draws every model exactly as animated")).setSaveConsumer(PhysicsOptions::enabled).build());
		general.addEntry(entries.startEnumSelector(Text.literal("Quality"), PhysicsOptions.Quality.class, PhysicsOptions.quality()).setDefaultValue(PhysicsOptions.Quality.MEDIUM)
				.setTooltip(Text.literal("Low relaxes constraints less and keeps fewer blocks and a shorter range: cheaper, looser. High is stiffer and costs more"))
				.setSaveConsumer(PhysicsOptions::quality).build());
		general.addEntry(entries.startBooleanToggle(Text.literal("Collide with blocks"), PhysicsOptions.worldCollision()).setDefaultValue(true)
				.setSaveConsumer(PhysicsOptions::worldCollision).build());
		general.addEntry(entries.startBooleanToggle(Text.literal("Spring bones collide with blocks"), PhysicsOptions.springWorldCollision()).setDefaultValue(true)
				.setTooltip(Text.literal("Jiggle bones set to collide with blocks are pushed off walls and floors")).setSaveConsumer(PhysicsOptions::springWorldCollision).build());
		general.addEntry(entries.startBooleanToggle(Text.literal("Water and lava"), PhysicsOptions.water()).setDefaultValue(true)
				.setTooltip(Text.literal("Hair, tails and cloth float, slow down and are carried by the current in water and lava")).setSaveConsumer(PhysicsOptions::water).build());
		general.addEntry(entries.startBooleanToggle(Text.literal("Cloth"), PhysicsOptions.cloth()).setDefaultValue(true)
				.setTooltip(Text.literal("Skirts, capes and other cloth made from a model's mesh; the costliest part of the module")).setSaveConsumer(PhysicsOptions::cloth).build());
		general.addEntry(entries.startBooleanToggle(Text.literal("Collide with other models and mobs"), PhysicsOptions.otherCollision()).setDefaultValue(true)
				.setSaveConsumer(PhysicsOptions::otherCollision).build());
		general.addEntry(entries.startBooleanToggle(Text.literal("Collide with players"), PhysicsOptions.playerCollision()).setDefaultValue(true)
				.setSaveConsumer(PhysicsOptions::playerCollision).build());
		general.addEntry(entries.startIntSlider(Text.literal("Range (blocks)"), (int)PhysicsOptions.distanceLimit(), 8, 128).setDefaultValue(48)
				.setTooltip(Text.literal("Models farther than this from the camera are drawn as animated")).setSaveConsumer(v -> PhysicsOptions.distanceLimit(v)).build());

		return builder.build();
	}
}
