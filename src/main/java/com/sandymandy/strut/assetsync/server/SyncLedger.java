package com.sandymandy.strut.assetsync.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each connected player was last offered, and whether they took it
 * <p>
 * Decides who gets a fresh sync when the server's assets change mid-session (a {@code /reload}): a player who
 * <b>accepted</b> the previous assets, and whose assets differ from what is on offer now, is sent the difference. A player who
 * <b>declined</b> (turned server assets off, over their size cap, a failed load) is left alone - asking again on every
 * reload would only nag someone who already said no. A player never asked (the server had nothing to offer when they
 * joined) is asked as soon as there is something
 * <p>
 * Pure and thread-safe, so the rules are tested without a server
 */
public final class SyncLedger {
	private record Entry(String manifestHash, boolean accepted) {}

	private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

	/**
	 * Notes the outcome of a sync
	 *
	 * @param manifestHash The hash of the manifest that was offered
	 * @param accepted Whether the player ended up with those assets loaded
	 */
	public void record(UUID player, String manifestHash, boolean accepted) {
		this.entries.put(player, new Entry(manifestHash, accepted));
	}

	public void forget(UUID player) {
		this.entries.remove(player);
	}

	/**
	 * Whether this player should be sent the current assets now
	 *
	 * @param currentHash The hash of what is on offer
	 * @param offeringNothing Whether the server has no assets at all right now
	 */
	public boolean needsPush(UUID player, String currentHash, boolean offeringNothing) {
		Entry entry = this.entries.get(player);

		if (entry == null)
			return !offeringNothing;

		// Their choice stands: no nagging after a decline. And nothing to send if they already have exactly this
		return entry.accepted() && !entry.manifestHash().equals(currentHash);
	}

	/**
	 * The hash this player was last given, or null
	 */
	public String lastHash(UUID player) {
		Entry entry = this.entries.get(player);

		return entry == null ? null : entry.manifestHash();
	}

	public int size() {
		return this.entries.size();
	}
}
