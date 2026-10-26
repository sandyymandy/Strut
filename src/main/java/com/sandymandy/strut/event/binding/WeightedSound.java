package com.sandymandy.strut.event.binding;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of a {@code random_sounds} pool: a sound, or an explicit <b>silence</b>, with a weight
 * <p>
 * Silence is a first-class entry rather than a padding trick. A pool used to be padded with
 * {@code minecraft:empty} to make "nothing" likely; here it is {@code {"none": true, "weight": 2}}, which
 * says what it means and does not ask the sound engine to play an empty sound
 *
 * @param sound The sound to play, or null for silence
 * @param weight How likely, relative to the other entries: 1 or more
 */
public record WeightedSound(@Nullable Identifier sound, int weight) {
	public WeightedSound {
		if (weight < 1)
			throw new IllegalArgumentException("a weight must be at least 1, got " + weight);
	}

	public boolean isSilence() {
		return this.sound == null;
	}
}
