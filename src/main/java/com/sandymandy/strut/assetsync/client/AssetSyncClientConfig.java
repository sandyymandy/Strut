package com.sandymandy.strut.assetsync.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;

/**
 * The player's own controls over server assets, read from {@code config/strut-client.json}
 * <p>
 * A server can only ever <i>offer</i>; these are the player's limits, enforced on the client whatever the server says.
 * The file is written with defaults the first time, so it can be found and edited. A missing, unreadable or malformed
 * file means the defaults; a value out of range is pulled back into range, never trusted
 *
 * @param acceptServerAssets Whether to take server assets at all. Off means every sync is declined
 * @param maxDownloadPerServerMiB The most one sync may download (what the cache lacks, not the total)
 * @param cacheLimitMiB How large the on-disk cache may grow before least-recently-used files are deleted
 * @param maxTexturePixels The largest texture (width x height) a synced PNG may declare, so a tiny file cannot decode to a
 *                         huge image
 */
public record AssetSyncClientConfig(boolean acceptServerAssets, long maxDownloadPerServerMiB, long cacheLimitMiB, long maxTexturePixels) {
	private static final Logger LOGGER = LogManager.getLogger("Strut");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final AssetSyncClientConfig DEFAULT = new AssetSyncClientConfig(true, 512, 1024, 8192L * 8192L);

	private static final long MAX_MIB = 1024 * 1024;

	public AssetSyncClientConfig {
		maxDownloadPerServerMiB = clamp(maxDownloadPerServerMiB, 0, MAX_MIB);
		cacheLimitMiB = clamp(cacheLimitMiB, 0, MAX_MIB);
		maxTexturePixels = clamp(maxTexturePixels, 1, 1L << 32);
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * What the client session should use
	 */
	public ClientAssetSession.Config toSessionConfig() {
		return new ClientAssetSession.Config(this.acceptServerAssets, this.maxDownloadPerServerMiB * 1024 * 1024, SyncLimits.DEFAULT, 800);
	}

	public long cacheLimitBytes() {
		return this.cacheLimitMiB * 1024 * 1024;
	}

	/**
	 * Reads the file, writing the defaults there if it does not exist. Never throws
	 */
	public static AssetSyncClientConfig load(Path file) {
		if (!Files.exists(file)) {
			DEFAULT.save(file);

			return DEFAULT;
		}

		try {
			JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

			return new AssetSyncClientConfig(
					bool(json, "acceptServerAssets", DEFAULT.acceptServerAssets),
					number(json, "maxDownloadPerServerMiB", DEFAULT.maxDownloadPerServerMiB),
					number(json, "cacheLimitMiB", DEFAULT.cacheLimitMiB),
					number(json, "maxTexturePixels", DEFAULT.maxTexturePixels));
		}
		catch (IOException | RuntimeException e) {
			LOGGER.warn("[Strut asset sync] could not read {}, using defaults: {}", file, e.toString());

			return DEFAULT;
		}
	}

	private static boolean bool(JsonObject json, String key, boolean fallback) {
		try {
			// Strict: Gson would read the string "yes please" as false, which silently turns the feature off
			return json.has(key) && json.get(key).getAsJsonPrimitive().isBoolean() ? json.get(key).getAsBoolean() : fallback;
		}
		catch (RuntimeException e) {
			return fallback;
		}
	}

	private static long number(JsonObject json, String key, long fallback) {
		try {
			return json.has(key) ? json.get(key).getAsLong() : fallback;
		}
		catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * Writes this config. A failure is logged and ignored: the player just does not get an editable file
	 */
	public void save(Path file) {
		JsonObject json = new JsonObject();

		json.addProperty("acceptServerAssets", this.acceptServerAssets);
		json.addProperty("maxDownloadPerServerMiB", this.maxDownloadPerServerMiB);
		json.addProperty("cacheLimitMiB", this.cacheLimitMiB);
		json.addProperty("maxTexturePixels", this.maxTexturePixels);

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(json));
		}
		catch (IOException e) {
			LOGGER.warn("[Strut asset sync] could not write {}: {}", file, e.toString());
		}
	}
}
