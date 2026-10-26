package com.sandymandy.strut.physics.fluid;

import com.google.gson.JsonObject;

/**
 * What one kind of thick liquid is made of: a cloud of soft particles that pull on each other and drag on what they touch, drawn as one smooth blobby
 * surface (metaballs). Every length is in blocks, every time in seconds
 *
 * @param radius How far a particle reaches: it pulls on and is pushed by neighbours inside this, and its blob of surface reaches about half of it
 * @param spacing How close two particles may come before they push apart (a fraction of {@code radius} is typical: about 0.45)
 * @param cohesion 0 to 1: how strongly particles between {@code spacing} and {@code radius} pull together, as the fraction of the gap closed each step. Thick
 *                 liquid clings to itself (0.02 to 0.06); water would be 0
 * @param viscosity 0 to 1: how much of the difference between two neighbours' velocities is removed each step. Honey is high (0.3), water is 0
 * @param friction 0 to 1: how much sliding speed a particle loses each step it touches a surface. High and it clings and creeps; 0 and it slides
 * @param gravity Blocks per second squared, downward
 * @param drag How quickly the air slows a particle, per second
 * @param lifetime Seconds a particle lives
 * @param fade Seconds at the end of its life over which a particle shrinks away
 * @param color ARGB of the liquid. Alpha is how see-through it is
 * @param threshold The metaball iso level (0.2 to 0.6): lower makes bigger, blobbier drops that join from further apart
 * @param cell The size of the grid the surface is cut on, in blocks. Larger is cheaper and more faceted: this is the low-poly look
 */
public record FluidParams(float radius, float spacing, float cohesion, float viscosity, float friction, float gravity, float drag, float lifetime, float fade,
						  int color, float threshold, float cell) {
	/** Thick, creamy and slightly glossy white: the default */
	public static final FluidParams THICK = new FluidParams(0.2f, 0.09f, 0.04f, 0.2f, 0.25f, 9.8f, 0.4f, 12f, 2f, 0xF2F4EFE8, 0.35f, 0.09f);

	public FluidParams {
		radius = Math.max(0.02f, radius);
		spacing = Math.max(0.005f, Math.min(spacing, radius));
		cohesion = clamp01(cohesion);
		viscosity = clamp01(viscosity);
		friction = clamp01(friction);
		lifetime = Math.max(0.1f, lifetime);
		fade = Math.max(0f, Math.min(fade, lifetime));
		threshold = Math.max(0.05f, Math.min(0.9f, threshold));
		cell = Math.max(radius / 6f, cell);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/** Reads a preset from JSON: any field left out keeps {@link #THICK}'s value. {@code color} is a number or a hex string ({@code "F2F4EFE8"}, ARGB) */
	public static FluidParams parse(JsonObject json) {
		FluidParams d = THICK;
		int color = d.color;

		if (json.has("color")) {
			var c = json.get("color");

			color = c.getAsJsonPrimitive().isNumber() ? c.getAsInt() : (int)Long.parseLong(c.getAsString().replace("#", "").replace("0x", ""), 16);
		}

		return new FluidParams(number(json, "radius", d.radius), number(json, "spacing", d.spacing), number(json, "cohesion", d.cohesion),
				number(json, "viscosity", d.viscosity), number(json, "friction", d.friction), number(json, "gravity", d.gravity), number(json, "drag", d.drag),
				number(json, "lifetime", d.lifetime), number(json, "fade", d.fade), color, number(json, "threshold", d.threshold), number(json, "cell", d.cell));
	}

	private static float number(JsonObject json, String key, float fallback) {
		return json.has(key) ? json.get(key).getAsFloat() : fallback;
	}
}
