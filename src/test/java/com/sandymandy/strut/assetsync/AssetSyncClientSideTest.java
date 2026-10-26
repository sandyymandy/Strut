package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Done;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.client.AssetCache;
import com.sandymandy.strut.assetsync.client.AssetSyncClientConfig;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.client.SyncedAssetIndex;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetSyncException;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.PngGuard;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;

/**
 * The parts of the client that do not need a game - the player's config, the index the virtual pack
 * is a view over, the PNG header guard, and dropping a session when the connection goes
 */
class AssetSyncClientSideTest {
	@TempDir
	Path dir;

	// ---------------------------------------------------------------------------------------------
	// Config
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a missing config file is created with the defaults, and reading it back gives the same values")
	void configIsCreated() throws IOException {
		Path file = this.dir.resolve("config/strut-client.json");

		assertEquals(AssetSyncClientConfig.DEFAULT, AssetSyncClientConfig.load(file));
		assertTrue(Files.exists(file), "written so the player can find and edit it");
		assertEquals(AssetSyncClientConfig.DEFAULT, AssetSyncClientConfig.load(file));
	}

	@Test
	@DisplayName("edited values are read, and unknown or missing keys keep their defaults")
	void configIsRead() throws IOException {
		Path file = this.dir.resolve("c.json");

		Files.writeString(file, "{\"acceptServerAssets\": false, \"maxDownloadPerServerMiB\": 64, \"somethingElse\": 1}");

		AssetSyncClientConfig config = AssetSyncClientConfig.load(file);

		assertFalse(config.acceptServerAssets());
		assertEquals(64, config.maxDownloadPerServerMiB());
		assertEquals(AssetSyncClientConfig.DEFAULT.cacheLimitMiB(), config.cacheLimitMiB());
		assertEquals(AssetSyncClientConfig.DEFAULT.maxTexturePixels(), config.maxTexturePixels());
	}

	@Test
	@DisplayName("a malformed or wrongly typed file falls back to the defaults and never throws")
	void badConfig() throws IOException {
		Path file = this.dir.resolve("c.json");

		for (String content : List.of("", "not json", "[]", "null", "{\"acceptServerAssets\": \"yes please\"}", "{\"cacheLimitMiB\": \"big\"}", "{\"maxDownloadPerServerMiB\": {}}")) {
			Files.writeString(file, content);

			assertDoesNotThrow(() -> AssetSyncClientConfig.load(file), content);
		}

		Files.writeString(file, "{\"acceptServerAssets\": \"yes please\", \"cacheLimitMiB\": \"big\"}");

		assertEquals(AssetSyncClientConfig.DEFAULT, AssetSyncClientConfig.load(file));
	}

	@Test
	@DisplayName("out-of-range values are pulled back into range")
	void configIsClamped() {
		AssetSyncClientConfig config = new AssetSyncClientConfig(true, -5, Long.MAX_VALUE, -1);

		assertEquals(0, config.maxDownloadPerServerMiB());
		assertTrue(config.cacheLimitMiB() <= 1024 * 1024);
		assertEquals(1, config.maxTexturePixels());
		assertTrue(config.cacheLimitBytes() > 0, "no overflow to a negative byte count");
	}

	@Test
	@DisplayName("the session config carries the player's switch and download cap in bytes")
	void sessionConfig() {
		ClientAssetSession.Config config = new AssetSyncClientConfig(false, 2, 10, 100).toSessionConfig();

		assertFalse(config.accept());
		assertEquals(2L * 1024 * 1024, config.maxDownloadBytes());
	}

	// ---------------------------------------------------------------------------------------------
	// Index
	// ---------------------------------------------------------------------------------------------

	private SyncedAssetIndex index(AssetCache cache, String... paths) {
		AssetManifest.Builder builder = new AssetManifest.Builder(SyncLimits.DEFAULT);
		int seed = 0;

		for (String path : paths) {
			byte[] data = AssetSyncLink.filled(20, seed++);

			cache.put(data);
			builder.add(path, AssetManifest.sha256Hex(data), data.length);
		}

		return SyncedAssetIndex.build(builder.build(), cache);
	}

