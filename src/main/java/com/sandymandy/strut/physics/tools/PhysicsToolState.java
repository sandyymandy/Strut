package com.sandymandy.strut.physics.tools;

import java.util.List;

/**
 * What the cursor tool tells the world pass to draw: a small cross on every part that can be picked up
 */
public final class PhysicsToolState {
	/** Whether the tool screen is open */
	static volatile boolean open;

	/** A cross at a world position: half its size in blocks and its colour */
	public record Cross(double x, double y, double z, float size, int rgb) {}

	static volatile List<Cross> crosses = List.of();

	private PhysicsToolState() {}
}
