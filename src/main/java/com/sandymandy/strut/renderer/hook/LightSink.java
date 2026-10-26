package com.sandymandy.strut.renderer.hook;

/** Where a {@link LightProvider} puts the lights it adds to a model */
public interface LightSink {
	/**
	 * A light at a place in the world: a model is lit on the side that faces it, more the nearer it is. {@code radius} is how far (blocks) it reaches, {@code brightness}
	 * how strong it is at its own place (1 is a torch's), {@code rgb} its colour ({@code 0xRRGGBB}; a surface lit by red light and nothing else turns red)
	 */
	void point(double x, double y, double z, double radius, float brightness, int rgb);

	/** A light from a direction, like the sun: {@code (dx, dy, dz)} is where it is, seen from the model, and need not be normalised */
	void directional(float dx, float dy, float dz, float strength, int rgb);
}
