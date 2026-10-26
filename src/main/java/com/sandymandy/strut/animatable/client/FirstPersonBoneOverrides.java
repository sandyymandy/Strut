package com.sandymandy.strut.animatable.client;

import com.sandymandy.strut.animatable.EntityBoneOverrides;
import com.sandymandy.strut.animation.override.OverrideScope;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * The canonical per-viewer bone override, ready to use rather than hand-rolled per mod: hide a bone
 * exactly while the local client is looking through {@code entity}'s own camera in first person -
 * the proof that {@link OverrideScope#FRAME} and local-beats-synced precedence actually
 * work end to end
 * <p>
 * Client-only, by necessity - {@link MinecraftClient#cameraEntity} and
 * {@link net.minecraft.client.option.Perspective#isFirstPerson()} have no server-side meaning at
 * all, which is exactly the point: this is <em>viewer</em> state, a decision only this specific
 * client can make about what it itself is looking at, so it lives in
 * {@code com.sandymandy.strut.animatable.client} rather than the common {@code animatable} package
 * {@link EntityBoneOverrides} itself lives in
 * <p>
 * Not wired to anything automatically - nothing in this repository replaces vanilla player
 * rendering, so there is no concrete "head bone" to hide by default. A {@code StrutReplacedEntity}
 * that does replace player rendering calls this once per client frame, naming its own head bone,
 * typically from a {@code WorldRenderEvents.START} registration (before that frame's entities
 * render) alongside {@code StrutClient}'s own {@code WorldRenderEvents.END} sweep that clears every
 * {@link OverrideScope#FRAME} override at the end of the same frame - together they give exactly
 * one frame's lifetime per call, re-evaluated fresh every frame, so pressing F5 takes effect
 * immediately rather than waiting for a tick
 */
public final class FirstPersonBoneOverrides {
	private FirstPersonBoneOverrides() {}

	/**
	 * Hides {@code bone} on {@code entity}, for this client only, exactly while {@code entity} is
	 * this client's own camera entity and the camera is in first person - a no-op otherwise, relying
	 * on {@code StrutClient}'s own end-of-frame sweep to drop whatever was set on a previous frame
	 * once the condition stops holding
	 *
	 * @param overrides {@code entity.overrides()} (or {@code entity.overrides(relatedEntity)}), the
	 *                  caller's own handle - not looked up here, so this stays a pure function of
	 *                  its arguments rather than re-deriving one
	 * @param entity The entity whose camera is being checked - the same one {@code overrides}
	 *               addresses, not necessarily {@code MinecraftClient#cameraEntity} itself
	 * @param bone The bone to hide, by name, when the condition holds
	 */
	public static void applyOwnCameraRule(EntityBoneOverrides overrides, Entity entity, String bone) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client.cameraEntity == entity && client.options.getPerspective().isFirstPerson())
			overrides.setHidden(bone, true, OverrideScope.FRAME, 0, 1f, 0f);
	}
}
