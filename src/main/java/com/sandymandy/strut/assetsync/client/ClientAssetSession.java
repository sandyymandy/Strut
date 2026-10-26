package com.sandymandy.strut.assetsync.client;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Ack;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Done;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.WantPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.AssetSyncException;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.ChunkAssembler;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;

/**
 * The client's half of one sync: checks what the server offers, asks only for what its cache lacks, verifies every
 * byte it receives, and reports the verdict
 * <p>
 * Like {@link ServerAssetSession} it is a plain state machine: messages in, messages out, no networking. Everything the
 * server says is checked here, because a server can put files on a player's disk: hostile paths, oversize files, wrong
 * hashes and out-of-order chunks all end the sync as {@link SyncResult#FAILED} with nothing written under a hash's name
 * <p>
 * When every needed file is in the cache the session is <i>ready</i>: the wiring then builds the pack from
 * {@link #manifest()}, reloads resources, and calls {@link #complete}
 */
public final class ClientAssetSession {
	/**
	 * @param accept Whether the player allows server assets at all
	 * @param maxDownloadBytes The most this session may download (what the cache lacks, not the manifest's total)
	 * @param limits Per-file caps
	 * @param wantPageHashes Hashes per want page. 32 bytes each, so this keeps a page under the 32 KiB client limit
	 */
	public record Config(boolean accept, long maxDownloadBytes, SyncLimits limits, int wantPageHashes) {
		public static final Config DEFAULT = new Config(true, 512L * 1024 * 1024, SyncLimits.DEFAULT, 800);
	}

	/** Largest chunk accepted, whatever the server claims to be sending. Bounds memory held per message */
	public static final int MAX_CHUNK_BYTES = 512 * 1024;

	/** Most files a manifest may list; beyond it the server is treated as hostile */
	public static final int MAX_ENTRIES = 100_000;

	public enum State { AWAIT_MANIFEST, RECEIVING, READY, FINISHED }

	private final AssetCache cache;
	private final Config config;
	private final Consumer<AssetSyncMessage> out;

	private State state = State.AWAIT_MANIFEST;
	private SyncResult result;
	private final List<ManifestEntry> pageEntries = new ArrayList<>();
	private AssetManifest manifest;

	private List<String> expected = List.of();
	private int expectedIndex;
	private ChunkAssembler current;
	private long bytesReceived;
	private int filesDownloaded;

	public ClientAssetSession(AssetCache cache, Config config, Consumer<AssetSyncMessage> out) {
		this.cache = cache;
		this.config = config;
		this.out = out;
	}

	/**
	 * A message from the server
	 */
	public void receive(AssetSyncMessage message) {
		if (this.state == State.FINISHED)
			return;

		try {
			switch (message) {
				case ManifestPage page -> onManifestPage(page);
				case Chunk chunk -> onChunk(chunk);
				default -> throw new AssetSyncException("Unexpected message from the server: " + message.getClass().getSimpleName());
			}
		}
		catch (AssetSyncException e) {
			abort(SyncResult.FAILED);
		}
	}

	private void onManifestPage(ManifestPage page) {
		if (this.state != State.AWAIT_MANIFEST && this.state != State.RECEIVING)
			throw new AssetSyncException("Manifest page in the wrong state");

		if (page.version() != AssetSyncMessage.PROTOCOL_VERSION)
			throw new AssetSyncException("Server speaks asset sync protocol " + page.version());

		if (!this.config.accept()) {
			abort(SyncResult.DECLINED);

			return;
		}

		this.state = State.RECEIVING;
		this.pageEntries.addAll(page.entries());

		if (this.pageEntries.size() > MAX_ENTRIES)
			throw new AssetSyncException("Manifest lists more than " + MAX_ENTRIES + " files");

		if (page.last())
			onManifestComplete();
	}

