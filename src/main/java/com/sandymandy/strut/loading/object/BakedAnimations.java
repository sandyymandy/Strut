package com.sandymandy.strut.loading.object;

import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animation.Animation;

import java.util.Map;

/**
 * Container object that holds a deserialized map of {@link Animation Animations}
 * <p>
 * Kept as a unique object so that it can be registered as a {@link com.google.gson.JsonDeserializer deserializer} for {@link com.google.gson.Gson Gson}
 */
public record BakedAnimations(Map<String, Animation> animations) {
	/**
	 * Gets an {@link Animation} by its name, if present
	 */
	@Nullable
	public Animation getAnimation(String name) {
		return animations.get(name);
	}

	/**
	 * The name of the animation at {@code index}, in the map's own iteration order, or null if
	 * there is no animation at that index
	 * <p>
	 * Meaningful only when {@link #animations} was built from something that preserves the order
	 * the clips were authored in - {@link com.sandymandy.strut.loading.binary.BinaryAnimationBaker}
	 * uses a linked map for exactly this reason. An index picked this way from a plain
	 * {@code HashMap}-backed instance (the JSON path) is still safe to call, just not guaranteed to
	 * mean the same clip from one reload to the next
	 *
	 * @param index A zero-based index, as an end user might type into a command
	 */
	@Nullable
	public String nameAt(int index) {
		if (index < 0)
			return null;

		int i = 0;

		for (String name : this.animations.keySet()) {
			if (i++ == index)
				return name;
		}

		return null;
	}
}
