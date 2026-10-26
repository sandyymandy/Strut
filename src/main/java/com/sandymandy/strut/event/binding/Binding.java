package com.sandymandy.strut.event.binding;

import com.google.gson.JsonElement;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

/**
 * Everything one binding key does when it fires: a map of <b>actions</b>, not a single action
 * <p>
 * A key routinely does several things at once - a fixed thump <i>and</i> one random cry, a sound <i>and</i>
 * a spoken line - so this holds all of them. The engine's two actions are {@code sounds} (all of them play)
 * and {@code random_sounds} (one weighted pick). Anything else is a mod's own action, keyed by its
 * namespaced id ({@code mymod:messages}) and handed its JSON value untouched: Strut has no idea what a chat
 * line or a camera shake is, and should not
 *
 * @param sounds Sounds that all play
 * @param randomSounds A pool of which one is picked, possibly to silence; empty for none
 * @param custom A mod's actions, by id, each with the JSON it was given
 */
public record Binding(List<Identifier> sounds, List<WeightedSound> randomSounds, Map<Identifier, JsonElement> custom) {
	public Binding {
		sounds = List.copyOf(sounds);
		randomSounds = List.copyOf(randomSounds);
		custom = Map.copyOf(custom);
	}

	public boolean isEmpty() {
		return this.sounds.isEmpty() && this.randomSounds.isEmpty() && this.custom.isEmpty();
	}
}
