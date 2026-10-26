package com.sandymandy.strut.physics.config;

import com.sandymandy.strut.physics.collision.ModelSdfs;
import com.sandymandy.strut.physics.fluid.StrutFluids;
import com.sandymandy.strut.physics.solver.SpringSolver;

/**
 * The player's physics options, read every frame. They are changed with {@code /strutphysics} or the Cloth Config screen, and the
 * config file persists them
 */
public final class PhysicsOptions {
	private static volatile boolean enabled = true;
	private static volatile double distanceLimit = 48.0;
	private static volatile boolean worldCollision = true;
	private static volatile float timeScale = 1f;
	private static volatile boolean otherCollision = true;
	private static volatile boolean playerCollision = true;
	private static volatile boolean springWorldCollision = true;
	private static volatile boolean cloth = true;
	private static volatile boolean fluid = true;
	private static volatile boolean water = true;
	private static volatile boolean meshColliders = true;
	private static volatile Quality quality = Quality.MEDIUM;

	/**
	 * How much work physics may do. {@code iterations} scales how many times each step the constraints are relaxed
	 * (fewer is looser and cheaper), {@code maxSteps} is the most steps one frame may catch up with, {@code boxes} the most world boxes kept near a part, and
	 * {@code range} scales the distance beyond which a model is drawn as animated. Medium is what the module did before quality levels existed
	 */
	public enum Quality {
		LOW(0.5, 3, 256, 0.6),
		MEDIUM(1.0, SpringSolver.MAX_STEPS, 768, 1.0),
		HIGH(1.5, SpringSolver.MAX_STEPS, 1024, 1.0);

		final double iterations;
		final int maxSteps;
		final int boxes;
		final double range;
		/** What all the physics of one frame may cost, in milliseconds, before the instances after it are held to one step */
		public final double budgetMillis;

		Quality(double iterations, int maxSteps, int boxes, double range) {
			this.iterations = iterations;
			this.maxSteps = maxSteps;
			this.boxes = boxes;
			this.range = range;
			this.budgetMillis = iterations < 1.0 ? 1.5 : iterations > 1.0 ? 6.0 : 3.0;
		}
	}

	public static Quality quality() {
		return quality;
	}

	public static void quality(Quality value) {
		quality = value == null ? Quality.MEDIUM : value;
	}

	/** {@code base} relaxation passes, scaled by the quality (never fewer than two) */
	public static int iterations(int base) {
		return Math.max(2, (int)Math.round(base * quality.iterations));
	}

	/** The most steps one frame may catch up with */
	public static int maxSteps() {
		return quality.maxSteps;
	}

	/** The most world boxes kept near a part */
	public static int maxWorldBoxes() {
		return quality.boxes;
	}

	/** The distance beyond which a model is drawn as animated, with the quality's scale applied */
	public static double effectiveRange() {
		return distanceLimit * quality.range;
	}

	private PhysicsOptions() {}

	/** Whether soft parts collide with other Strut models and with mobs */
	public static boolean otherCollision() {
		return otherCollision;
	}

	public static void otherCollision(boolean value) {
		otherCollision = value;
	}

	/** Whether spring bones that list {@code world} in {@code collidesWith} collide with blocks (chains follow {@link #worldCollision()} alone) */
	public static boolean springWorldCollision() {
		return springWorldCollision;
	}

	public static void springWorldCollision(boolean value) {
		springWorldCollision = value;
	}

	/** Whether cloth (a mesh's vertices simulated) runs: it is the costliest part of the module, so it has its own switch */
	public static boolean cloth() {
		return cloth;
	}

	public static void cloth(boolean value) {
		cloth = value;
	}

	/**
	 * Whether a model's collision shapes are made from its real geometry (a signed distance field per bone, see {@link ModelSdfs}) rather than boxes and
	 * capsules. It applies to the models loaded after it is changed (a resource reload, F3+T)
	 */
	public static boolean meshColliders() {
		return meshColliders;
	}

	public static void meshColliders(boolean value) {
		meshColliders = value;
	}

	/** Whether the thick liquid ({@link StrutFluids}) runs: it has a pool of particles and a mesh to build, so it has its own switch */
	public static boolean fluid() {
		return fluid;
	}

	public static void fluid(boolean value) {
		fluid = value;
	}

	/** Whether soft parts react to the game's liquids: they float, are slowed and are carried by the current while in water or lava */
	public static boolean water() {
		return water;
	}

	public static void water(boolean value) {
		water = value;
	}

	/** Whether soft parts collide with players' bodies */
	public static boolean playerCollision() {
		return playerCollision;
	}

	public static void playerCollision(boolean value) {
		playerCollision = value;
	}

	/** The master switch: off means every model is drawn exactly as animated */
	public static boolean enabled() {
		return enabled;
	}

	public static void enabled(boolean value) {
		enabled = value;
	}

	/** Whether chains collide with blocks */
	public static boolean worldCollision() {
		return worldCollision;
	}

	public static void worldCollision(boolean value) {
		worldCollision = value;
	}

	/** How fast physics time runs: 1 is real time, below 1 is slow motion for watching what it does. A debugging tool, not saved */
	public static float timeScale() {
		return timeScale;
	}

	public static void timeScale(float scale) {
		timeScale = Math.max(0.02f, Math.min(1f, scale));
	}

	/** Entities farther than this many blocks from the camera skip physics and are drawn as animated */
	public static double distanceLimit() {
		return distanceLimit;
	}

	public static void distanceLimit(double blocks) {
		distanceLimit = Math.max(4.0, blocks);
	}
}
