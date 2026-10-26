package com.sandymandy.strut.assetsync.server;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Ack;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Done;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.WantPage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncResult;

/**
 * The server's half of one client's sync: offers the manifest, sends what the client asks for, waits for its verdict
 * <p>
 * A plain state machine with no networking and no clock of its own: the wiring feeds it messages ({@link #receive}) and
 * the server tick ({@link #tick}), and hands it somewhere to send ({@code out}). That is what makes it testable end to
 * end against {@link ClientAssetSession} without a server
 * <p>
 * It trusts nothing the client says: a hash it never offered, an ack for bytes it never sent, or a message in the wrong
 * state ends the session as {@link SyncResult#PROTOCOL_ERROR}
 */
public final class ServerAssetSession {
	/**
	 * @param chunkBytes Largest chunk. Must stay well under the 1 MiB custom payload limit
	 * @param windowBytes Most chunk data allowed unacknowledged at once - flow control
	 * @param maxChunksPerTick Most chunks sent in one tick, so a big transfer does not stall the server thread
	 * @param pageBytes Largest estimated manifest page
	 * @param idleTimeoutTicks How long the client may go silent while the server waits on it
	 * @param loadTimeoutTicks How long the client may take to build its pack and reload after the last byte
	 */
	public record Config(int chunkBytes, long windowBytes, int maxChunksPerTick, int pageBytes, long idleTimeoutTicks,
						 long loadTimeoutTicks) {
		public static final Config DEFAULT = new Config(256 * 1024, 4L * 1024 * 1024, 8, 900 * 1024, 20 * 60, 20 * 60 * 5);
	}

	/**
	 * Reads a slice of a file's bytes
	 */
	@FunctionalInterface
	public interface AssetReader {
		byte[] read(ManifestEntry entry, long offset, int length) throws IOException;
	}

	private enum State { AWAIT_WANT, SENDING, AWAIT_DONE, FINISHED }

	private final AssetManifest manifest;
	private final AssetReader reader;
	private final Config config;
	private final Consumer<AssetSyncMessage> out;

	private State state = State.AWAIT_WANT;
	private SyncResult result;
	private long lastInbound;
	private int wantReceived;

	private final Set<String> wanted = new LinkedHashSet<>();
	private List<ManifestEntry> queue = List.of();
	private int queueIndex;
	private long offset;
	private long sentBytes;
	private long ackedBytes;

	public ServerAssetSession(AssetManifest manifest, AssetReader reader, Config config, Consumer<AssetSyncMessage> out) {
		this.manifest = manifest;
		this.reader = reader;
		this.config = config;
		this.out = out;
	}

