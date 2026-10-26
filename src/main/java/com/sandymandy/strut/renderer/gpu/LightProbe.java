package com.sandymandy.strut.renderer.gpu;

import com.sandymandy.strut.renderer.hook.LightProvider;
import com.sandymandy.strut.renderer.hook.LightSink;
import com.sandymandy.strut.renderer.hook.StrutLighting;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The directions the light on a model comes from, how much of it there is from each and what colour it is, worked out from the world where it stands: the sun by day and
 * the moon by night (through what is above it), the block lights (a torch, a lamp) round it one by one, and the lights mods add ({@link StrutLighting}). The shading modes
 * light a model by them, so the side of a character that faces a torch is the lit side, and in the dark there is no direction to speak of
 * <p>
 * The result is a {@link Rig} of {@link #LIGHTS} lights, each a vector whose direction is where the light is and whose length, up to 1, is how strong it is: the sky's (the
 * sun or the moon) and the three strongest of the others, the weaker ones added together into the last. A zero vector is no light; with none at all the shader uses its own
 * fixed lights. It is worked out at most once a tick for each entity
 */
public final class LightProbe {
	/** The lights a model is lit by: the sky, and the three strongest of the rest (the weaker are added into the last) */
	public static final int LIGHTS = 4;

	/** The lights of one model: for each, a direction scaled by its strength, and a colour (each channel 0 to 1) */
	public static final class Rig {
		private final Vector3f[] direction = new Vector3f[LIGHTS];
		private final Vector3f[] colour = new Vector3f[LIGHTS];

		Rig() {
			for (int i = 0; i < LIGHTS; i++) {
				this.direction[i] = new Vector3f();
				this.colour[i] = new Vector3f(1f, 1f, 1f);
			}
		}

		public Vector3f direction(int i) {
			return this.direction[i];
		}

		public Vector3f colour(int i) {
			return this.colour[i];
		}

		/** The colour of light {@code i} packed {@code 0xRRGGBB} */
		public int packedColour(int i) {
			Vector3f c = this.colour[i];

			return (Math.round(MathHelper.clamp(c.x, 0f, 1f) * 255f) << 16) | (Math.round(MathHelper.clamp(c.y, 0f, 1f) * 255f) << 8) | Math.round(MathHelper.clamp(c.z, 0f, 1f) * 255f);
		}

		/** This rig moved {@code amount} of the way to {@code target}, as a new one */
		Rig lerp(Rig target, float amount) {
			Rig out = new Rig();

			for (int i = 0; i < LIGHTS; i++) {
				out.direction[i].set(this.direction[i]).lerp(target.direction[i], amount);
				out.colour[i].set(this.colour[i]).lerp(target.colour[i], amount);
			}

			return out;
		}
	}

	private static final Int2ObjectOpenHashMap<Cached> CACHE = new Int2ObjectOpenHashMap<>();

	private record Cached(long tick, Rig rig, long emitterTick, List<LightMerge.Light> emitters) {}

	private record Eased(long nanos, float block, float sky) {}

	private static final Int2ObjectOpenHashMap<Eased> EASED = new Int2ObjectOpenHashMap<>();

	/** How far a light source is looked for, in blocks, and how often (in ticks) a model looks again */
	private static final int EMITTER_RADIUS = 8;
	private static final int EMITTER_PERIOD = 8;

	private static String strongestSource = "none";

	/** The block that is the strongest light round the last model looked at, for the F9 overlay */
	public static String strongestSource() {
		return strongestSource;
	}

	private LightProbe() {}

	/** The lights on {@code entity}, as described above; never mutated by the caller */
	public static Rig of(Entity entity) {
		World world = entity.getWorld();
		Cached cached = CACHE.get(entity.getId());

		if (cached != null && cached.tick == world.getTime())
			return cached.rig;

		Vector3d centre = new Vector3d(entity.getX(), entity.getY() + entity.getHeight() * 0.5, entity.getZ());
		BlockPos pos = BlockPos.ofFloored(centre.x, centre.y, centre.z);
		long emitterTick = cached == null ? Long.MIN_VALUE : cached.emitterTick;
		List<LightMerge.Light> emitters = cached == null ? List.of() : cached.emitters;

		// the lamps and torches round it are looked for every few ticks, not every one
		if (world.getTime() - emitterTick >= EMITTER_PERIOD || world.getTime() < emitterTick) {
			emitters = emitters(world, pos, centre);
			emitterTick = world.getTime();
		}

		Rig target = sample(world, pos, centre, emitters, entity);
		// each light moves toward what it should be, a step a tick, so walking up to a torch brings its light in smoothly instead of in jumps
		Rig rig = cached == null ? target : cached.rig.lerp(target, 0.25f);

		if (CACHE.size() > 4096)
			CACHE.clear();

		CACHE.put(entity.getId(), new Cached(world.getTime(), rig, emitterTick, emitters));

		return rig;
	}

