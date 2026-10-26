package com.sandymandy.strut.physics.world;

/**
 * The liquid a model stands in, as the physics sees it: one column of water or lava at the entity's place, from the lowest block of it to its surface, and the
 * way it flows. Soft parts that are inside it float, are slowed and are carried by the current, the way the game's own entities are
 * <p>
 * It is read once a frame (not for every particle), so a part reaching sideways out of the column is judged by the column's surface: close enough for hair,
 * a tail and a skirt, which hang within a block of the entity
 */
public final class Liquid {
	/** How much of gravity water takes away from what is in it: above 1 a part rises a little, as hair does */
	public static final float WATER_BUOYANCY = 1.1f;
	/** The share of its speed that a part in water keeps each step (60 a second) */
	public static final float WATER_KEEP = 0.9f;
	public static final float LAVA_BUOYANCY = 1.4f;
	public static final float LAVA_KEEP = 0.8f;
	/** How hard a current pushes, in blocks per second squared, at full strength */
	private static final float FLOW_PUSH = 6f;
	/** How far under the surface, in blocks, a part has to be to count as fully in */
	private static final double EDGE = 0.2;

	private final double bottom;
	private final double surface;
	private final float flowX;
	private final float flowZ;
	private final float buoyancy;
	private final float keep;

	/**
	 * @param bottom the lowest y of the liquid
	 * @param surface the y of its top, the height of a partly full block included
	 * @param flowX the current's push along x, about -1 to 1
	 * @param flowZ the current's push along z
	 */
	public Liquid(double bottom, double surface, float flowX, float flowZ, boolean lava) {
		this.bottom = bottom;
		this.surface = surface;
		this.flowX = flowX;
		this.flowZ = flowZ;
		this.buoyancy = lava ? LAVA_BUOYANCY : WATER_BUOYANCY;
		this.keep = lava ? LAVA_KEEP : WATER_KEEP;
	}

	/** How far a particle at height {@code y} is in the liquid: 0 out of it, 1 fully in, eased over the first 0.2 blocks below the surface */
	public double submersion(double y) {
		if (y < this.bottom - 1.0)
			return 0;

		return Math.max(0, Math.min(1, (this.surface - y) / EDGE));
	}

	/** Gravity, in blocks per second squared, for a particle {@code submersion} in the liquid: lighter, and upward if the liquid lifts it */
	public double gravity(double gravity, double submersion) {
		return gravity * (1 - submersion * this.buoyancy);
	}

	/** The share of its speed that a particle {@code submersion} in the liquid keeps for a step */
	public double keep(double submersion) {
		return 1 - submersion * (1 - this.keep);
	}

	/** The push of the current on a particle {@code submersion} in the liquid along x, in blocks per second squared */
	public double pushX(double submersion) {
		return this.flowX * FLOW_PUSH * submersion;
	}

	/** As {@link #pushX} along z */
	public double pushZ(double submersion) {
		return this.flowZ * FLOW_PUSH * submersion;
	}
}
