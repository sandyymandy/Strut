package com.sandymandy.strut.event.binding;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * One named table of bindings - typically one character's voice - loaded from a single JSON file
 * <p>
 * Keys are lower-case. Clips and tables were authored with inconsistent capitalisation
 * ({@code doggyfastMSG1} in the animation, {@code doggyFastMSG1} in the table) and only worked because
 * both sides were lower-cased at lookup; doing it once, here, keeps that true
 *
 * @param id The set's id, from its file path
 * @param bindings Key to binding, keys lower-case
 */
public record BindingSet(Identifier id, Map<String, Binding> bindings) {
	public BindingSet {
		bindings = Map.copyOf(bindings);
	}

	@Nullable
	public Binding get(String key) {
		return this.bindings.get(key.toLowerCase(Locale.ROOT));
	}
}
