package com.sandymandy.strut.assetsync.protocol;

import java.util.List;

/**
 * Everything the two sides say to each other during a sync. Plain data: the wire codecs wrap these
 * <p>
 * Server to client: {@link ManifestPage}, {@link Chunk}. Client to server: {@link WantPage}, {@link Ack}, {@link Done}
 */
public sealed interface AssetSyncMessage {
	/** Changes whenever these messages or their meaning change; a mismatch ends the sync cleanly */
	int PROTOCOL_VERSION = 1;

	/** Most hashes one want page may carry: 1,000 x 32 bytes stays under the 32,767-byte client-to-server payload limit */
	int MAX_WANT_HASHES = 1000;

	/**
	 * One page of the manifest. Pages arrive in order; the last one says so
	 */
	record ManifestPage(int version, boolean last, List<ManifestEntry> entries) implements AssetSyncMessage {
		public ManifestPage {
			entries = List.copyOf(entries);
		}
	}

	/**
	 * One page of the hashes the client lacks, in the order it wants them. The last page says so; it may be empty
	 */
	record WantPage(boolean last, List<String> hashes) implements AssetSyncMessage {
		public WantPage {
			hashes = List.copyOf(hashes);
		}
	}

	/**
	 * A slice of one wanted file
	 */
	record Chunk(String hash, long offset, byte[] data) implements AssetSyncMessage {}

	/**
	 * How many bytes of chunk data the client has received in total; lets the server send more
	 */
	record Ack(long bytesReceived) implements AssetSyncMessage {}

	/**
	 * The client's final word: the assets are live, or why they are not
	 */
	record Done(SyncResult result) implements AssetSyncMessage {}
}
