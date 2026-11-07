package com.sandymandy.strut.physics.tools;

import java.util.ArrayList;
import java.util.List;
import com.sandymandy.strut.physics.config.PhysicsOptions;
import com.sandymandy.strut.physics.fluid.FluidSystem;
import java.util.Locale;

/**
 * The F9 overlay's physics line: how much physics ran in the last completed frame. Render thread only, like the rest of the overlay
 */
public final class PhysicsStats {
	private static int instances;
	private static int bones;
	private static int steps;
	private static long nanos;
	private static int lastInstances;
	private static int lastBones;
	private static int lastSteps;
	private static long lastNanos;
	private static long frame = 0;
	private static long seen = 0;

	private PhysicsStats() {}

	/** Called once at the start of every rendered frame */
	public static void nextFrame() {
		frame++;
	}

	/** Called for every simulated instance; rolls the totals over when a new frame starts */
	public static void record(int simulatedBones, int stepCount, long elapsedNanos) {
		if (seen != frame) {
			lastInstances = instances;
			lastBones = bones;
			lastSteps = steps;
			lastNanos = nanos;
			instances = 0;
			bones = 0;
			steps = 0;
			nanos = 0;
			seen = frame;
		}

		instances++;
		bones += simulatedBones;
		steps += stepCount;
		nanos += elapsedNanos;
	}

	private static int heldBack;
	private static int lastHeldBack;
	private static int candidates;
	private static int lastCandidates;
	private static int admitted;
	private static int lastAdmitted;
	private static long heldFrame = -1;
	/** What one instance's turn cost, as last measured, nanoseconds; 0 before any */
	private static double each;
	/** Every how many frames each instance past the near ones takes its turn, worked out from the last frame: 1 while everyone fits in the budget */
	private static int cadence = 1;
	/** How near the camera an instance must be to have its turn every frame, blocks, worked out from the last frame */
	private static double nearDistance = Double.MAX_VALUE;
	private static int near;
	/** The distances of the instances that asked this frame, and the last frame's */
	private static double[] distances = new double[64];
	private static double[] lastDistances = new double[64];
	private static int ranked;
	private static int lastRanked;
	/** Whether everyone had their turn last frame within the budget */
	private static boolean allFit = true;

	/** What an instance may do this frame, see {@link #admit} */
	public enum Turn {
		/** All the steps the clock calls for */
		FULL,
		/** A few steps, then the clock is let go of (it was held before) */
		ONE,
		/** Nothing: its last result is drawn again */
		HOLD
	}

	private static void rollTurns() {
		if (heldFrame != frame) {
			lastHeldBack = heldBack;
			lastCandidates = candidates;
			lastAdmitted = admitted;

			double[] swap = lastDistances;

			lastDistances = distances;
			distances = swap;
			lastRanked = ranked;
			ranked = 0;

			// how many instance turns the budget pays for, from what the turns cost last frame
			double budget = PhysicsOptions.quality().budgetMillis * 1_000_000.0;
			// (called by the frame's first turn, before anything of the frame is recorded: the totals still are the frame before's)
			// (a frame in which nobody was due says nothing about the cost: the last known one is kept)
			if (lastAdmitted > 0)
				each = Math.max(1.0, nanos / (double)lastAdmitted);

			double fit = each > 0.0 ? budget / each : Double.MAX_VALUE;

			allFit = lastCandidates <= 0 || fit >= lastCandidates;

			if (allFit) {
				cadence = 1;
				near = lastCandidates;
				nearDistance = Double.MAX_VALUE;
			}
			else {
				// the nearest have their turn every frame, as many as most of the budget pays for (and always a few); the rest share what is left
				// (only those whose distance is known can be nearest)
				near = Math.min(lastRanked, Math.max(MIN_NEAR, (int)(fit * NEAR_SHARE)));

				double[] sorted = java.util.Arrays.copyOf(lastDistances, lastRanked);

				java.util.Arrays.sort(sorted);
				nearDistance = near > 0 ? sorted[near - 1] : -1;

				int far = lastCandidates - near;
				double left = Math.max(1.0, fit - near);

				cadence = far <= left ? 1 : Math.min(MAX_CADENCE, (int)Math.ceil(far / left));
			}

			heldBack = 0;
			candidates = 0;
			admitted = 0;
			heldFrame = frame;
		}
	}

