package com.sandymandy.strut.event.builtin;

import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.binding.BindingRuntime;
import com.sandymandy.strut.event.binding.EventBindings;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * What the engine does on a client when a {@code strut:sound} or a {@code strut:marker} fires: play the
 * sound, or look the cue up in the entity's binding sets and carry out what it finds
 * <p>
 * Uses only {@code World#playSound}, which exists on the common {@code World} and does its work only on a
 * client world, so this needs no client class and the event package stays server-safe. Sounds play from the
 * entity's position; a {@code locator} bone is not honoured yet (it needs the posed bone's world position)
 * <p>
 * The engine plays sounds and nothing else. It has no particle system, so {@code strut:particle} has no
 * default playback: a mod that wants one registers a client handler or a binding action
 */
final class ClientSoundPlayback {
	private ClientSoundPlayback() {}

	/**
	 * {@code strut:sound}: a sound id plays as it is; a binding key is resolved through the entity's sets
	 */
	static void onSound(ClientEventContext context, SoundEventPayload payload) {
		Entity entity = context.entity();
		BindingRuntime.SoundSink sink = sinkFor(entity);

		if (!payload.isBindingKey()) {
			sink.play(payload.sound(), payload.volume(), payload.pitch());

			return;
		}

		EventBindings.runtime().run(context, payload.key(), payload.volume(), payload.pitch(), chainOf(entity), sink);
	}

	/**
	 * {@code strut:marker}: if the entity's sets bind the marker's name, carry that out. A marker nobody bound is
	 * the ordinary case and costs one map lookup - and nothing at all if no bindings are loaded, which is
	 * checked first so a game with no binding files never even touches the entity
	 */
	static void onMarker(ClientEventContext context, String name) {
		if (EventBindings.sets().isEmpty())
			return;

		Entity entity = context.entity();

		EventBindings.runtime().run(context, name, 1f, 1f, chainOf(entity), sinkFor(entity), false);
	}

	private static List<Identifier> chainOf(Entity entity) {
		if (entity instanceof PlayerEntity player) {
			PlayerBody body = StrutPlayers.body(player);

			if (body != null)
				return body.eventBindings(StrutPlayers.variant(player));
		}

		return entity instanceof StrutEntity geo ? geo.getEventBindings() : List.of();
	}

	private static BindingRuntime.SoundSink sinkFor(Entity entity) {
		return (sound, volume, pitch) -> entity.getWorld().playSound(entity.getX(), entity.getY(), entity.getZ(),
				SoundEvent.of(sound), entity.getSoundCategory(), volume, pitch, false);
	}
}
