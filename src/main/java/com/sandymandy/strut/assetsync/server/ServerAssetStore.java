package com.sandymandy.strut.assetsync.server;

import java.io.IOException;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;

/**
 * What a server offers, frozen: the manifest and the bytes behind it
 * <p>
 * Held in memory, built once per data reload, so serving a chunk never touches the disk or a data pack on the server
 * thread. Files with the same content share one copy. A transfer keeps the store it started with, so a {@code /reload}
 * in the middle of someone's download never changes what they are being sent
 * <p>
 * The memory it holds is bounded by {@link Builder}'s budget: a server that offers more than it can hold skips the
 * excess with a warning rather than running out of memory
 */
public final class ServerAssetStore {
	public static final ServerAssetStore EMPTY = new ServerAssetStore(AssetManifest.empty(), Map.of(), List.of());

	private final AssetManifest manifest;
	private final Map<String, byte[]> byHash;
	private final List<String> rejected;

	private ServerAssetStore(AssetManifest manifest, Map<String, byte[]> byHash, List<String> rejected) {
		this.manifest = manifest;
		this.byHash = byHash;
		this.rejected = List.copyOf(rejected);
	}

	public AssetManifest manifest() {
		return this.manifest;
	}

	public boolean isEmpty() {
		return this.manifest.isEmpty();
	}

	/** One line per file that was left out, and why - for the server log */
	public List<String> rejected() {
		return this.rejected;
	}

	/** Bytes actually held in memory (distinct content, counted once) */
	public long heldBytes() {
		long total = 0;

		for (byte[] data : this.byHash.values())
			total += data.length;

		return total;
	}

	/**
	 * Serves slices of the offered files to a {@link ServerAssetSession}
	 */
	public ServerAssetSession.AssetReader reader() {
		return (entry, offset, length) -> {
			byte[] data = this.byHash.get(entry.sha256());

			if (data == null || offset < 0 || length < 0 || offset + length > data.length)
				throw new IOException("No such slice of " + AssetPath.describe(entry.path()));

			return Arrays.copyOfRange(data, (int)offset, (int)offset + length);
		};
	}

	/**
	 * @param maxFileBytes Largest single file
	 * @param maxTotalBytes The most distinct content held in memory
	 */
	public static Builder builder(long maxFileBytes, long maxTotalBytes) {
		return new Builder(maxFileBytes, maxTotalBytes);
	}

	/**
	 * Collects files, then freezes them. Files are considered in path order, so which ones a budget cuts off is the
	 * same on every run
	 */
	public static final class Builder {
		private final long maxFileBytes;
		private final long maxTotalBytes;
		private final Map<String, byte[]> pending = new TreeMap<>();
		private final List<String> rejected = new ArrayList<>();

		private Builder(long maxFileBytes, long maxTotalBytes) {
			this.maxFileBytes = maxFileBytes;
			this.maxTotalBytes = maxTotalBytes;
		}

		/**
		 * Adds a file. A later add for the same path replaces the earlier one (highest data pack, then code sources)
		 *
		 * @param path {@code <namespace>/<path>}, see {@link AssetPath}
		 * @return Why it was refused, or null
		 */
		@Nullable
		public String add(String path, byte[] bytes) {
			String problem = AssetPath.rejection(path);

			if (problem == null && bytes.length > this.maxFileBytes)
				problem = "file larger than " + this.maxFileBytes + " bytes";

			if (problem != null) {
				this.rejected.add(AssetPath.describe(path) + ": " + problem);

				return problem;
			}

			this.pending.put(path, bytes);

			return null;
		}

		public ServerAssetStore build() {
			AssetManifest.Builder manifest = new AssetManifest.Builder(new SyncLimits(this.maxFileBytes));
			Map<String, byte[]> byHash = new HashMap<>();
			long held = 0;

			for (Map.Entry<String, byte[]> entry : this.pending.entrySet()) {
				byte[] data = entry.getValue();
				String hash = AssetManifest.sha256Hex(data);
				boolean isNew = !byHash.containsKey(hash);

				if (isNew && held + data.length > this.maxTotalBytes) {
					this.rejected.add(AssetPath.describe(entry.getKey()) + ": over the " + this.maxTotalBytes + "-byte memory budget");

					continue;
				}

				String problem = manifest.add(entry.getKey(), hash, data.length);

				if (problem != null) {
					this.rejected.add(AssetPath.describe(entry.getKey()) + ": " + problem);

					continue;
				}

				if (isNew) {
					byHash.put(hash, data);
					held += data.length;
				}
			}

			return new ServerAssetStore(manifest.build(), byHash, this.rejected);
		}
	}
}
