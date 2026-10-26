package com.sandymandy.strut.assetsync.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.AssetSyncException;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;

/**
 * Where each synced file lives on the client: {@code <namespace>/<path>} to a file in the cache
 * <p>
 * The virtual resource pack is a thin view over this, so everything about <i>which</i> file answers a request is decided
 * (and tested) here without a game. Built from a verified manifest and the cache; a file that is not in the cache
 * means the sync is not really finished, so building fails instead of leaving a hole
 */
public final class SyncedAssetIndex {
	private final String manifestHash;
	private final Map<String, Path> files;
	private final Set<String> namespaces;

	private SyncedAssetIndex(String manifestHash, Map<String, Path> files) {
		this.manifestHash = manifestHash;
		this.files = files;

		Set<String> found = new TreeSet<>();

		for (String path : files.keySet())
			found.add(path.substring(0, path.indexOf('/')));

		this.namespaces = Set.copyOf(found);
	}

	/**
	 * @throws AssetSyncException if a file the manifest lists is not in the cache
	 */
	public static SyncedAssetIndex build(AssetManifest manifest, AssetCache cache) {
		Map<String, Path> files = new TreeMap<>();

		for (ManifestEntry entry : manifest.entries()) {
			if (!cache.hasIntact(entry.sha256(), entry.size()))
				throw new AssetSyncException("Synced file missing from the cache: " + AssetPath.describe(entry.path()));

			files.put(entry.path(), cache.pathOf(entry.sha256()));
		}

		return new SyncedAssetIndex(manifest.hash(), files);
	}

	/** Equal exactly when two indexes serve identical content, so an unchanged rejoin needs no reload */
	public String manifestHash() {
		return this.manifestHash;
	}

	public int fileCount() {
		return this.files.size();
	}

	public Set<String> namespaces() {
		return this.namespaces;
	}

	/**
	 * The cache file that answers {@code assets/<namespace>/<path>}, or null
	 */
	public Path find(String namespace, String path) {
		return this.files.get(namespace + "/" + path);
	}

	/**
	 * Every path in {@code namespace} at or under the directory {@code prefix} (empty means all), as the game asks when it
	 * lists resources
	 */
	public List<String> list(String namespace, String prefix) {
		String start = namespace + "/" + (prefix.isEmpty() ? "" : prefix + "/");
		List<String> found = new ArrayList<>();

		for (String full : this.files.keySet()) {
			if (full.startsWith(start))
				found.add(full.substring(namespace.length() + 1));
		}

		return found;
	}
}