	/**
	 * {@code packedLight} (the game's light on an entity, whole block and sky levels) eased toward over about a fifth of a second, so the light on a model rises and falls
	 * smoothly as it walks toward a torch or into shade instead of a level at a time. The result may hold a level between two whole ones (in sixteenths); the shader
	 * reads the lightmap at the levels round it
	 */
	public static int smooth(Entity entity, int packedLight) {
		long now = System.nanoTime();
		float block = (packedLight & 0xFFFF) / 16f;
		float sky = ((packedLight >> 16) & 0xFFFF) / 16f;
		Eased last = EASED.get(entity.getId());

		if (last != null && now - last.nanos < 1_000_000_000L) {
			float keep = (float) Math.exp(-(now - last.nanos) / 1.8e8);

			block = last.block * keep + block * (1f - keep);
			sky = last.sky * keep + sky * (1f - keep);
		}

		if (EASED.size() > 4096)
			EASED.clear();

		EASED.put(entity.getId(), new Eased(now, block, sky));

		return Math.round(Math.min(block, 15f) * 16f) | (Math.round(Math.min(sky, 15f) * 16f) << 16);
	}

	/** The lights at {@code pos} of {@code world}, for a model that is not an entity (a block entity, whose own object is {@code animatable}) */
	@Nullable
	public static Rig at(@Nullable World world, BlockPos pos, @Nullable Object animatable) {
		if (world == null)
			return null;

		Vector3d centre = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

		return sample(world, pos, centre, emitters(world, pos, centre), animatable);
	}

	/** Forgets what was worked out: the world changed */
	public static void clear() {
		CACHE.clear();
		EASED.clear();
	}

	/**
	 * The blocks that give off light round {@code centre}: for each in range, the way to it times how bright it is and how near, and its colour. Not through walls: a
	 * source is counted wherever it is
	 */
	private static List<LightMerge.Light> emitters(World world, BlockPos pos, Vector3d centre) {
		List<LightMerge.Light> found = new ArrayList<>();
		float strongest = 0f;
		String source = "none";
		BlockPos.Mutable at = new BlockPos.Mutable();

		for (int dx = -EMITTER_RADIUS; dx <= EMITTER_RADIUS; dx++) {
			for (int dy = -EMITTER_RADIUS; dy <= EMITTER_RADIUS; dy++) {
				for (int dz = -EMITTER_RADIUS; dz <= EMITTER_RADIUS; dz++) {
					at.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);

					BlockState state = world.getBlockState(at);
					int luminance = state.getLuminance();

					// the invisible light block is a technical one, put where a light is wanted (above a player, by a mod that lights what it carries): there is nothing to
					// see at it, so it is not where the light on a model comes from
					if (luminance <= 0 || state.isOf(Blocks.LIGHT))
						continue;

					double x = at.getX() + 0.5 - centre.x;
					double y = at.getY() + 0.5 - centre.y;
					double z = at.getZ() + 0.5 - centre.z;
					double distanceSquared = x * x + y * y + z * z;

					// light reaches as many blocks as it is bright
					if (distanceSquared < 0.04 || distanceSquared > (double) luminance * luminance)
						continue;

					double distance = Math.sqrt(distanceSquared);
					// fading out toward the edge of the search, so a source coming into range does not jump in
					double edge = Math.min(1.0, Math.max(0.0, (EMITTER_RADIUS + 0.5 - distance) / 4.0));
					double fade = edge * edge * (3.0 - 2.0 * edge);
					double weight = luminance / 15.0 / (1.0 + distanceSquared * 0.06) * fade * 1.6;

					if (weight <= 0.0)
						continue;

					found.add(LightMerge.Light.of((float) (x / distance * weight), (float) (y / distance * weight), (float) (z / distance * weight), StrutLighting.blockLightColour(state.getBlock())));

					if (weight > strongest) {
						strongest = (float) weight;
						source = state.getBlock().getName().getString() + " at " + dx + " " + dy + " " + dz + " luminance " + luminance;
					}
				}
			}
		}

