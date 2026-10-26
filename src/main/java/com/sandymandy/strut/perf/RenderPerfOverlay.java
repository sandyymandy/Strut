package com.sandymandy.strut.perf;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import org.joml.Vector3f;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import com.sandymandy.strut.renderer.gpu.LightProbe;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen half of the render measurement harness - draws
 * {@link RenderPerfStats#summaryLines()} in the corner of the screen, toggled by a keybinding
 * rather than always on, since this is a development aid and not something a player should see by
 * default
 */
public final class RenderPerfOverlay {
	private static boolean visible = false;
	private static KeyBinding toggleKey;

	private RenderPerfOverlay() {}

	public static void init() {
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.strut.toggle_perf_overlay",
				InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.strut"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				visible = !visible;
			}
		});

		HudRenderCallback.EVENT.register(RenderPerfOverlay::render);
	}

	private static void render(DrawContext context, RenderTickCounter tickCounter) {
		if (!visible)
			return;

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		List<String> lines = new ArrayList<>(RenderPerfStats.summaryLines());
		MinecraftClient client = MinecraftClient.getInstance();

		// the lights the shading is given: what the probe finds for the player, and what the last model queued was lit by (sky, strongest block light, the rest)
		if (client.player != null) {
			lines.add("lights (you): " + describe(LightProbe.of(client.player)));
			lines.add("lights (last drawn): " + describe(GpuBatchQueue.lastLit()));
			lines.add("strongest block light: " + LightProbe.strongestSource());
		}
		int x = 6;
		int y = 6;

		for (String line : lines) {
			context.drawTextWithShadow(textRenderer, line, x, y, 0xFFFFFF);

			y += 10;
		}
	}

	private static String describe(LightProbe.Rig rig) {
		if (rig == null)
			return "none";

		StringBuilder out = new StringBuilder();

		for (int i = 0; i < LightProbe.LIGHTS; i++) {
			Vector3f light = rig.direction(i);

			out.append(String.format("[%.2f %.2f %.2f |%.2f| #%06X] ", light.x, light.y, light.z, light.length(), rig.packedColour(i)));
		}

		return out.toString();
	}
}
