package com.sandymandy.strut.renderer;

import com.sandymandy.strut.renderer.iris.IrisCompat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Whether the world is being rendered right now (the main pass or a shader pack's shadow pass), as opposed to something else that draws an entity: the
 * inventory's paper doll, a menu that shows a character
 * <p>
 * The GPU path queues an entity and draws it once, when the world has drawn its entities, in the world's view: for an entity drawn into a screen that draw
 * never comes, and the entity is not there. The CPU walk draws into whatever it is given, at once. And an entity drawn into a screen is drawn turned toward the
 * mouse, in another scale and place, so what it learns about its bones then (where they are) is not where they are in the world
 */
public final class RenderPass {
	private static boolean inWorld;

	private RenderPass() {}

	/**
	 * Called once at startup: the world render events say when a world render starts and ends
	 */
	public static void init() {
		WorldRenderEvents.START.register(context -> inWorld = true);
		WorldRenderEvents.END.register(context -> inWorld = false);
	}

	/** Added to an entity's id for the animation state it has when it is drawn into a screen */
	private static final long SCREEN_INSTANCE_BASE = 1L << 40;

	/**
	 * The id the animation state of {@code entityId} is kept under for this draw: its own id in the world, and another one in a screen. The inventory's paper
	 * doll is the player turned toward the mouse and drawn at another time in the frame; with the player's own state it would move their pose, and what
	 * the world draws next, along with it
	 */
	public static long instanceId(int entityId) {
		return worldPass() ? entityId : SCREEN_INSTANCE_BASE + entityId;
	}

	/**
	 * Whether an entity drawn now is drawn in the world: the world is being rendered, or the shadow pass of a shader pack is
	 */
	public static boolean worldPass() {
		return inWorld || IrisCompat.isRenderingShadowPass();
	}
}
