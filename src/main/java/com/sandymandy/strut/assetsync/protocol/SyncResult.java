package com.sandymandy.strut.assetsync.protocol;

/**
 * How one asset sync ended
 * <p>
 * The first four are what a client reports in its final message; the last two are only ever decided by
 * the server about a client that misbehaved or went quiet. The order of the first four is protocol
 */
public enum SyncResult {
	/** The assets are live on the client: the pack was built and resources reloaded */
	LOADED,
	/** The player has asset sync turned off */
	DECLINED,
	/** What the server wants to send is over the player's size cap */
	TOO_LARGE,
	/** A hash mismatch, disk error, bad path or anything else the client could not accept */
	FAILED,
	/** The client stopped answering (server side only) */
	TIMED_OUT,
	/** The client sent something the protocol does not allow (server side only) */
	PROTOCOL_ERROR;

	public boolean isSuccess() {
		return this == LOADED;
	}

	/**
	 * @throws IllegalArgumentException for a code no client may send
	 */
	public static SyncResult fromWire(int code) {
		if (code < 0 || code > FAILED.ordinal())
			throw new IllegalArgumentException("Unknown asset sync result " + code);

		return values()[code];
	}
}
