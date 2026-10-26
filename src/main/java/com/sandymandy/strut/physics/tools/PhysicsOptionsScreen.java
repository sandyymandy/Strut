package com.sandymandy.strut.physics.tools;

import java.util.Locale;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.config.PhysicsSettings;

/**
 * The physics options without Cloth Config: a plain screen of switches that saves to {@code config/strut-physics.json}. Opened from the cursor tool, and by
 * Mod Menu when Cloth Config is not installed
 */
public final class PhysicsOptionsScreen extends Screen {
	private final Screen parent;

	PhysicsOptionsScreen(Screen parent) {
		super(Text.literal("Strut Physics"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 6;

		y = toggle(x, y, "Physics", PhysicsOptions::enabled, PhysicsOptions::enabled);

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Quality: " + PhysicsOptions.quality().name().toLowerCase(Locale.ROOT)), b -> {
			PhysicsOptions.Quality[] all = PhysicsOptions.Quality.values();

			PhysicsOptions.quality(all[(PhysicsOptions.quality().ordinal() + 1) % all.length]);
			b.setMessage(Text.literal("Quality: " + PhysicsOptions.quality().name().toLowerCase(Locale.ROOT)));
			PhysicsSettings.save();
		}).dimensions(x, y, 200, 20).build());
		y += 24;

		y = toggle(x, y, "Collide with blocks", PhysicsOptions::worldCollision, PhysicsOptions::worldCollision);
		y = toggle(x, y, "Spring bones collide with blocks", PhysicsOptions::springWorldCollision, PhysicsOptions::springWorldCollision);
		y = toggle(x, y, "Cloth", PhysicsOptions::cloth, PhysicsOptions::cloth);
		y = toggle(x, y, "Water and lava", PhysicsOptions::water, PhysicsOptions::water);
		y = toggle(x, y, "Collide with other models and mobs", PhysicsOptions::otherCollision, PhysicsOptions::otherCollision);
		y = toggle(x, y, "Collide with players", PhysicsOptions::playerCollision, PhysicsOptions::playerCollision);

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Range: " + (int)PhysicsOptions.distanceLimit() + " blocks"), b -> {
			double next = PhysicsOptions.distanceLimit() >= 96 ? 16 : PhysicsOptions.distanceLimit() + 16;

			PhysicsOptions.distanceLimit(next);
			b.setMessage(Text.literal("Range: " + (int)PhysicsOptions.distanceLimit() + " blocks"));
			PhysicsSettings.save();
		}).dimensions(x, y, 200, 20).build());

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x, this.height - 40, 200, 20).build());
	}

	private int toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set) {
		this.addDrawableChild(ButtonWidget.builder(text(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(text(label, get.getAsBoolean()));
			PhysicsSettings.save();
		}).dimensions(x, y, 200, 20).build());

		return y + 24;
	}

	private static Text text(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "On" : "Off"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}
}
