package com.sandymandy.strut.assetsync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.client.AssetCache;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetSyncException;
import com.sandymandy.strut.assetsync.protocol.ChunkAssembler;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;

/**
 * The client's file store, and receiving a file into it
 */
class AssetCacheAndAssemblerTest {
	@TempDir
	Path dir;

	private static ManifestEntry entryFor(byte[] data) {
		return new ManifestEntry("ns/file.png", AssetManifest.sha256Hex(data), data.length);
	}

	private static byte[] slice(byte[] data, int from, int to) {
		return Arrays.copyOfRange(data, from, to);
	}

	private long leftoverTempFiles() throws IOException {
		Path tmp = this.dir.resolve("tmp");

		if (!Files.isDirectory(tmp))
			return 0;

		try (Stream<Path> files = Files.list(tmp)) {
			return files.count();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Cache
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a stored file is found by its hash, laid out under the first two hex digits")
	void storeAndFind() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		byte[] data = AssetSyncLink.bytes("hello");
		String hash = cache.put(data);

		assertEquals(AssetManifest.sha256Hex(data), hash);
		assertTrue(cache.has(hash));
		assertArrayEquals(data, Files.readAllBytes(cache.find(hash).orElseThrow()));
		assertEquals(this.dir.resolve(hash.substring(0, 2)).resolve(hash), cache.pathOf(hash));
		assertFalse(cache.has("f".repeat(64)));
		assertTrue(cache.find("f".repeat(64)).isEmpty());
	}

	@Test
	@DisplayName("a cache path can only ever be built from a real hash, never from a server-chosen name")
	void onlyHashesMakePaths() {
		AssetCache cache = new AssetCache(this.dir);

		for (String bad : new String[] {"../../evil", "abc", "A".repeat(64), "g".repeat(64), "", "a".repeat(63)})
			assertThrows(IllegalArgumentException.class, () -> cache.pathOf(bad), bad);
	}

	@Test
	@DisplayName("storing the same content twice keeps one file and leaves no temp file behind")
	void storingTwice() throws IOException {
		AssetCache cache = new AssetCache(this.dir);

		cache.put(AssetSyncLink.bytes("same"));
		cache.put(AssetSyncLink.bytes("same"));

		assertEquals(4, cache.totalBytes());
		assertEquals(0, leftoverTempFiles());
	}

	@Test
	@DisplayName("prune deletes the least recently used files first, spares kept ones, and does nothing when under the limit")
	void prune() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		String oldest = cache.put(AssetSyncLink.filled(100, 1));
		String middle = cache.put(AssetSyncLink.filled(100, 2));
		String newest = cache.put(AssetSyncLink.filled(100, 3));

		Files.setLastModifiedTime(cache.pathOf(oldest), FileTime.fromMillis(1000));
		Files.setLastModifiedTime(cache.pathOf(middle), FileTime.fromMillis(2000));
		Files.setLastModifiedTime(cache.pathOf(newest), FileTime.fromMillis(3000));

		assertEquals(0, cache.prune(1000, Set.of()), "already under the limit");
		assertEquals(100, cache.prune(250, Set.of()));
		assertFalse(cache.has(oldest));
		assertTrue(cache.has(middle));
		assertTrue(cache.has(newest));

		// Only the kept file may survive an impossible limit
		cache.prune(0, Set.of(newest));

		assertFalse(cache.has(middle));
		assertTrue(cache.has(newest));
	}

	@Test
	@DisplayName("using a file refreshes it, so pruning takes an unused one before a used one")
	void findRefreshes() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		String used = cache.put(AssetSyncLink.filled(100, 1));
		String unused = cache.put(AssetSyncLink.filled(100, 2));

		Files.setLastModifiedTime(cache.pathOf(used), FileTime.fromMillis(1000));
		Files.setLastModifiedTime(cache.pathOf(unused), FileTime.fromMillis(2000));

		cache.find(used);
		cache.prune(100, Set.of());