	/**
	 * Sends the manifest. Call once, when the configuration task starts
	 */
	public void start(long nowTick) {
		this.lastInbound = nowTick;

		List<List<ManifestEntry>> pages = this.manifest.pages(this.config.pageBytes());

		for (int i = 0; i < pages.size(); i++)
			this.out.accept(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, i == pages.size() - 1, pages.get(i)));
	}

	/**
	 * A message from the client
	 */
	public void receive(AssetSyncMessage message, long nowTick) {
		if (this.state == State.FINISHED)
			return;

		this.lastInbound = nowTick;

		if (message instanceof Done done) {
			// A client may end the sync at any point: declining, over its cap, or a failure of its own
			finish(done.result());

			return;
		}

		switch (this.state) {
			case AWAIT_WANT -> {
				if (message instanceof WantPage page)
					onWant(page);
				else
					finish(SyncResult.PROTOCOL_ERROR);
			}
			case SENDING, AWAIT_DONE -> {
				if (message instanceof Ack ack)
					onAck(ack);
				else
					finish(SyncResult.PROTOCOL_ERROR);
			}
			default -> finish(SyncResult.PROTOCOL_ERROR);
		}
	}

	private void onWant(WantPage page) {
		// A client cannot legitimately want more hashes than the manifest has files, so more is abuse, not a big want
		this.wantReceived += page.hashes().size();

		if (this.wantReceived > this.manifest.entries().size()) {
			finish(SyncResult.PROTOCOL_ERROR);

			return;
		}

		for (String hash : page.hashes()) {
			if (this.manifest.firstWithHash(hash) == null) {
				finish(SyncResult.PROTOCOL_ERROR);

				return;
			}

			this.wanted.add(hash);
		}

		if (!page.last())
			return;

		List<ManifestEntry> files = new ArrayList<>();

		for (String hash : this.wanted)
			files.add(this.manifest.firstWithHash(hash));

		this.queue = files;
		this.state = files.isEmpty() ? State.AWAIT_DONE : State.SENDING;
	}

	private void onAck(Ack ack) {
		if (ack.bytesReceived() > this.sentBytes || ack.bytesReceived() < this.ackedBytes)
			finish(SyncResult.PROTOCOL_ERROR);
		else
			this.ackedBytes = ack.bytesReceived();
	}

	/**
	 * Advances the transfer and the timeouts. Call once per server tick
	 */
	public void tick(long nowTick) {
		if (this.state == State.FINISHED)
			return;

		if (this.state == State.SENDING)
			pump();

		long silent = nowTick - this.lastInbound;

		switch (this.state) {
			case AWAIT_WANT -> {
				if (silent > this.config.idleTimeoutTicks())
					finish(SyncResult.TIMED_OUT);
			}
			case SENDING -> {
				if (this.sentBytes > this.ackedBytes && silent > this.config.idleTimeoutTicks())
					finish(SyncResult.TIMED_OUT);
			}
			case AWAIT_DONE -> {
				if (silent > this.config.loadTimeoutTicks())
					finish(SyncResult.TIMED_OUT);
			}
			default -> {}
		}
	}

	private void pump() {
		for (int sent = 0; sent < this.config.maxChunksPerTick() && this.state == State.SENDING; sent++) {
			long inFlight = this.sentBytes - this.ackedBytes;
			ManifestEntry entry = this.queue.get(this.queueIndex);
			long remaining = entry.size() - this.offset;
			int length = (int)Math.min(this.config.chunkBytes(), remaining);

			// Always allow one chunk when nothing is in flight, so a window smaller than a chunk still progresses
			if (inFlight > 0 && inFlight + length > this.config.windowBytes())
				return;

			byte[] data;

			try {
				data = this.reader.read(entry, this.offset, length);
			}
			catch (IOException | RuntimeException e) {
				finish(SyncResult.FAILED);

				return;
			}

			if (data.length != length) {
				finish(SyncResult.FAILED);

				return;
			}

			this.out.accept(new Chunk(entry.sha256(), this.offset, data));
			this.offset += length;
			this.sentBytes += length;

			if (this.offset >= entry.size()) {
				this.offset = 0;
				this.queueIndex++;

				if (this.queueIndex >= this.queue.size())
					this.state = State.AWAIT_DONE;
			}
		}
	}

	private void finish(SyncResult result) {
		this.state = State.FINISHED;
		this.result = result;
	}

	/**
	 * How the sync ended, or empty while it is still going
	 */
	public Optional<SyncResult> result() {
		return Optional.ofNullable(this.result);
	}

	public boolean isFinished() {
		return this.state == State.FINISHED;
	}

	/** Where the sync is, for diagnostics: {@code waiting}, {@code sending}, {@code loading} (the client is building its pack) or {@code finished} */
	public String stateName() {
		return switch (this.state) {
			case AWAIT_WANT -> "waiting";
			case SENDING -> "sending";
			case AWAIT_DONE -> "loading";
			case FINISHED -> "finished";
		};
	}

	/** Total chunk bytes this client asked for, once its want list is complete (0 before) - the denominator of {@link #bytesSent()} */
	public long wantedBytes() {
		long total = 0;

		for (ManifestEntry entry : this.queue)
			total += entry.size();

		return total;
	}

	/** Chunk bytes sent so far, for diagnostics */
	public long bytesSent() {
		return this.sentBytes;
	}

}
