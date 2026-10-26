package com.sandymandy.strut.event.type;

/**
 * What an event does when playback crosses it while running <b>backwards</b> (a negative rate)
 * <p>
 * {@link #SKIP} is the default for everything: a gameplay event that fires on the way back out of an
 * animation is almost never what an author meant. An event opts in with {@link #FIRE}. The engine's
 * own {@code strut:loop} and {@code strut:end} never fire in reverse.
 */
public enum ReversePolicy {
	SKIP,
	FIRE;

	public boolean firesInReverse() {
		return this == FIRE;
	}
}