	private void onManifestComplete() {
		// Refuses the whole manifest at the first bad entry
		this.manifest = AssetManifest.of(this.pageEntries, this.config.limits());

		List<String> missing = new ArrayList<>();
		long missingBytes = 0;

		for (String hash : this.manifest.distinctHashes()) {
			long size = this.manifest.firstWithHash(hash).size();

			if (this.cache.hasIntact(hash, size))
				continue;

			missing.add(hash);
			missingBytes += size;
		}

		if (missingBytes > this.config.maxDownloadBytes()) {
			abort(SyncResult.TOO_LARGE);

			return;
		}

		this.expected = missing;

		int pageSize = Math.max(1, Math.min(this.config.wantPageHashes(), AssetSyncMessage.MAX_WANT_HASHES));

		for (int from = 0; from == 0 || from < missing.size(); from += pageSize) {
			int to = Math.min(missing.size(), from + pageSize);

			this.out.accept(new WantPage(to >= missing.size(), missing.subList(from, to)));
		}

		if (missing.isEmpty())
			becomeReady();
	}

	private void onChunk(Chunk chunk) {
		if (this.state != State.RECEIVING || this.manifest == null || this.expectedIndex >= this.expected.size())
			throw new AssetSyncException("Chunk nobody asked for");

		String hash = this.expected.get(this.expectedIndex);

		if (!hash.equals(chunk.hash()))
			throw new AssetSyncException("Chunk for " + AssetPath.describe(chunk.hash()) + " but " + hash + " was next");

		if (chunk.data().length > MAX_CHUNK_BYTES)
			throw new AssetSyncException("Chunk of " + chunk.data().length + " bytes");

		if (this.current == null)
			this.current = new ChunkAssembler(this.cache, this.manifest.firstWithHash(hash));

		boolean complete = this.current.accept(chunk.offset(), chunk.data());

		this.bytesReceived += chunk.data().length;
		this.out.accept(new Ack(this.bytesReceived));

		if (complete) {
			this.current = null;
			this.expectedIndex++;
			this.filesDownloaded++;

			if (this.expectedIndex >= this.expected.size())
				becomeReady();
		}
	}

	private void becomeReady() {
		// Everything needed is cached. Mark it all used now so a prune never takes it while it is in service
		for (String hash : this.manifest.distinctHashes())
			this.cache.find(hash);

		this.state = State.READY;
	}

	/**
	 * Reports the outcome once the wiring has built the pack and reloaded (or failed to)
	 *
	 * @throws IllegalStateException if the session is not ready
	 */
	public void complete(SyncResult outcome) {
		if (this.state != State.READY)
			throw new IllegalStateException("Asset sync is not ready to complete: " + this.state);

		this.out.accept(new Done(outcome));
		this.state = State.FINISHED;
		this.result = outcome;
	}

	/**
	 * Drops the session without telling anyone: the connection is gone. Discards any half-downloaded file
	 */
	public void discard() {
		if (this.current != null) {
			this.current.close();
			this.current = null;
		}

		if (this.state != State.FINISHED) {
			this.state = State.FINISHED;
			this.result = SyncResult.FAILED;
		}
	}

	/**
	 * Ends the sync now, telling the server why. For a disconnect handled by the wiring, or a failure of its own
	 */
	public void abort(SyncResult reason) {
		if (this.state == State.FINISHED)
			return;

		if (this.current != null) {
			this.current.close();
			this.current = null;
		}

		this.out.accept(new Done(reason));
		this.state = State.FINISHED;
		this.result = reason;
	}

	public State state() {
		return this.state;
	}

	public boolean isReady() {
		return this.state == State.READY;
	}

	/**
	 * The accepted manifest, once the session is ready (or finished having received one)
	 */
	public Optional<AssetManifest> manifest() {
		return Optional.ofNullable(this.manifest);
	}

	public Optional<SyncResult> result() {
		return Optional.ofNullable(this.result);
	}

	/** Files fetched this session (not cache hits), for diagnostics */
	public int filesDownloaded() {
		return this.filesDownloaded;
	}

	public long bytesReceived() {
		return this.bytesReceived;
	}
}
