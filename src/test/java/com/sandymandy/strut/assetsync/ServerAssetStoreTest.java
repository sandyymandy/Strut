package com.sandymandy.strut.assetsync;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetStore;

/**
 * What a server offers, and the public API that feeds it
 */
class ServerAssetStoreTest {
	private final List<StrutAssetSync.ServerAssetSource> added = new ArrayList<>();

	private void addSource(StrutAssetSync.ServerAssetSource source) {
		this.added.add(source);
		StrutAssetSync.addSource(source);
	}

	@AfterEach
	void reset() {
		this.added.forEach(StrutAssetSync::removeSource);
		StrutAssetSync.setLimits(StrutAssetSync.DEFAULT_MAX_FILE_BYTES, StrutAssetSync.DEFAULT_MAX_TOTAL_BYTES);
		StrutAssetSync.setRequired(false);
		StrutAssetSync.rebuild(sink -> {});
	}

	private static ServerAssetStore store(long maxFile, long maxTotal, Object... pathsAndBytes) {
		ServerAssetStore.Builder builder = ServerAssetStore.builder(maxFile, maxTotal);

		for (int i = 0; i < pathsAndBytes.length; i += 2)
			builder.add((String)pathsAndBytes[i], (byte[])pathsAndBytes[i + 1]);

		return builder.build();
	}

	@Test
	@DisplayName("the manifest lists what was added, hashed, and the reader serves exactly those bytes")
	void servesWhatItOffers() throws IOException {
		byte[] model = AssetSyncLink.filled(1000, 1);
		ServerAssetStore store = store(10_000, 100_000, "mymod/a.strutmodel", model, "mymod/b.png", AssetSyncLink.filled(50, 2));
		ManifestEntry entry = store.manifest().entries().get(0);

		assertEquals(2, store.manifest().entries().size());
		assertEquals(AssetManifest.sha256Hex(model), entry.sha256());
		assertArrayEquals(Arrays.copyOfRange(model, 100, 300), store.reader().read(entry, 100, 200));
		assertArrayEquals(model, store.reader().read(entry, 0, 1000));
		assertEquals(1050, store.heldBytes());
	}

	@Test
	@DisplayName("the reader refuses slices outside a file, and files it does not hold")
	void readerIsBounded() {
		ServerAssetStore store = store(10_000, 100_000, "mymod/a.png", AssetSyncLink.filled(100, 1));
		ManifestEntry entry = store.manifest().entries().get(0);

		assertThrows(IOException.class, () -> store.reader().read(entry, 90, 20));
		assertThrows(IOException.class, () -> store.reader().read(entry, -1, 5));
		assertThrows(IOException.class, () -> store.reader().read(entry, 0, -5));
		assertThrows(IOException.class, () -> store.reader().read(new ManifestEntry("mymod/x.png", "f".repeat(64), 5), 0, 5));
	}

	@Test
	@DisplayName("identical content under two paths is held once")
	void sharedContentIsHeldOnce() {
		byte[] same = AssetSyncLink.filled(1000, 1);
		ServerAssetStore store = store(10_000, 100_000, "mymod/a.png", same, "mymod/b.png", same.clone());

		assertEquals(2, store.manifest().entries().size());
		assertEquals(1000, store.heldBytes());
	}

	@Test
	@DisplayName("bad paths, forbidden types, reserved namespaces and oversize files are refused and reported, the rest still served")
	void refusesAndReports() {
		ServerAssetStore store = store(500, 100_000,
				"mymod/ok.png", AssetSyncLink.filled(10, 1),
				"mymod/evil.class", new byte[10],
				"mymod/../up.png", new byte[10],
				"minecraft/textures/gui/title.png", new byte[10],
				"mymod/big.png", new byte[501],
				"mymod/${jndi:x}.png", new byte[10]);

		assertEquals(List.of("mymod/ok.png"), store.manifest().entries().stream().map(ManifestEntry::path).toList());
		assertEquals(5, store.rejected().size());
		assertTrue(store.rejected().stream().noneMatch(line -> line.contains("${")), "no raw hostile text in the log lines");
	}

	@Test
	@DisplayName("a memory budget cuts off the excess deterministically, in path order, and dedupe does not count twice")
	void memoryBudget() {
		byte[] shared = AssetSyncLink.filled(400, 1);
		ServerAssetStore store = store(1000, 1000,
				"mymod/a.png", shared, "mymod/b.png", shared.clone(), "mymod/c.png", AssetSyncLink.filled(400, 2), "mymod/d.png", AssetSyncLink.filled(400, 3));

		// a (400) + b (shared, free) + c (400) = 800; d would make 1200
		assertEquals(List.of("mymod/a.png", "mymod/b.png", "mymod/c.png"), store.manifest().entries().stream().map(ManifestEntry::path).toList());
		assertEquals(800, store.heldBytes());
		assertEquals(1, store.rejected().size());
		assertTrue(store.rejected().get(0).startsWith("mymod/d.png"));
	}

