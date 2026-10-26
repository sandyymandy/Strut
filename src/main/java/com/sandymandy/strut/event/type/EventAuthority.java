package com.sandymandy.strut.event.type;

import org.jetbrains.annotations.Nullable;

/**
 * Where an event's handlers run
 * <p>
 * {@link #BOTH} is "mirrored dispatch": the server side runs exactly once <i>and</i>
 * every tracking client runs its own twin. That is exactly-once <b>per machine</b>, not atomic
 * across machines - a client can see its twin up to one latency before or after the server applies
 * the gameplay half
 */
public enum EventAuthority {
	/** The server tick only - gameplay. Never runs on a client */
	SERVER,
	/** Each tracking client, from its mirrored timeline - audio, visuals, HUD */
	CLIENT,
	/** The server handler once, and the client twin on every tracking client */
	BOTH;

	public boolean runsOnServer() {
		return this != CLIENT;
	}

	public boolean runsOnClient() {
		return this != SERVER;
	}

	/**
	 * Decodes the {@code .strutanim} event record's authority byte
	 * <p>
	 * <b>0 means "the event type's own default", not "server"</b> - every shipped file has 0 in this
	 * byte, and reading it as a real value would silently turn every existing sound into a server
	 * event. Hence {@code null} for 0: the caller falls back to
	 * {@link StrutEventType#defaultAuthority()}
	 *
	 * @throws IllegalArgumentException for a code no version of the format defines
	 */
	@Nullable
	public static EventAuthority fromWire(int wire) {
		return switch (wire) {
			case 0 -> null;
			case 1 -> SERVER;
			case 2 -> CLIENT;
			case 3 -> BOTH;
			default -> throw new IllegalArgumentException("no event authority has wire code " + wire);
		};
	}

	/**
	 * The byte a file stores for this authority, or 0 for {@code null} (use the type's default)
	 */
	public static int toWire(@Nullable EventAuthority authority) {
		return authority == null ? 0 : authority.ordinal() + 1;
	}
}
