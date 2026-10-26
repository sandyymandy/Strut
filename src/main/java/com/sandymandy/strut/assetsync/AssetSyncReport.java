package com.sandymandy.strut.assetsync;

import java.util.ArrayList;
import java.util.List;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;
import com.sandymandy.strut.assetsync.server.ServerAssetStore;
import java.util.Locale;

/**
 * The text of {@code /strut assets}: what the server is offering, what has happened, and who is downloading right now
 * <p>
 * Pure, so the wording and the numbers are tested without a server. A working sync and one that silently sends nothing look
 * the same from outside, so this is how a live test <i>sees</i> that files were offered, chunks left the server, and
 * clients came out the other end loaded
 */
public final class AssetSyncReport {
	/**
	 * One player's sync in progress
	 *
	 * @param state See {@link ServerAssetSession#stateName()}
	 */
	public record ActiveSync(String player, String state, long bytesSent, long bytesWanted) {}

	private AssetSyncReport() {}

	/**
	 * The status report, one line per entry
	 */
	public static List<String> status(ServerAssetStore store, boolean required, List<ActiveSync> active) {
		List<String> lines = new ArrayList<>();
		AssetManifest manifest = store.manifest();

		if (manifest.isEmpty()) {
			lines.add("Asset sync: nothing to offer. Put files in data/<namespace>/asset_sync/ in a data pack, then /reload.");
		}
		else {
			lines.add("Asset sync: offering " + manifest.entries().size() + " file(s), " + formatBytes(manifest.totalBytes()) + " ("
					+ formatBytes(store.heldBytes()) + " held in memory), manifest " + manifest.hash().substring(0, 8)
					+ ", assets " + (required ? "REQUIRED" : "optional"));
		}

		lines.add("Syncs: started " + StrutAssetSync.syncsStarted() + ", loaded " + StrutAssetSync.syncsLoaded() + ", not loaded "
				+ StrutAssetSync.syncsNotLoaded() + ", " + formatBytes(StrutAssetSync.bytesSent()) + " sent (since launch)");

		if (active.isEmpty()) {
			lines.add("In progress: none");
		}
		else {
			lines.add("In progress: " + active.size());

			for (ActiveSync sync : active) {
				lines.add("  " + safeName(sync.player()) + ": " + sync.state()
						+ (sync.bytesWanted() > 0 ? " " + formatBytes(sync.bytesSent()) + " / " + formatBytes(sync.bytesWanted()) : ""));
			}
		}

		if (!store.rejected().isEmpty()) {
			lines.add("Skipped at the last reload: " + store.rejected().size());

			for (int i = 0; i < Math.min(5, store.rejected().size()); i++)
				lines.add("  " + store.rejected().get(i));

			if (store.rejected().size() > 5)
				lines.add("  ... and " + (store.rejected().size() - 5) + " more (see the log)");
		}

		return lines;
	}

	/**
	 * The offered files, at most {@code limit} of them
	 */
	public static List<String> files(ServerAssetStore store, int limit) {
		List<String> lines = new ArrayList<>();
		List<ManifestEntry> entries = store.manifest().entries();

		for (int i = 0; i < Math.min(limit, entries.size()); i++)
			lines.add(entries.get(i).path() + "  " + formatBytes(entries.get(i).size()) + "  " + entries.get(i).sha256().substring(0, 8));

		if (entries.size() > limit)
			lines.add("... and " + (entries.size() - limit) + " more");

		if (entries.isEmpty())
			lines.add("No files offered.");

		return lines;
	}

	/**
	 * A player name made safe to print: a Minecraft name is letters, digits and underscores, so anything else is replaced and
	 * the length limited. (Not {@link AssetPath#describe}, which allows only path characters and would mangle capitals)
	 */
	public static String safeName(String name) {
		StringBuilder safe = new StringBuilder();

		for (int i = 0; i < Math.min(name.length(), 32); i++) {
			char c = name.charAt(i);

			safe.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ? c : '?');
		}

		return safe.toString();
	}

	public static String formatBytes(long bytes) {
		if (bytes < 1024)
			return bytes + " B";

		if (bytes < 1024 * 1024)
			return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);

		if (bytes < 1024L * 1024 * 1024)
			return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));

		return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
	}
}
