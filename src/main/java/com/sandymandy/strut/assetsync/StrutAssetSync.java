package com.sandymandy.strut.assetsync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetStore;

/**
 * The public API of asset sync: server-owned assets delivered to every client that joins
 * <p>
 * Most servers need no code at all: put files in a data pack under {@code data/<namespace>/asset_sync/} and they reach
 * clients as {@code assets/<namespace>/...}. This class is for a mod that wants to <b>require</b> the assets, or generate
 * some in code.
 * <pre>{@code
 * StrutAssetSync.setRequired(true);                                  // players who cannot take the assets cannot join
 * StrutAssetSync.addSource(sink -> sink.add("mymod", "sounds.json", bytes));
 * }</pre>
 * Everything here is server-side and thread-safe. A server with nothing to sync sends nothing and costs nothing
 */
public final class StrutAssetSync {
	private static final Logger LOGGER = LogManager.getLogger("Strut");

	/** Largest single synced file by default */
	public static final long DEFAULT_MAX_FILE_BYTES = 64L * 1024 * 1024;

	/** Most distinct content the server holds in memory by default */
	public static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;

	/**
	 * Where a mod's own assets are handed over
	 */
	public interface Sink {
		/**
		 * Offers one file: {@code assets/<namespace>/<path>} on the client
		 *
		 * @return Why the file was refused (bad path, forbidden type, reserved namespace, too large), or null
		 */
		String add(String namespace, String path, byte[] bytes);
	}

	/**
	 * Supplies assets from code. Called on every data reload, after the data packs, so what it adds overrides them
	 */
	@FunctionalInterface
	public interface ServerAssetSource {
		void contribute(Sink sink);
	}

	private static final List<ServerAssetSource> SOURCES = new CopyOnWriteArrayList<>();

	private static volatile ServerAssetStore store = ServerAssetStore.EMPTY;
	private static volatile boolean required;
	private static volatile long maxFileBytes = DEFAULT_MAX_FILE_BYTES;
	private static volatile long maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES;

	private static final AtomicLong SYNCS_STARTED = new AtomicLong();
	private static final AtomicLong SYNCS_LOADED = new AtomicLong();
	private static final AtomicLong SYNCS_REJECTED = new AtomicLong();
	private static final AtomicLong BYTES_SENT = new AtomicLong();

	private StrutAssetSync() {}

	/**
	 * Whether a player who cannot take the assets (declines, is over their size cap, or has an incompatible client) is
	 * refused entry. Off by default: Strut does not decide that for every mod
	 */
	public static void setRequired(boolean required) {
		StrutAssetSync.required = required;
	}

	public static boolean isRequired() {
		return required;
	}

	public static void addSource(ServerAssetSource source) {
		SOURCES.add(source);
	}

	/**
	 * Stops calling a source added with {@link #addSource}. Takes effect at the next data reload
	 */
	public static void removeSource(ServerAssetSource source) {
		SOURCES.remove(source);
	}

	/**
	 * Sets the caps on what is offered. Takes effect at the next data reload
	 *
	 * @param maxFileBytes Largest single file
	 * @param maxTotalBytes The most distinct content held in memory
	 */
	public static void setLimits(long maxFileBytes, long maxTotalBytes) {
		StrutAssetSync.maxFileBytes = maxFileBytes;
		StrutAssetSync.maxTotalBytes = maxTotalBytes;
	}

	public static long maxFileBytes() {
		return maxFileBytes;
	}

	/**
	 * What is being offered right now
	 */
	public static ServerAssetStore currentStore() {
		return store;
	}

	/**
	 * Rebuilds the store from data-pack files (handed over by {@code scan}) and then every registered source. Called on
	 * each data reload; logs a summary and one warning per skipped file
	 *
	 * @param scan Adds every data-pack file under {@code asset_sync/}
	 * @return The new store
	 */
	public static ServerAssetStore rebuild(Consumer<Sink> scan) {
		ServerAssetStore.Builder builder = ServerAssetStore.builder(maxFileBytes, maxTotalBytes);
		// An invalid path is still handed to the builder, which refuses it and records why (sanitised) for the log
		Sink sink = (namespace, path, bytes) -> builder.add(namespace + "/" + path, bytes);

		scan.accept(sink);

		for (ServerAssetSource source : SOURCES) {
			try {
				source.contribute(sink);
			}
			catch (RuntimeException e) {
				LOGGER.warn("[Strut asset sync] an asset source failed: {}", e.toString());
			}
		}

		ServerAssetStore built = builder.build();

		for (String line : built.rejected())
			LOGGER.warn("[Strut asset sync] skipped {}", line);

		store = built;

		if (!built.isEmpty())
			LOGGER.info("[Strut asset sync] offering {} file(s), {} bytes ({} held in memory)", built.manifest().entries().size(), built.manifest().totalBytes(), built.heldBytes());

		return built;
	}

	// ---- diagnostics, read by /strut assets ----

	public static void recordStarted() {
		SYNCS_STARTED.incrementAndGet();
	}

	public static void recordFinished(SyncResult result, long bytesSent) {
		BYTES_SENT.addAndGet(bytesSent);

		if (result.isSuccess())
			SYNCS_LOADED.incrementAndGet();
		else
			SYNCS_REJECTED.incrementAndGet();
	}

	public static long syncsStarted() {
		return SYNCS_STARTED.get();
	}

	public static long syncsLoaded() {
		return SYNCS_LOADED.get();
	}

	public static long syncsNotLoaded() {
		return SYNCS_REJECTED.get();
	}

	public static long bytesSent() {
		return BYTES_SENT.get();
	}
}
