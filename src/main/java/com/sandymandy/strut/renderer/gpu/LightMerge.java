package com.sandymandy.strut.renderer.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Brings many lights down to the few a model is drawn with: the strongest ones stay as they are and the rest are added together into the last, so two lights either
 * side of a model light both sides, and a hundred lamps in a town do not cost a hundred
 */
final class LightMerge {
	/** A light: where it is from the model (the vector's length is how strong it is) and its colour, each channel 0 to 1 */
	record Light(float x, float y, float z, float r, float g, float b) {
		static final Light NONE = new Light(0f, 0f, 0f, 1f, 1f, 1f);

		float strength() {
			return (float) Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
		}

		static Light of(float x, float y, float z, int rgb) {
			return new Light(x, y, z, (rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
		}
	}

	private LightMerge() {}

	/**
	 * {@code slots} lights from {@code candidates}: the {@code slots - 1} strongest, then the others as one (their directions added, their colours mixed by how strong each
	 * is). Slots with nothing for them are {@link Light#NONE}
	 */
	static Light[] merge(List<Light> candidates, int slots) {
		Light[] out = new Light[slots];

		Arrays.fill(out, Light.NONE);

		if (candidates.isEmpty())
			return out;

		List<Light> sorted = new ArrayList<>(candidates);

		sorted.sort(Comparator.comparingDouble(Light::strength).reversed());

		int single = sorted.size() <= slots ? sorted.size() : slots - 1;

		for (int i = 0; i < single; i++)
			out[i] = sorted.get(i);

		if (sorted.size() > slots) {
			float x = 0f;
			float y = 0f;
			float z = 0f;
			float r = 0f;
			float g = 0f;
			float b = 0f;
			float total = 0f;

			for (int i = single; i < sorted.size(); i++) {
				Light light = sorted.get(i);
				float strength = light.strength();

				x += light.x;
				y += light.y;
				z += light.z;
				r += light.r * strength;
				g += light.g * strength;
				b += light.b * strength;
				total += strength;
			}

			out[slots - 1] = total > 0f ? new Light(x, y, z, r / total, g / total, b / total) : Light.NONE;
		}

		return out;
	}
}