	@Test
	@DisplayName("find answers exactly the listed paths with the verified cache file, per namespace")
	void indexFind() throws IOException {
		AssetCache cache = new AssetCache(this.dir.resolve("cache"));
		SyncedAssetIndex index = index(cache, "mymod/textures/a.png", "mymod/sounds.json", "other/textures/a.png");

		assertNotNull(index.find("mymod", "textures/a.png"));
		assertNotNull(index.find("other", "textures/a.png"));
		assertNull(index.find("mymod", "textures/missing.png"));
		assertNull(index.find("mymod", "../textures/a.png"), "a path is only ever a key, never resolved");
		assertNull(index.find("nope", "textures/a.png"));
		assertEquals(3, index.fileCount());
		assertEquals(Set.of("mymod", "other"), index.namespaces());
		assertEquals(20, Files.size(index.find("mymod", "sounds.json")));
		assertTrue(index.find("mymod", "sounds.json").startsWith(this.dir.resolve("cache")));
	}

	@Test
	@DisplayName("list returns what is at or under a directory, like the game's own lookups - and not a sibling that merely shares the prefix")
	void indexList() {
		AssetCache cache = new AssetCache(this.dir.resolve("cache"));
		SyncedAssetIndex index = index(cache, "mymod/textures/a.png", "mymod/textures/sub/b.png", "mymod/textures_extra/c.png", "mymod/sounds.json", "other/textures/d.png");

		assertEquals(List.of("textures/a.png", "textures/sub/b.png"), index.list("mymod", "textures"));
		assertEquals(List.of("textures/sub/b.png"), index.list("mymod", "textures/sub"));
		assertEquals(4, index.list("mymod", "").size(), "an empty prefix lists the whole namespace");
		assertEquals(List.of(), index.list("mymod", "sound"), "'sound' is not a directory");
		assertEquals(List.of(), index.list("nobody", ""));
	}

