package com.sandymandy.strut.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a body's animation file names the clips {@link PlayerLocomotion} looks for. A file may call its walk clip {@code walk}, or
 * {@code animation.base.walk} (a Blockbench export names every clip {@code animation.<model>.<name>}), or {@code crouch_walk} where locomotion says
 * {@code sneak_walk}
 * <p>
 * For a role such as {@code walk}, the names tried are the role with the prefix, then each alias of it with the prefix, in that order. The defaults know that
 * {@code sneak} and {@code crouch} are the same thing; more are added with {@link PlayerBody.Builder#clipAlias}
 *
 * @param prefix put in front of every name: {@code "animation.base."} for a file whose clips are {@code animation.base.walk}; empty for none
 * @param aliases other names for a role, by role, tried after the role's own
 */
public record ClipNames(String prefix, Map<String, List<String>> aliases) {
	/** No prefix, and the aliases every body gets */
	public static final ClipNames DEFAULT = new ClipNames("", Map.of(
			"sneak_walk", List.of("crouch_walk"),
			"sneak_idle", List.of("crouch_idle"),
			"sneak", List.of("crouch")));

	public ClipNames {
		Map<String, List<String>> copy = new HashMap<>();

		aliases.forEach((role, names) -> copy.put(role, List.copyOf(names)));
		aliases = Map.copyOf(copy);
	}

	/**
	 * The names to look for, best first, for {@code role}
	 */
	public List<String> candidates(String role) {
		List<String> names = new ArrayList<>(2);

		names.add(this.prefix + role);

		for (String alias : this.aliases.getOrDefault(role, List.of()))
			names.add(this.prefix + alias);

		return names;
	}

	/**
	 * These names with {@code prefix} in place of the old one
	 */
	public ClipNames withPrefix(String prefix) {
		return new ClipNames(prefix, this.aliases);
	}

	/**
	 * These names with {@code alias} tried after the role's own and its other aliases
	 */
	public ClipNames withAlias(String role, String alias) {
		Map<String, List<String>> next = new HashMap<>(this.aliases);
		List<String> list = new ArrayList<>(next.getOrDefault(role, List.of()));

		list.add(alias);
		next.put(role, list);

		return new ClipNames(this.prefix, next);
	}
}
