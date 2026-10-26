package com.sandymandy.strut.event.binding;

import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every loaded {@link BindingSet}, and the lookup of a key through an entity's <b>fallback chain</b>
 * <p>
 * An entity names an ordered chain of sets - {@code [mymod:creatures/ava, mymod:creatures/default]} - and a key is looked up
 * in each in turn. <b>The first set that defines the key supplies the whole binding</b>; bindings are not
 * merged across sets, so a character that overrides {@code pound} replaces the default's {@code pound}
 * entirely rather than playing both. That is the rule that is easiest to predict when reading a file, and
 * the one that lets a character say "this cue is silent for me" by defining it empty
 * <p>
 * Replaced wholesale on every resource reload, so a set that a pack removed stops being found
 */
public final class BindingSets {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	private volatile Map<Identifier, BindingSet> sets = Map.of();
	private final Set<Identifier> warnedMissing = ConcurrentHashMap.newKeySet();

	/**
	 * Replaces every set, on a resource reload
	 */
	public void set(Map<Identifier, BindingSet> loaded) {
		this.sets = Map.copyOf(loaded);
		this.warnedMissing.clear();
	}

	public Optional<BindingSet> get(Identifier id) {
		return Optional.ofNullable(this.sets.get(id));
	}

	public boolean isEmpty() {
		return this.sets.isEmpty();
	}

	public int size() {
		return this.sets.size();
	}

	/**
	 * Looks {@code key} up through {@code chain}; the first set that defines it wins
	 * <p>
	 * A set named in the chain that is not loaded is skipped, with a one-time warning: it is almost always a
	 * typo or a pack that is not installed, and a chain that silently falls through to the default is exactly
	 * the kind of thing that takes an hour to notice
	 */
	public Optional<Binding> find(List<Identifier> chain, String key) {
		for (Identifier id : chain) {
			BindingSet set = this.sets.get(id);

			if (set == null) {
				if (this.warnedMissing.add(id))
					LOGGER.warn("[Strut events] an entity's event bindings name the set {}, which is not loaded; skipping it", id);

				continue;
			}

			Binding binding = set.get(key);

			if (binding != null)
				return Optional.of(binding);
		}

		return Optional.empty();
	}
}
