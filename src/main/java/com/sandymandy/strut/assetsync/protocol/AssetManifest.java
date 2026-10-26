package com.sandymandy.strut.assetsync.protocol;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Every file a server offers a client: path, hash, size - sorted, unique by path, and validated
 * <p>
 * The server builds one from its data packs with a {@link Builder}, which <i>skips and reports</i> bad files. A client
 * builds one from what the server sent with {@link #of}, which <i>refuses the whole manifest</i> at the first bad
 * entry: a server that lists a hostile path is not to be half-trusted
 */
public final class AssetManifest {
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

	private final List<ManifestEntry> entries;
	private final String hash;
	private final long totalBytes;

	private AssetManifest(List<ManifestEntry> sorted) {
		this.entries = List.copyOf(sorted);
		this.hash = hashOf(this.entries);
		this.totalBytes = this.entries.stream().mapToLong(ManifestEntry::size).sum();
	}

	public static AssetManifest empty() {
		return new AssetManifest(List.of());
	}

	/**
	 * A manifest from entries received over the wire
	 *
	 * @throws AssetSyncException at the first entry that breaks a rule, or a duplicate path
	 */
	public static AssetManifest of(Collection<ManifestEntry> received, SyncLimits limits) {
		Map<String, ManifestEntry> byPath = new TreeMap<>();

		for (ManifestEntry entry : received) {
			String problem = problem(entry, limits);

			if (problem != null)
				throw new AssetSyncException("Bad asset '" + AssetPath.describe(entry.path()) + "': " + problem);

			if (byPath.put(entry.path(), entry) != null)
				throw new AssetSyncException("Asset listed twice: " + AssetPath.describe(entry.path()));
		}

		return new AssetManifest(new ArrayList<>(byPath.values()));
	}

	/**
	 * @return Why {@code entry} is not acceptable, or null
	 */
	@Nullable
	public static String problem(ManifestEntry entry, SyncLimits limits) {
		String rejection = AssetPath.rejection(entry.path());

		if (rejection != null)
			return rejection;

		if (!HASH.matcher(entry.sha256()).matches())
			return "malformed hash";

		if (entry.size() < 0)
			return "negative size";

		if (entry.size() > limits.maxFileBytes())
			return "file larger than " + limits.maxFileBytes() + " bytes";

		return null;
	}

	public List<ManifestEntry> entries() {
		return this.entries;
	}

	/** A hash over every entry, equal exactly when two manifests offer identical content */
	public String hash() {
		return this.hash;
	}

	public long totalBytes() {
		return this.totalBytes;
	}

	public boolean isEmpty() {
		return this.entries.isEmpty();
	}

	/**
	 * The distinct hashes of these entries, in first-appearance order. Two paths with the same content are one file
	 */
	public Set<String> distinctHashes() {
		Set<String> hashes = new LinkedHashSet<>();

		for (ManifestEntry entry : this.entries)
			hashes.add(entry.sha256());

		return hashes;
	}

	/**
	 * The first entry with this hash - what a transfer of that hash is measured against
	 */
	@Nullable
	public ManifestEntry firstWithHash(String sha256) {
		for (ManifestEntry entry : this.entries) {
			if (entry.sha256().equals(sha256))
				return entry;
		}

		return null;
	}

	/**
	 * This manifest cut into pages whose estimated size stays under {@code maxPageBytes}. Always at least one page
	 */
	public List<List<ManifestEntry>> pages(int maxPageBytes) {
		List<List<ManifestEntry>> pages = new ArrayList<>();
		List<ManifestEntry> page = new ArrayList<>();
		int bytes = 0;

		for (ManifestEntry entry : this.entries) {
			int cost = estimatedBytes(entry);

			if (!page.isEmpty() && bytes + cost > maxPageBytes) {
				pages.add(page);
				page = new ArrayList<>();
				bytes = 0;
			}

			page.add(entry);
			bytes += cost;
		}

		pages.add(page);

		return pages;
	}

	private static int estimatedBytes(ManifestEntry entry) {
		// The path (up to three bytes a character), a length prefix, the hash as text and the size as a long
		return entry.path().length() * 3 + 3 + 64 + 3 + 8;
	}

	private static String hashOf(List<ManifestEntry> entries) {
		MessageDigest digest = digest();

		for (ManifestEntry entry : entries)
			digest.update((entry.path() + "\0" + entry.sha256() + "\0" + entry.size() + "\n").getBytes(StandardCharsets.UTF_8));

		return HexFormat.of().formatHex(digest.digest());
	}

	public static String sha256Hex(byte[] bytes) {
		return HexFormat.of().formatHex(digest().digest(bytes));
	}

	static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every JVM has SHA-256", e);
		}
	}

	/**
	 * Collects files for a server's manifest, skipping the ones that break a rule
	 */
	public static final class Builder {
		private final SyncLimits limits;
		private final Map<String, ManifestEntry> byPath = new TreeMap<>();
		private final List<String> rejected = new ArrayList<>();

		public Builder(SyncLimits limits) {
			this.limits = limits;
		}

		/**
		 * Adds a file. A later add for the same path replaces the earlier one, which is how data-pack overrides
		 * (highest pack last) resolve
		 *
		 * @return Why the file was skipped, or null if it was added
		 */
		@Nullable
		public String add(String path, String sha256, long size) {
			ManifestEntry entry = new ManifestEntry(path, sha256, size);
			String problem = problem(entry, this.limits);

			if (problem != null) {
				this.rejected.add(path + ": " + problem);

				return problem;
			}

			this.byPath.put(path, entry);

			return null;
		}

		/** One line per skipped file, for the server log */
		public List<String> rejected() {
			return List.copyOf(this.rejected);
		}

		public AssetManifest build() {
			List<ManifestEntry> sorted = new ArrayList<>(this.byPath.values());

			sorted.sort(Comparator.comparing(ManifestEntry::path));

			return new AssetManifest(sorted);
		}
	}
}