	@Test
	@DisplayName("a later add for a path wins")
	void laterAddWins() throws IOException {
		ServerAssetStore store = store(1000, 10_000, "mymod/a.png", AssetSyncLink.bytes("first"), "mymod/a.png", AssetSyncLink.bytes("second"));

		assertEquals(1, store.manifest().entries().size());
		assertArrayEquals(AssetSyncLink.bytes("second"), store.reader().read(store.manifest().entries().get(0), 0, 6));
	}

	@Test
	@DisplayName("an empty store is empty")
	void emptyStore() {
		assertTrue(ServerAssetStore.EMPTY.isEmpty());
		assertTrue(store(10, 10).isEmpty());
	}

	// ---------------------------------------------------------------------------------------------
	// The public API
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a rebuild takes the scan first, then every source, and sources override the scan")
	void rebuildOrder() throws IOException {
		addSource(sink -> sink.add("mymod", "sounds.json", AssetSyncLink.bytes("from code")));

		ServerAssetStore built = StrutAssetSync.rebuild(sink -> {
			sink.add("mymod", "sounds.json", AssetSyncLink.bytes("from data pack"));
			sink.add("mymod", "geo/a.strutmodel", AssetSyncLink.bytes("model"));
		});

		assertSame(built, StrutAssetSync.currentStore());
		assertEquals(2, built.manifest().entries().size());

		ManifestEntry sounds = built.manifest().entries().stream().filter(e -> e.path().equals("mymod/sounds.json")).findFirst().orElseThrow();

		assertArrayEquals(AssetSyncLink.bytes("from code"), built.reader().read(sounds, 0, (int)sounds.size()));
	}

	@Test
	@DisplayName("a source that throws does not stop the rebuild or take the others with it")
	void failingSource() {
		List<String> ran = new ArrayList<>();

		addSource(sink -> {
			throw new IllegalStateException("boom");
		});
		addSource(sink -> {
			ran.add("second");
			sink.add("mymod", "b.png", new byte[5]);
		});

		ServerAssetStore built = StrutAssetSync.rebuild(sink -> sink.add("mymod", "a.png", new byte[5]));

		assertEquals(List.of("second"), ran);
		assertEquals(2, built.manifest().entries().size());
	}

	@Test
	@DisplayName("the sink reports why a file was refused")
	void sinkReportsRefusals() {
		String[] reasons = new String[3];

		StrutAssetSync.rebuild(sink -> {
			reasons[0] = sink.add("mymod", "ok.png", new byte[5]);
			reasons[1] = sink.add("mymod", "evil.jar", new byte[5]);
			reasons[2] = sink.add("minecraft", "textures/x.png", new byte[5]);
		});

		assertNull(reasons[0]);
		assertNotNull(reasons[1]);
		assertNotNull(reasons[2]);
	}

	@Test
	@DisplayName("the limits set apply from the next rebuild")
	void limitsApply() {
		StrutAssetSync.setLimits(100, 1000);

		ServerAssetStore built = StrutAssetSync.rebuild(sink -> {
			sink.add("mymod", "small.png", new byte[100]);
			sink.add("mymod", "big.png", new byte[101]);
		});

		assertEquals(1, built.manifest().entries().size());
		assertEquals(100, StrutAssetSync.maxFileBytes());
	}

	@Test
	@DisplayName("required is off until a mod turns it on, and the counters add up")
	void requiredAndCounters() {
		assertFalse(StrutAssetSync.isRequired());

		StrutAssetSync.setRequired(true);

		assertTrue(StrutAssetSync.isRequired());

		long started = StrutAssetSync.syncsStarted();
		long loaded = StrutAssetSync.syncsLoaded();
		long notLoaded = StrutAssetSync.syncsNotLoaded();
		long bytes = StrutAssetSync.bytesSent();

		StrutAssetSync.recordStarted();
		StrutAssetSync.recordFinished(SyncResult.LOADED, 100);
		StrutAssetSync.recordFinished(SyncResult.DECLINED, 5);

		assertEquals(started + 1, StrutAssetSync.syncsStarted());
		assertEquals(loaded + 1, StrutAssetSync.syncsLoaded());
		assertEquals(notLoaded + 1, StrutAssetSync.syncsNotLoaded());
		assertEquals(bytes + 105, StrutAssetSync.bytesSent());
	}
}
