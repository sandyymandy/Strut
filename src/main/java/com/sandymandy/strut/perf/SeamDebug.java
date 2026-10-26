package com.sandymandy.strut.perf;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * A diagnostic for the hairline slits some models show, to tell a geometry gap from a texture problem. F10 cycles a mode that the GPU path's
 * fragment shader reads; the chat line says which is active. Off by default and never saved
 * <ul>
 * <li>0: normal</li>
 * <li>1: no alpha discard (transparent texels are drawn opaque). If the slits vanish, they were transparent texels being sampled</li>
 * <li>2: every quad a flat random colour, no texture at all. If the slits are still there, they are gaps in the geometry; if gone, they were texture</li>
 * <li>3: the texture read with an exact texel fetch, bypassing the sampler's filter, mipmap and wrap state. If the slits vanish, it was sampler state</li>
 * </ul>
 * Only the Strut shader is affected, not a shader pack's own program. Set {@code -Dstrut.debugSeams=N} to start in a mode
 */
public final class SeamDebug {
	private static final String[] MODES = {"off", "no alpha discard", "flat colour per quad, no texture", "exact texel fetch, no filtering"};
	private static volatile int mode = initial();
	private static KeyBinding key;

	private SeamDebug() {}

	private static int initial() {
		try {
			return Math.floorMod(Integer.parseInt(System.getProperty("strut.debugSeams", "0")), MODES.length);
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** The mode the shader should use this frame */
	public static int mode() {
		return mode;
	}

	public static void init() {
		key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.strut.cycle_seam_debug", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.strut"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.wasPressed()) {
				mode = (mode + 1) % MODES.length;

				if (client.player != null)
					client.player.sendMessage(Text.literal("Strut seam debug " + mode + ": " + MODES[mode]), true);
			}
		});
	}
}