		strongestSource = source;

		return found;
	}

	/** The lights the providers add for a model at {@code centre}, as candidates */
	private static void providedLights(World world, Vector3d centre, @Nullable Object animatable, List<LightMerge.Light> into) {
		List<LightProvider> providers = StrutLighting.providers();

		if (providers.isEmpty())
			return;

		LightSink sink = new LightSink() {
			@Override
			public void point(double x, double y, double z, double radius, float brightness, int rgb) {
				double dx = x - centre.x;
				double dy = y - centre.y;
				double dz = z - centre.z;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

				if (distance < 0.2 || radius <= 0.0 || distance >= radius)
					return;

				double fade = 1.0 - distance / radius;
				double weight = brightness * fade * fade;

				into.add(LightMerge.Light.of((float) (dx / distance * weight), (float) (dy / distance * weight), (float) (dz / distance * weight), rgb));
			}

			@Override
			public void directional(float dx, float dy, float dz, float strength, int rgb) {
				float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

				if (length > 1e-4f && strength > 0f)
					into.add(LightMerge.Light.of(dx / length * strength, dy / length * strength, dz / length * strength, rgb));
			}
		};

		for (LightProvider provider : providers) {
			try {
				provider.provide(animatable, world, centre.x, centre.y, centre.z, sink);
			}
			catch (RuntimeException ex) {
				// a mod's provider that fails must not take the frame down with it; it is skipped this time
			}
		}
	}

	private static Rig sample(World world, BlockPos pos, Vector3d centre, List<LightMerge.Light> emitters, @Nullable Object animatable) {
		float sky = world.getLightLevel(LightType.SKY, pos) / 15f;
		float block = world.getLightLevel(LightType.BLOCK, pos) / 15f;

		// the sun by day, the moon by night, from the time of day: noon straight up, sunset in the west, the moon the other way round the sky
		float angle = world.getSkyAngleRadians(1f);
		Vector3f sun = new Vector3f(-MathHelper.sin(angle), MathHelper.cos(angle), 0.25f).normalize();
		// how far the sun is up, as the game works it out for the sky's own brightness, less in rain
		float day = MathHelper.clamp(MathHelper.cos(angle) * 2f + 0.5f, 0f, 1f) * (1f - world.getRainGradient(1f) * 0.6f);
		Vector3f key = new Vector3f();

		if (day > 0.02f)
			key.add(new Vector3f(sun).mul(sky * day));

		if (day < 0.98f)
			key.add(new Vector3f(sun).negate().mul(sky * (1f - day) * 0.2f));

		Rig rig = new Rig();

		rig.direction[0].set(capped(key));

		List<LightMerge.Light> candidates = new ArrayList<>(emitters);

		providedLights(world, centre, animatable, candidates);

		if (!candidates.isEmpty()) {
			// a torch is a light of its own beside the sun, not a share of the sky's: by day, with the sun straight up, it still pulls the shading toward itself
			LightMerge.Light[] merged = LightMerge.merge(candidates, LIGHTS - 1);

			for (int i = 0; i < merged.length; i++) {
				LightMerge.Light light = merged[i];

				rig.direction[i + 1].set(capped(new Vector3f(light.x(), light.y(), light.z())));
				rig.colour[i + 1].set(light.r(), light.g(), light.b());
			}

			return rig;
		}

		// block light with no source near (light carried round a corner): the way the light level rises
		Vector3f gradient = new Vector3f(
				world.getLightLevel(LightType.BLOCK, pos.east()) - world.getLightLevel(LightType.BLOCK, pos.west()),
				world.getLightLevel(LightType.BLOCK, pos.up()) - world.getLightLevel(LightType.BLOCK, pos.down()),
				world.getLightLevel(LightType.BLOCK, pos.south()) - world.getLightLevel(LightType.BLOCK, pos.north()));
		float rise = gradient.length();

		if (rise > 0.01f)
			rig.direction[1].set(capped(gradient.div(rise).mul(block * Math.min(1f, rise / 3f))));

		return rig;
	}

	/** {@code v} no longer than one, the direction kept */
	private static Vector3f capped(Vector3f v) {
		float length = v.length();

		return length > 1f ? v.div(length) : v;
	}
}
