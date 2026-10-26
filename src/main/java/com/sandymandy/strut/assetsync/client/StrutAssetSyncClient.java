package com.sandymandy.strut.assetsync.client;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.assetsync.AssetSyncDemo;
import com.sandymandy.strut.assetsync.AssetSyncReport;
import com.sandymandy.strut.assetsync.client.SyncedAssetIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The client-side API of asset sync
 * <pre>{@code
 * StrutAssetSyncClient.onAssetsReady(() -> ...);      // the server's assets were just loaded
 * boolean live = StrutAssetSyncClient.isServerAssetsActive();
 * }</pre>
 */
public final class StrutAssetSyncClient {
	private static final List<Runnable> READY = new CopyOnWriteArrayList<>();

	private static volatile int lastFilesDownloaded;
	private static volatile long lastBytesDownloaded;
	private static volatile long cacheBytes;

	private StrutAssetSyncClient() {}

	/**
	 * Calls {@code callback} on the client thread each time a server's assets finish loading, after the resource reload and
	 * before the player joins the world
	 */
	public static void onAssetsReady(Runnable callback) {
		READY.add(callback);
	}

	/**
	 * Whether a server's synced assets are loaded right now
	 */
	public static boolean isServerAssetsActive() {
		return SyncedAssetPackProvider.INSTANCE.active() != null;
	}

	/**
	 * How many files the active assets hold, or 0
	 */
	public static int activeFileCount() {
		SyncedAssetIndex active = SyncedAssetPackProvider.INSTANCE.active();

		return active == null ? 0 : active.fileCount();
	}

	static void recordSync(int filesDownloaded, long bytesDownloaded, long cacheSize) {
		lastFilesDownloaded = filesDownloaded;
		lastBytesDownloaded = bytesDownloaded;
		cacheBytes = cacheSize;
	}

	/**
	 * The F9 overlay's lines: whether server assets are active, what the last join fetched, and, when the built-in demo
	 * assets are on offer, whether the game's own loaders can see them
	 */
	public static List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		SyncedAssetIndex active = SyncedAssetPackProvider.INSTANCE.active();

		if (active == null) {
			lines.add("asset sync: no server assets");

			return lines;
		}

		lines.add("asset sync: " + active.fileCount() + " file(s) active in " + active.namespaces().size() + " namespace(s); last join fetched "
				+ lastFilesDownloaded + " file(s) / " + AssetSyncReport.formatBytes(lastBytesDownloaded) + ", cache " + AssetSyncReport.formatBytes(cacheBytes));

		if (active.namespaces().contains(AssetSyncDemo.NAMESPACE)) {
			MinecraftClient client = MinecraftClient.getInstance();
			boolean texture = client.getResourceManager().getResource(Identifier.of(AssetSyncDemo.NAMESPACE, AssetSyncDemo.TEXTURE)).isPresent();

			lines.add("asset sync demo: lang '" + Language.getInstance().get(AssetSyncDemo.LANG_KEY) + "', texture " + (texture ? "found" : "MISSING"));
		}

		return lines;
	}

	static void fireReady() {
		for (Runnable callback : READY) {
			try {
				callback.run();
			}
			catch (RuntimeException e) {
				StrutConstants.LOGGER.warn("[Strut asset sync] an onAssetsReady callback failed: {}", e.toString());
			}
		}
	}
}