		assertTrue(cache.has(used));
		assertFalse(cache.has(unused));
	}

	@Test
	@DisplayName("an empty or missing cache prunes and totals as zero")
	void emptyCache() {
		AssetCache cache = new AssetCache(this.dir.resolve("never/made"));

		assertEquals(0, cache.totalBytes());
		assertEquals(0, cache.prune(0, Set.of()));
	}

	// ---------------------------------------------------------------------------------------------
	// Assembler
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("ordered chunks assemble a file into the cache under its hash")
	void assembles() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		byte[] data = AssetSyncLink.filled(1000, 7);
		ChunkAssembler assembler = new ChunkAssembler(cache, entryFor(data));

		assertFalse(assembler.accept(0, slice(data, 0, 400)));
		assertFalse(assembler.accept(400, slice(data, 400, 900)));
		assertEquals(900, assembler.received());
		assertFalse(cache.has(AssetManifest.sha256Hex(data)), "not visible until complete");
		assertTrue(assembler.accept(900, slice(data, 900, 1000)));
		assertArrayEquals(data, Files.readAllBytes(cache.pathOf(AssetManifest.sha256Hex(data))));
		assertEquals(0, leftoverTempFiles());
	}

	@Test
	@DisplayName("a zero-length file completes on its single empty chunk")
	void emptyFile() {
		AssetCache cache = new AssetCache(this.dir);
		ChunkAssembler assembler = new ChunkAssembler(cache, entryFor(new byte[0]));

		assertTrue(assembler.accept(0, new byte[0]));
		assertTrue(cache.has(AssetManifest.sha256Hex(new byte[0])));
	}

	@Test
	@DisplayName("a wrong hash is refused and nothing is cached or left behind")
	void wrongHash() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		byte[] real = AssetSyncLink.filled(500, 1);
		byte[] forged = AssetSyncLink.filled(500, 2);
		ChunkAssembler assembler = new ChunkAssembler(cache, entryFor(real));

		assertThrows(AssetSyncException.class, () -> assembler.accept(0, forged));
		assertFalse(cache.has(AssetManifest.sha256Hex(real)));
		assertFalse(cache.has(AssetManifest.sha256Hex(forged)), "the forged bytes must not be cached under their own hash either");
		assertEquals(0, cache.totalBytes());
		assertEquals(0, leftoverTempFiles());
	}

	@Test
	@DisplayName("a chunk at the wrong offset is refused, whether skipping ahead, repeating, or rewinding")
	void wrongOffset() throws IOException {
		byte[] data = AssetSyncLink.filled(1000, 3);

		for (long badOffset : new long[] {100, 0, 999}) {
			AssetCache cache = new AssetCache(this.dir);
			ChunkAssembler assembler = new ChunkAssembler(cache, entryFor(data));

			assembler.accept(0, slice(data, 0, 300));

			long offset = badOffset;

			assertThrows(AssetSyncException.class, () -> assembler.accept(offset, slice(data, 300, 400)), "offset " + offset);
			assertEquals(0, leftoverTempFiles());
		}
	}

	@Test
	@DisplayName("a chunk running past the declared size is refused")
	void oversize() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		byte[] data = AssetSyncLink.filled(100, 3);
		ChunkAssembler assembler = new ChunkAssembler(cache, entryFor(data));

		assertThrows(AssetSyncException.class, () -> assembler.accept(0, AssetSyncLink.filled(101, 3)));
		assertEquals(0, leftoverTempFiles());
	}

	@Test
	@DisplayName("a chunk after completion is refused, and closing an unfinished download discards it")
	void afterCompletionAndClose() throws IOException {
		AssetCache cache = new AssetCache(this.dir);
		byte[] data = AssetSyncLink.filled(100, 3);
		ChunkAssembler done = new ChunkAssembler(cache, entryFor(data));

		done.accept(0, data);

		assertThrows(AssetSyncException.class, () -> done.accept(100, new byte[0]));

		ChunkAssembler abandoned = new ChunkAssembler(cache, entryFor(AssetSyncLink.filled(100, 9)));

		abandoned.accept(0, AssetSyncLink.filled(50, 9));
		abandoned.close();
		abandoned.close();

		assertEquals(0, leftoverTempFiles());
		assertTrue(cache.has(AssetManifest.sha256Hex(data)), "closing one download never touches a finished file");
	}
}
