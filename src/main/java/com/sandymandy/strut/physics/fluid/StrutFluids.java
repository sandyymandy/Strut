package com.sandymandy.strut.physics.fluid;

import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Random;
import com.sandymandy.strut.physics.StrutPhysics;

/**
 * The thick liquid: a few hundred soft particles that cling to each other and to what they touch, drawn as one smooth blobby surface (metaballs). Client only,
 * visual only, like the rest of the physics module
 * <pre>{@code
 * // a burst from a bone of a model, in the bone's own directions: a mod's event binding can do this on a marker (see "strut_physics:fluid")
 * StrutFluids.emit(entity, "tip", StrutFluids.Emission.burst(StrutFluids.THICK, 14).speed(2.2f).spread(0.25f));
 *
 * // a stream of 40 particles over one second from a point in the world
 * StrutFluids.emitAt(StrutFluids.THICK, x, y, z, 0, 1, 0, StrutFluids.Emission.stream(StrutFluids.THICK, 40, 1.0f).speed(1.5f));
 * }</pre>
 * A preset says what the liquid is ({@link FluidParams}): the built-in {@link #THICK}, one registered with {@link #register}, or one a resource pack ships at
 * {@code assets/<namespace>/strut/fluids/<name>.json} (id {@code <namespace>:<name>})
 */
public final class StrutFluids {
	/** The default creamy white liquid */
	public static final Identifier THICK = Identifier.of(StrutPhysics.MOD_ID, "thick");

	private StrutFluids() {}

	/**
	 * What to emit and how
	 *
	 * @param preset Which liquid
	 * @param count How many particles in all
	 * @param duration Seconds to spread them over: 0 is all at once, a burst; more is a stream
	 * @param speed How fast they leave, blocks per second
	 * @param spread How wide the cone they leave in is, 0 (a line) to 1 (a hemisphere)
	 * @param jitter How much each particle's speed varies, as a fraction (0.2 is plus or minus a fifth)
	 * @param offset Where they start, in the bone's own space from its pivot, in blocks (null for the pivot)
	 * @param direction Which way they leave, in the bone's own space (null for {@code (0, 0, -1)}, Blockbench's front). Ignored by {@link #emitAt}
	 * @param inherit How much of the emitting entity's own velocity the particles take, 0 to 1
	 */
	public record Emission(Identifier preset, int count, float duration, float speed, float spread, float jitter, @Nullable float[] offset, @Nullable float[] direction,
						   float inherit) {
		public static Emission burst(Identifier preset, int count) {
			return new Emission(preset, count, 0f, 1.5f, 0.25f, 0.2f, null, null, 0.5f);
		}

		public static Emission stream(Identifier preset, int count, float seconds) {
			return new Emission(preset, count, seconds, 1.5f, 0.25f, 0.2f, null, null, 0.5f);
		}

		public Emission speed(float speed) {
			return new Emission(this.preset, this.count, this.duration, speed, this.spread, this.jitter, this.offset, this.direction, this.inherit);
		}

		public Emission spread(float spread) {
			return new Emission(this.preset, this.count, this.duration, this.speed, spread, this.jitter, this.offset, this.direction, this.inherit);
		}

		public Emission jitter(float jitter) {
			return new Emission(this.preset, this.count, this.duration, this.speed, this.spread, jitter, this.offset, this.direction, this.inherit);
		}

		public Emission offset(float x, float y, float z) {
			return new Emission(this.preset, this.count, this.duration, this.speed, this.spread, this.jitter, new float[] {x, y, z}, this.direction, this.inherit);
		}

		public Emission direction(float x, float y, float z) {
			return new Emission(this.preset, this.count, this.duration, this.speed, this.spread, this.jitter, this.offset, new float[] {x, y, z}, this.inherit);
		}

		public Emission inherit(float inherit) {
			return new Emission(this.preset, this.count, this.duration, this.speed, this.spread, this.jitter, this.offset, this.direction, inherit);
		}
	}

	/** Registers a liquid under {@code id}; a resource pack's file of the same id replaces it */
	public static void register(Identifier id, FluidParams params) {
		FluidPresets.register(id, params);
	}

	/** The liquid called {@code id}: {@link FluidParams#THICK} (with a warning, once) if there is none */
	public static FluidParams preset(Identifier id) {
		return FluidPresets.get(id);
	}

	/**
	 * Starts an emission from a bone of {@code entity}'s model, following the bone while it lasts. Client side only (a server call does nothing). The particles
	 * start the next time the entity is drawn
	 */
	public static void emit(Entity entity, String bone, Emission emission) {
		if (entity.getWorld().isClient())
			FluidSystem.emitFromBone(entity.getId(), bone, emission);
	}

	/**
	 * Starts an emission at a point in the world, leaving along {@code (dx, dy, dz)} (any length but zero)
	 */
	public static void emitAt(Identifier preset, double x, double y, double z, double dx, double dy, double dz, Emission emission) {
		FluidSystem.emitAt(x, y, z, dx, dy, dz, new Emission(preset, emission.count(), emission.duration(), emission.speed(), emission.spread(), emission.jitter(),
				emission.offset(), emission.direction(), emission.inherit()));
	}

	/** Removes every particle */
	public static void clear() {
		FluidSystem.clear();
	}

	/** How many particles are alive, of every liquid */
	public static int particles() {
		return FluidSystem.particleCount();
	}

	/**
	 * A velocity of {@code speed} blocks per second in a cone around the unit direction {@code (dx, dy, dz)}: {@code spread} 0 is the line itself and 1 a
	 * hemisphere, the angle uniform over the cone's area; the speed varies by {@code jitter}. Pure and public for tests and for custom emitters
	 *
	 * @param out Receives x, y, z
	 */
	public static void coneVelocity(Random random, double dx, double dy, double dz, float speed, float spread, float jitter, float[] out) {
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (len < 1e-9) {
			dx = 0;
			dy = 1;
			dz = 0;
			len = 1;
		}

		dx /= len;
		dy /= len;
		dz /= len;

		// two directions across the cone's axis
		double ux;
		double uy;
		double uz;

		if (Math.abs(dy) < 0.9) {
			ux = dz;
			uy = 0;
			uz = -dx;
		}
		else {
			ux = 0;
			uy = -dz;
			uz = dy;
		}

		double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);

		ux /= ul;
		uy /= ul;
		uz /= ul;

		double vx = dy * uz - dz * uy;
		double vy = dz * ux - dx * uz;
		double vz = dx * uy - dy * ux;

		// uniform over the cap: cos(angle) from 1 down to 1 - spread
		double cos = 1.0 - random.nextDouble() * Math.max(0f, Math.min(1f, spread));
		double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
		double around = random.nextDouble() * Math.PI * 2;
		double cx = Math.cos(around) * sin;
		double cy = Math.sin(around) * sin;
		double s = speed * (1.0 + (random.nextDouble() * 2 - 1) * jitter);

		out[0] = (float)((dx * cos + ux * cx + vx * cy) * s);
		out[1] = (float)((dy * cos + uy * cx + vy * cy) * s);
		out[2] = (float)((dz * cos + uz * cx + vz * cy) * s);
	}
}
