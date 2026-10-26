package com.sandymandy.strut.renderer.hook;

/**
 * Eight {@code vec4}s every shader hook can read as {@code StrutParams[0..7]}, the same for every model drawn in a frame: a time, a colour, a strength, anything a hook
 * needs from the game. Set them from the client's own tick or render events; they are uploaded once a frame as they stand then
 */
public final class StrutShaderParams {
	/** How many {@code vec4}s there are */
	public static final int COUNT = 8;

	private static final float[] VALUES = new float[COUNT * 4];

	private StrutShaderParams() {}

	/** Sets slot {@code slot} (0 to 7) */
	public static synchronized void set(int slot, float x, float y, float z, float w) {
		if (slot < 0 || slot >= COUNT)
			throw new IllegalArgumentException("slot " + slot + " is not 0 to " + (COUNT - 1));

		VALUES[slot * 4] = x;
		VALUES[slot * 4 + 1] = y;
		VALUES[slot * 4 + 2] = z;
		VALUES[slot * 4 + 3] = w;
	}

	/** A copy of the values, slot after slot, for the upload */
	public static synchronized float[] snapshot() {
		return VALUES.clone();
	}
}
