package com.sandymandy.strut.event.type;

/**
 * What an event type is <i>for</i>
 * <p>
 * Purely descriptive - it routes nothing and changes no delivery rule. It exists so tooling (the
 * plugin's type picker, the {@code /strut events} log, validation messages) can group and explain
 * types, and so a consumer reading a clip can tell a damage window from a whoosh at a glance
 */
public enum EventCategory {
	/** Damage windows, item changes, state phases, interaction locks */
	GAMEPLAY,
	/** Sounds and voice cues */
	AUDIO,
	/** Particles, flashes, camera impulses, overlays */
	VISUAL,
	/** Attached props and effects */
	ATTACHMENT,
	/** Typed extension hooks registered by a mod */
	SCRIPT,
	/** Synchronisation points with no direct side effect */
	MARKER
}
