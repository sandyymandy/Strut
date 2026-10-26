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
	/** Every how many frames each instance takes its turn, worked out from the last frame: 1 while everyone fits in the budget */
	private static int cadence = 1;

	/** What an instance may do this frame, see {@link #admit} */
	public enum Turn {
		/** All the steps the clock calls for */
		FULL,
		/** One step, then the clock is let go of (it was held before) */
		ONE,
		/** Nothing: its last result is drawn again */
		HOLD
	}

	private static void rollTurns() {
		if (heldFrame != frame) {
			lastHeldBack = heldBack;
			lastCandidates = candidates;
			lastAdmitted = admitted;

			// how many instance turns the budget pays for, from what the turns cost last frame
			double budget = PhysicsOptions.quality().budgetMillis * 1_000_000.0;
			// (called by the frame's first turn, before anything of the frame is recorded: the totals still are the frame before's)
			double each = lastAdmitted > 0 ? Math.max(1.0, nanos / (double)lastAdmitted) : 0.0;
			double fit = each > 0.0 ? budget / each : Double.MAX_VALUE;

			cadence = lastCandidates <= 0 || fit >= lastCandidates ? 1 : Math.min(MAX_CADENCE, (int)Math.ceil(lastCandidates / Math.max(1.0, fit)));
			heldBack = 0;
			candidates = 0;
			admitted = 0;
			heldFrame = frame;
		}
	}

	/** The most frames an instance waits for its turn: past this the crowd is left over budget rather than frozen */
	public static final int MAX_CADENCE = 6;

	/**
	 * What an instance may do this frame, so a crowd of cloths or ragdolls cannot take the frame whole. While everyone fits in the quality level's budget each
	 * instance has its full turn every frame. When the crowd is bigger than the budget pays for, each takes its turn every few frames instead (the cadence,
	 * worked out from what a turn cost the frame before) and draws its last result again in between, which costs nothing. Instances start at different points
	 * of the cadence, so the turns are spread over the frames. And as a safety, once a frame has spent the whole budget the instances after it hold
	 * (except one that has waited the most it may)
	 *
	 * @param starved How many frames in a row this instance has been held
	 */
	public static Turn admit(int starved) {
		rollTurns();
		candidates++;

		boolean over = seen == frame && nanos > PhysicsOptions.quality().budgetMillis * 1_000_000.0;
		boolean due = starved >= cadence - 1;

		if (!due || (over && starved < MAX_CADENCE)) {
			heldBack++;

			return Turn.HOLD;
		}

		admitted++;

		return starved > 0 ? Turn.ONE : Turn.FULL;
	}

	/** Forgets what the frames before cost, so the next ones start from every instance having its turn (a test, or physics switched back on) */
	public static void reset() {
		cadence = 1;
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
				lastHeldBack > 0 ? ", " + lastHeldBack + " waiting their turn (every " + cadence + " frames)" : ""));

		String fluid = FluidSystem.line();

		if (fluid != null)
			lines.add(fluid);

		return lines;
	}
}
