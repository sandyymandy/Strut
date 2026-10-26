package com.sandymandy.strut.animation.authority;

/**
 * Why the server sent a full animation snapshot - what lets a client tell "you have just started
 * tracking this" from "here is the state again, in case you drifted"
 * <p>
 * Nothing about the playback state itself differs between them, so this changes no animation. It
 * matters only to events: a snapshot that <i>introduces</i> a clip to a client which has just started
 * tracking it must not replay that clip's history, while one that fills in a clip whose delta was lost
 * should still deliver the events it would have.
 */
public enum SnapshotReason {
	/** The client began tracking the entity: it has nothing, and is owed no history */
	START_TRACKING,
	/** The client asked for the full state ({@code AnimationResyncRequestPacket}) */
	RESYNC,
	/** The periodic safety-net resend to everyone tracking the entity */
	PERIODIC;

	public static SnapshotReason fromWire(int wire) {
		SnapshotReason[] values = values();

		if (wire < 0 || wire >= values.length)
			throw new IllegalArgumentException("no snapshot reason has wire code " + wire);

		return values[wire];
	}
}
