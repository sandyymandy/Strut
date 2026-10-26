package com.sandymandy.strut.client;

import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.animation.authority.ClientAnimationState;
import com.sandymandy.strut.animation.authority.ClientAnimationStates;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.StrutEvents;
import com.sandymandy.strut.event.dispatch.ClientEventDispatcher;
import com.sandymandy.strut.event.dispatch.ClipResolution;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the client half of typed events once per client tick
 * <p>
 * The one place the event dispatcher touches the running game: it walks every entity the client mirrors
 * animation state for, finds the live entity, and hands {@link ClientEventDispatcher} the pieces. It
 * runs on the <b>client tick</b> rather than in a renderer on purpose - vanilla culls an entity before it
 * is ever rendered, so anything fired from rendering is silently lost for an entity behind the camera,
 * and a sound from something behind you should still be heard.
 * <p>
 * Lives outside {@code event/} because it names {@link MinecraftClient}, and that whole package is
 * guarded against client references so the event system can run on a dedicated server
 */
public final class ClientEventSweep {
	private static final ClientEventDispatcher DISPATCHER =
			new ClientEventDispatcher(StrutEvents.clientListeners(), StrutEvents.clientLog());

	private ClientEventSweep() {}

	/**
	 * Called once from {@link com.sandymandy.strut.StrutClient#onInitializeClient()}
	 */
	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ClientEventSweep::tick);
	}

	/**
	 * Forgets an entity's cursors and log - on its unload, so a reused id starts clean
	 */
	public static void forget(int entityId) {
		DISPATCHER.forget(entityId);
		StrutEvents.clientLog().forget(entityId);
	}

	private static void tick(MinecraftClient client) {
		ClientWorld world = client.world;

		if (world == null)
			return;

		// A paused game does not advance the world's clock, so a sweep would only re-cover the same instant
		if (client.isPaused())
			return;

		for (int id : ClientAnimationStates.entityIds()) {
			Entity entity = world.getEntityById(id);
			ClientAnimationState state = ClientAnimationStates.get(id);

			if (entity == null || state == null)
				continue;

			Consumer<ClientEventContext> hook = entity instanceof StrutEntity geo ? geo::onClientAnimationEvent : null;
			List<Identifier> declared = entity instanceof StrutEntity geo ? geo.getAnimationFiles() : List.of();

			// a player with a body: the body says what a StrutEntity says for itself
			if (entity instanceof PlayerEntity player) {
				PlayerBody body = StrutPlayers.body(player);

				if (body != null) {
					hook = body::onClientEvent;
					declared = body.animationFiles(StrutPlayers.variant(player));
				}
			}

			List<Identifier> declaredFiles = declared;

			DISPATCHER.sweep(new ClientEventDispatcher.Subject(id, entity.getUuid(), entity,
							StrutEvents.participants().isParticipant(entity, client.player), hook), state,
					clip -> ClipResolution.resolve(ClipTimelines.client(), declaredFiles, clip), world.getTime());
		}
	}
}
