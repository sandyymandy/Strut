package com.sandymandy.strut.event.binding;

import com.google.gson.JsonElement;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.dispatch.EventStats;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Carries out a binding: plays its sounds, picks from its random pool, and runs a mod's own actions
 * <p>
 * All randomness comes from one {@link ClientEventContext#random()} taken once per firing and used in a
 * fixed order (the random pool first, then a mod's actions in the order they were parsed), so every client
 * makes the same choices for the same firing. The sound output is a {@link SoundSink} so this can be tested
 * without a world
 */
public final class BindingRuntime {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	/**
	 * Where sounds go: the game plays them at the entity, a test records them
	 */
	@FunctionalInterface
	public interface SoundSink {
		void play(Identifier sound, float volume, float pitch);
	}

	private final BindingSets sets;
	private final EventBindingActions actions;

	public BindingRuntime(BindingSets sets, EventBindingActions actions) {
		this.sets = sets;
		this.actions = actions;
	}

	/**
	 * Runs whatever {@code key} is bound to for an entity with the given fallback {@code chain}
	 *
	 * @param volume Multiplies the volume of every sound the binding plays
	 * @param pitch Multiplies the pitch of every sound the binding plays
	 * @return Whether any set in the chain defined the key. False is normal - most cues are unbound for most
	 *         characters - and is counted in {@link EventStats#unboundKeys()} so a mistyped key is findable
	 */
	public boolean run(ClientEventContext context, String key, float volume, float pitch, List<Identifier> chain, SoundSink sink) {
		return run(context, key, volume, pitch, chain, sink, true);
	}

	/**
	 * {@link #run(ClientEventContext, String, float, float, List, SoundSink)}, choosing whether an unbound key is
	 * counted. A sound key nobody bound is worth counting; a marker nobody bound is the normal case (most markers
	 * are for gameplay, not presentation) and would only bury the signal
	 */
	public boolean run(ClientEventContext context, String key, float volume, float pitch, List<Identifier> chain, SoundSink sink,
					   boolean countUnbound) {
		Optional<Binding> found = this.sets.find(chain, key);

		if (found.isEmpty()) {
			if (countUnbound)
				EventStats.recordUnboundKey();

			return false;
		}

		Binding binding = found.get();
		RandomGenerator random = context.random();

		for (Identifier sound : binding.sounds()) {
			sink.play(sound, volume, pitch);
		}

		if (!binding.randomSounds().isEmpty()) {
			WeightedSound picked = WeightedPick.pick(binding.randomSounds(), random);

			if (!picked.isSilence())
				sink.play(picked.sound(), volume, pitch);
		}

		for (Map.Entry<Identifier, JsonElement> custom : binding.custom().entrySet()) {
			BindingAction action = this.actions.get(custom.getKey());

			if (action == null)
				continue;

			try {
				action.run(context, custom.getValue());
			}
			catch (RuntimeException ex) {
				LOGGER.error("[Strut events] the binding action {} threw for key \"{}\"", custom.getKey(), key, ex);
			}
		}

		return true;
	}
}