	@Test
	@DisplayName("an index cannot be built over a file the cache does not hold, or holds torn")
	void indexNeedsEveryFile() throws IOException {
		AssetCache cache = new AssetCache(this.dir.resolve("cache"));
		byte[] data = AssetSyncLink.filled(50, 1);
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), 50)), SyncLimits.DEFAULT);

		assertThrows(AssetSyncException.class, () -> SyncedAssetIndex.build(manifest, cache));

		cache.put(data);

		assertDoesNotThrow(() -> SyncedAssetIndex.build(manifest, cache));

		Files.write(cache.pathOf(AssetManifest.sha256Hex(data)), new byte[3]);

		assertThrows(AssetSyncException.class, () -> SyncedAssetIndex.build(manifest, cache));
	}

	@Test
	@DisplayName("the index carries the manifest hash, so identical assets can be recognised")
	void indexHash() {
		AssetCache cache = new AssetCache(this.dir.resolve("cache"));

		assertEquals(index(cache, "mymod/a.png", "mymod/b.png").manifestHash(), index(cache, "mymod/a.png", "mymod/b.png").manifestHash());
		assertNotEquals(index(cache, "mymod/a.png", "mymod/b.png").manifestHash(), index(cache, "mymod/a.png", "mymod/c.png").manifestHash());
	}

	// ---------------------------------------------------------------------------------------------
	// PNG guard
	// ---------------------------------------------------------------------------------------------

	private static byte[] pngHeader(long width, long height) {
		byte[] header = new byte[24];
		byte[] signature = {(byte)0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

		System.arraycopy(signature, 0, header, 0, 8);
		header[11] = 13;
		header[12] = 'I';
		header[13] = 'H';
		header[14] = 'D';
		header[15] = 'R';

		for (int i = 0; i < 4; i++) {
			header[16 + i] = (byte)(width >> (24 - 8 * i));
			header[20 + i] = (byte)(height >> (24 - 8 * i));
		}

		return header;
	}

	@Test
	@DisplayName("a PNG within the pixel limit passes, exactly at the limit passes, one over is refused")
	void pngLimit() {
		assertNull(PngGuard.problem(pngHeader(16, 16), 1000));
		assertNull(PngGuard.problem(pngHeader(1000, 1), 1000), "exactly at the limit");
		assertNotNull(PngGuard.problem(pngHeader(1001, 1), 1000));
		assertNotNull(PngGuard.problem(pngHeader(1, 1001), 1000));
		assertNull(PngGuard.problem(pngHeader(32, 31), 32 * 31));
		assertNotNull(PngGuard.problem(pngHeader(32, 32), 32 * 31));
	}

	@Test
	@DisplayName("two huge sides whose product overflows a long are still refused")
	void pngOverflow() {
		assertNotNull(PngGuard.problem(pngHeader(4_294_967_295L, 4_294_967_295L), 8192L * 8192L));
		assertNotNull(PngGuard.problem(pngHeader(4_294_967_295L, 3), 8192L * 8192L));
		assertNotNull(PngGuard.problem(pngHeader(3_037_000_500L, 3_037_000_500L), 8192L * 8192L), "just past the square root of 2^63");
	}

	@Test
	@DisplayName("a bad signature, a missing IHDR, a zero size and a short header are all refused")
	void pngMalformed() {
		byte[] badSignature = pngHeader(16, 16);

		badSignature[1] = 'X';

		byte[] badChunk = pngHeader(16, 16);

		badChunk[12] = 'X';

		assertEquals("not a PNG", PngGuard.problem(badSignature, 1000));
		assertEquals("no IHDR chunk", PngGuard.problem(badChunk, 1000));
		assertNotNull(PngGuard.problem(pngHeader(0, 16), 1000));
		assertNotNull(PngGuard.problem(pngHeader(16, 0), 1000));
		assertNotNull(PngGuard.problem(new byte[10], 1000));
		assertNotNull(PngGuard.problem(new byte[0], 1000));
		assertNotNull(PngGuard.problem("<html>not an image at all</html>".getBytes(), 1000));
	}

	@Test
	@DisplayName("the file form reads the header from disk, and refuses a file that is short or missing")
	void pngFromFile() throws IOException {
		Path ok = this.dir.resolve("ok");
		Path bomb = this.dir.resolve("bomb");
		Path shortFile = this.dir.resolve("short");

		Files.write(ok, pngHeader(64, 64));
		Files.write(bomb, pngHeader(60_000, 60_000));
		Files.write(shortFile, new byte[5]);

		assertNull(PngGuard.problem(ok, 8192L * 8192L));
		assertNotNull(PngGuard.problem(bomb, 8192L * 8192L), "a 24-byte file that would decode to gigabytes");
		assertNotNull(PngGuard.problem(shortFile, 100));
		assertEquals("unreadable", PngGuard.problem(this.dir.resolve("does-not-exist"), 100));
	}

	// ---------------------------------------------------------------------------------------------
	// Dropping a session
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("discarding a session mid-download removes the partial file and sends nothing, since the connection is gone")
	void discardMidTransfer() throws IOException {
		byte[] data = AssetSyncLink.filled(1000, 1);
		List<AssetSyncMessage> sent = new ArrayList<>();
		AssetCache cache = new AssetCache(this.dir.resolve("cache"));
		ClientAssetSession client = new ClientAssetSession(cache, ClientAssetSession.Config.DEFAULT, sent::add);

		client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, true, List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), 1000))));
		client.receive(new Chunk(AssetManifest.sha256Hex(data), 0, Arrays.copyOfRange(data, 0, 400)));

		int before = sent.size();

		client.discard();

		assertEquals(before, sent.size(), "nothing sent");
		assertTrue(sent.stream().noneMatch(Done.class::isInstance));
		assertEquals(ClientAssetSession.State.FINISHED, client.state());

		Path tmp = this.dir.resolve("cache/tmp");

		try (Stream<Path> files = Files.isDirectory(tmp) ? Files.list(tmp) : Stream.<Path>empty()) {
			assertEquals(0, files.count());
		}

		client.receive(new Chunk(AssetManifest.sha256Hex(data), 400, Arrays.copyOfRange(data, 400, 1000)));

		assertEquals(before, sent.size(), "a finished session ignores late messages");
		assertFalse(cache.has(AssetManifest.sha256Hex(data)));
	}
}