	/** The most frames an instance waits for its turn: past this the crowd is left over budget rather than frozen */
	public static final int MAX_CADENCE = 12;
	/** The fewest instances that have their turn every frame, whatever the crowd, and the share of the budget the nearest are given */
	public static final int MIN_NEAR = 4;
	/** Farther than this from the camera, blocks, an instance may wait up to {@link #MAX_FAR_CADENCE} frames */
	public static final double FAR = 16;
	public static final int MAX_FAR_CADENCE = 30;
	private static final double NEAR_SHARE = 0.6;

	/** {@link #admit(int, double, boolean)} for an instance whose distance is not known: it waits with the far ones */
	public static Turn admit(int starved) {
		return admit(starved, Double.MAX_VALUE, false);
	}

	/**
	 * What an instance may do this frame, so a crowd of flesh, cloth or ragdolls cannot take the frame whole, and the ones nearest the camera stay smooth. While
	 * everyone fits in the quality level's budget each instance has its full turn every frame. When the crowd is bigger, the camera's own model and the nearest
	 * (as many as most of the budget pays for, and never fewer than {@link #MIN_NEAR}) still have theirs every frame, and the others take theirs every few frames
	 * (the cadence, worked out from what a turn cost the frame before), drawing their last result again in between, which costs nothing. They start at different
	 * points of the cadence, so their turns are spread over the frames. And as a safety, once a frame has spent the whole budget the far ones after it hold
	 * (except one that has waited the most it may)
	 *
	 * @param starved How many frames in a row this instance has been held
	 * @param distance How far it is from the camera, blocks
	 * @param viewer Whether it is the camera's own model (the player seen from behind, or in a mirror): never held
	 */
	public static Turn admit(int starved, double distance, boolean viewer) {
		rollTurns();

		candidates++;

		// the camera's own model has its turn whatever the crowd, and is not one of the nearest the budget pays for
		if (!viewer && distance < Double.MAX_VALUE) {
			if (ranked >= distances.length)
				distances = java.util.Arrays.copyOf(distances, distances.length * 2);

			distances[ranked++] = distance;
		}

		if (viewer || allFit || (distance < Double.MAX_VALUE && distance <= nearDistance)) {
			admitted++;

			return starved > 0 ? Turn.ONE : Turn.FULL;
		}

		boolean over = seen == frame && nanos > PhysicsOptions.quality().budgetMillis * 1_000_000.0;
		// far off, where it is a few pixels, an instance may wait twice as long
		boolean far = distance > FAR && distance < Double.MAX_VALUE;
		int wait = far ? Math.min(MAX_FAR_CADENCE, cadence * 2) : cadence;
		boolean due = starved >= wait - 1;

		if (!due || (over && starved < (far ? MAX_FAR_CADENCE : MAX_CADENCE))) {
			heldBack++;

			return Turn.HOLD;
		}

		admitted++;

		return starved > 0 ? Turn.ONE : Turn.FULL;
	}

	/** Forgets what the frames before cost, so the next ones start from every instance having its turn (a test, or physics switched back on) */
	public static void reset() {
		cadence = 1;
		nearDistance = Double.MAX_VALUE;
		near = 0;
		each = 0;
		ranked = 0;
		lastRanked = 0;
		allFit = true;
		candidates = 0;
		lastCandidates = 0;
		admitted = 0;
		lastAdmitted = 0;
		heldBack = 0;
		lastHeldBack = 0;
		instances = 0;
		bones = 0;
		steps = 0;
		nanos = 0;
		heldFrame = -1;
		seen = -1;
	}

	/** Every how many frames each instance takes its turn at the moment (1 when everyone fits the budget); for the overlay */
	public static int cadence() {
		return cadence;
	}

	public static List<String> lines() {
		List<String> lines = new ArrayList<>(2);

		lines.add(String.format("physics: %s (%s), %d instances, %d spring bones, %d steps, %.3f ms%s", PhysicsOptions.enabled() ? "on" : "OFF",
				PhysicsOptions.quality().name().toLowerCase(Locale.ROOT), lastInstances, lastBones, lastSteps, lastNanos / 1_000_000.0,
				lastHeldBack > 0 ? ", the nearest " + near + " every frame, " + lastHeldBack + " others waiting their turn (every " + cadence + " frames)" : ""));

		String fluid = FluidSystem.line();

		if (fluid != null)
			lines.add(fluid);

		return lines;
	}
}
