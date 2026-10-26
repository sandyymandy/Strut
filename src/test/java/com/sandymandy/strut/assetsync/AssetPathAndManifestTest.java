package com.sandymandy.strut.assetsync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.AssetSyncException;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;
import com.sandymandy.strut.assetsync.protocol.SyncResult;

/**
 * What a synced path may look like, and what a manifest is
 */
class AssetPathAndManifestTest {
	private static final String HASH_A = "a".repeat(64);
	private static final String HASH_B = "b".repeat(64);

	// ---------------------------------------------------------------------------------------------
	// Paths
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a data path maps to <namespace>/<path> and into assets/ inside the pack")
	void mapsDataPaths() {
		assertEquals(Optional.of("mymod/geo/creatures/eve.strutmodel"), AssetPath.fromDataPath("mymod", "geo/creatures/eve.strutmodel"));
		assertEquals("assets/mymod/sounds.json", AssetPath.inPack("mymod/sounds.json"));
		assertTrue(AssetPath.isValid("mymod/textures/entity/eve.png"));
		assertTrue(AssetPath.isValid("mymod/strut/event_bindings/creatures/eve.json"));
	}

	@Test
	@DisplayName("every allowed file type passes and code-like types do not")
	void extensionAllowList() {
		for (String extension : AssetPath.ALLOWED_EXTENSIONS)
			assertTrue(AssetPath.isValid("ns/some/file" + extension), extension);

		for (String bad : List.of("ns/a.class", "ns/a.jar", "ns/a.js", "ns/a.fsh", "ns/a.vsh", "ns/a.glsl", "ns/a.exe", "ns/noextension", "ns/a.png.exe"))
			assertEquals("file type not allowed", AssetPath.rejection(bad), bad);
	}

	@Test
	@DisplayName("hostile paths are rejected: climbing, absolute, empty segments, capitals, spaces, backslashes, no namespace")
	void hostilePaths() {
		for (String bad : List.of(
				"ns/../../etc/passwd.png", "ns/a/../b.png", "ns/./a.png", "../ns/a.png", "/ns/a.png", "ns//a.png", "ns/a/.png/",
				"ns/A.png", "ns/a b.png", "ns\\a.png", "ns/a\\b.png", "a.png", "", "ns/", "ns:a/b.png", "n s/a.png", "ns/é.png"))
			assertNotNull(AssetPath.rejection(bad), "should reject: '" + bad + "'");

		assertTrue(AssetPath.fromDataPath("mymod", "../x.png").isEmpty());
		assertTrue(AssetPath.fromDataPath("P H", "x.png").isEmpty());
	}

	@Test
	@DisplayName("an over-long path is rejected")
	void overLong() {
		assertNotNull(AssetPath.rejection("ns/" + "a".repeat(AssetPath.MAX_LENGTH) + ".png"));
		assertNull(AssetPath.rejection("ns/" + "a".repeat(AssetPath.MAX_LENGTH - 10) + ".png"));
	}

	// ---------------------------------------------------------------------------------------------
	// Manifest
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the builder sorts, dedupes by path with the later add winning, and reports what it skipped")
	void builder() {
		AssetManifest.Builder builder = new AssetManifest.Builder(new SyncLimits(1000));

		assertNull(builder.add("ns/b.png", HASH_A, 10));
		assertNull(builder.add("ns/a.png", HASH_B, 20));
		assertNull(builder.add("ns/b.png", HASH_B, 30), "an override from a higher pack");
		assertNotNull(builder.add("ns/x.class", HASH_A, 1));
		assertNotNull(builder.add("ns/big.png", HASH_A, 1001));
		assertNotNull(builder.add("ns/bad_hash.png", "xyz", 1));
		assertNotNull(builder.add("ns/negative.png", HASH_A, -1));

		AssetManifest manifest = builder.build();

		assertEquals(List.of("ns/a.png", "ns/b.png"), manifest.entries().stream().map(ManifestEntry::path).toList());
		assertEquals(HASH_B, manifest.entries().get(1).sha256(), "the later add won");
		assertEquals(50, manifest.totalBytes());
		assertEquals(4, builder.rejected().size());
		assertTrue(builder.rejected().get(0).startsWith("ns/x.class"));
	}

