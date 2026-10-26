package com.sandymandy.strut.player;

import org.jetbrains.annotations.ApiStatus;

/**
 * Marks the stretches of game code that must see the player's eye where the game puts it, not at the eye bone of an immersive body. The wall check is the one:
 * it measures from the eye, and an eye bone leaning forward as the head turns would be inside the wall the player stands against, which hurts them
 */
@ApiStatus.Internal
public final class EyeGuard {
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private EyeGuard() {}

	public static void enter() {
		DEPTH.get()[0]++;
	}

	public static void exit() {
		int[] depth = DEPTH.get();

		if (depth[0] > 0)
			depth[0]--;
	}

	/** Whether the eye is to be the game's own right now */
	public static boolean vanillaEye() {
		return DEPTH.get()[0] > 0;
	}
}