	@Test
	@DisplayName("the manifest hash is equal for equal content in any add order, and changes with any change")
	void manifestHash() {
		AssetManifest one = manifest(entry("ns/a.png", HASH_A, 1), entry("ns/b.png", HASH_B, 2));
		AssetManifest reordered = manifest(entry("ns/b.png", HASH_B, 2), entry("ns/a.png", HASH_A, 1));

		assertEquals(one.hash(), reordered.hash());
		assertNotEquals(one.hash(), manifest(entry("ns/a.png", HASH_A, 1), entry("ns/b.png", HASH_B, 3)).hash(), "size");
		assertNotEquals(one.hash(), manifest(entry("ns/a.png", HASH_B, 1), entry("ns/b.png", HASH_B, 2)).hash(), "content");
		assertNotEquals(one.hash(), manifest(entry("ns/a.png", HASH_A, 1), entry("ns/c.png", HASH_B, 2)).hash(), "path");
		assertNotEquals(one.hash(), AssetManifest.empty().hash());
	}

	@Test
	@DisplayName("a received manifest is refused whole at the first bad entry or duplicate path")
	void receivedManifestIsRefusedWhole() {
		SyncLimits limits = new SyncLimits(1000);

		assertThrows(AssetSyncException.class, () -> AssetManifest.of(List.of(entry("ns/ok.png", HASH_A, 1), entry("ns/../evil.png", HASH_A, 1)), limits));
		assertThrows(AssetSyncException.class, () -> AssetManifest.of(List.of(entry("ns/ok.png", HASH_A, 1), entry("ns/ok.png", HASH_B, 1)), limits));
		assertThrows(AssetSyncException.class, () -> AssetManifest.of(List.of(entry("ns/ok.png", HASH_A, 5000)), limits));
		assertThrows(AssetSyncException.class, () -> AssetManifest.of(List.of(entry("ns/ok.png", "UPPER".repeat(13), 1)), limits));
	}

	@Test
	@DisplayName("two paths with the same content are one distinct hash")
	void distinctHashes() {
		AssetManifest manifest = manifest(entry("ns/a.png", HASH_A, 1), entry("ns/b.png", HASH_A, 1), entry("ns/c.png", HASH_B, 2));

		assertEquals(List.of(HASH_A, HASH_B), List.copyOf(manifest.distinctHashes()));
		assertEquals("ns/a.png", manifest.firstWithHash(HASH_A).path());
		assertNull(manifest.firstWithHash("c".repeat(64)));
	}

	@Test
	@DisplayName("pages respect the byte budget, keep every entry once in order, and an empty manifest is one empty page")
	void paging() {
		List<ManifestEntry> entries = new ArrayList<>();

		for (int i = 0; i < 100; i++)
			entries.add(entry(String.format("ns/file%03d.png", i), HASH_A, i));

		AssetManifest manifest = manifest(entries.toArray(new ManifestEntry[0]));
		List<List<ManifestEntry>> pages = manifest.pages(1000);

		assertTrue(pages.size() > 1);
		assertEquals(manifest.entries(), pages.stream().flatMap(List::stream).toList());
		assertTrue(pages.stream().allMatch(page -> !page.isEmpty()));

		assertEquals(1, AssetManifest.empty().pages(1000).size());
		assertTrue(AssetManifest.empty().pages(1000).get(0).isEmpty());

		// A page always holds at least one entry, even if that one alone is over budget
		assertEquals(100, manifest.pages(1).size());
	}

	@Test
	@DisplayName("sync result wire codes are stable and only client-sendable results decode")
	void resultWireCodes() {
		assertEquals(SyncResult.LOADED, SyncResult.fromWire(0), "the order is protocol: never reorder it");
		assertEquals(SyncResult.DECLINED, SyncResult.fromWire(1));
		assertEquals(SyncResult.TOO_LARGE, SyncResult.fromWire(2));
		assertEquals(SyncResult.FAILED, SyncResult.fromWire(3));
		assertThrows(IllegalArgumentException.class, () -> SyncResult.fromWire(4), "a client may not claim the server's own results");
		assertThrows(IllegalArgumentException.class, () -> SyncResult.fromWire(-1));
	}

	private static ManifestEntry entry(String path, String hash, long size) {
		return new ManifestEntry(path, hash, size);
	}

	private static AssetManifest manifest(ManifestEntry... entries) {
		return AssetManifest.of(List.of(entries), new SyncLimits(1_000_000));
	}
}
