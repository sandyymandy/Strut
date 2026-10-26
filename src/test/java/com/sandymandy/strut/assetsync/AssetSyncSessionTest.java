package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Ack;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Done;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.WantPage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;

/**
 * The two sessions, real classes end to end over an in-memory wire
 */
class AssetSyncSessionTest {
	@TempDir
	Path dir;

	private static final ServerAssetSession.Config SMALL = new ServerAssetSession.Config(100, 300, 4, 900 * 1024, 100, 500);

	private AssetSyncLink link(String name) {
		return new AssetSyncLink(this.dir.resolve(name));
	}

	private byte[] cached(AssetSyncLink link, byte[] data) throws IOException {
		return Files.readAllBytes(link.cache.pathOf(AssetManifest.sha256Hex(data)));
	}

	// ---------------------------------------------------------------------------------------------
	// The happy path
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a first join transfers every file, verifies it, and both sides end LOADED")
	void firstJoin() throws IOException {
		byte[] model = AssetSyncLink.filled(1_000_000, 1);
		byte[] texture = AssetSyncLink.filled(70_000, 2);
		byte[] sounds = AssetSyncLink.bytes("{}");
		AssetSyncLink link = link("a").file("mymod/geo/eve.strutmodel", model).file("mymod/textures/eve.png", texture).file("mymod/sounds.json", sounds).open();

		link.runToLoaded();

		assertEquals(SyncResult.LOADED, link.client.result().orElseThrow());
		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertArrayEquals(model, cached(link, model));
		assertArrayEquals(texture, cached(link, texture));
		assertArrayEquals(sounds, cached(link, sounds));
		assertEquals(3, link.client.filesDownloaded());
		assertEquals(model.length + texture.length + sounds.length, link.client.bytesReceived());
		assertEquals(link.manifest.hash(), link.client.manifest().orElseThrow().hash());
	}

	@Test
	@DisplayName("no chunk exceeds the configured size, and a 1 MB file arrives as several")
	void chunking() {
		AssetSyncLink link = link("a").file("mymod/a.strutmodel", AssetSyncLink.filled(1_000_000, 1)).open();

		link.runToLoaded();

		assertEquals(4, link.chunkCount(), "1,000,000 bytes in 256 KiB chunks");
		assertTrue(link.everSentToClient.stream().filter(Chunk.class::isInstance).allMatch(m -> ((Chunk)m).data().length <= ServerAssetSession.Config.DEFAULT.chunkBytes()));
		assertEquals(1_000_000, link.chunkBytesSent());
	}

	@Test
	@DisplayName("a rejoin with a warm cache transfers nothing and is ready at once")
	void warmCache() {
		AssetSyncLink first = link("shared").file("mymod/a.png", AssetSyncLink.filled(5000, 1)).file("mymod/b.png", AssetSyncLink.filled(5000, 2)).open();

		first.runToLoaded();

		AssetSyncLink second = link("shared").file("mymod/a.png", AssetSyncLink.filled(5000, 1)).file("mymod/b.png", AssetSyncLink.filled(5000, 2)).open();

		second.runToLoaded();

		assertEquals(0, second.chunkCount());
		assertEquals(0, second.client.filesDownloaded());
		assertEquals(SyncResult.LOADED, second.server.result().orElseThrow());
	}

	@Test
	@DisplayName("after one file changes, a rejoin fetches only that file")
	void onlyTheChangedFile() throws IOException {
		byte[] unchanged = AssetSyncLink.filled(5000, 1);
		byte[] edited = AssetSyncLink.filled(5000, 2);
		byte[] editedAgain = AssetSyncLink.filled(5000, 3);

		link("shared").file("mymod/a.png", unchanged).file("mymod/b.png", edited).open().runToLoaded();

		AssetSyncLink second = link("shared").file("mymod/a.png", unchanged).file("mymod/b.png", editedAgain).open();

		second.runToLoaded();

		assertEquals(1, second.client.filesDownloaded());
		assertEquals(5000, second.chunkBytesSent());
		assertArrayEquals(editedAgain, cached(second, editedAgain));
	}

	@Test
	@DisplayName("two paths with identical content are transferred once")
	void sharedContentTransfersOnce() {
		byte[] same = AssetSyncLink.filled(3000, 4);
		AssetSyncLink link = link("a").file("mymod/one.png", same).file("mymod/two.png", same).file("mymod/three.png", AssetSyncLink.filled(10, 5)).open();

		link.runToLoaded();

		assertEquals(3010, link.chunkBytesSent());
		assertEquals(2, link.client.filesDownloaded());
	}

	@Test
	@DisplayName("an empty manifest completes immediately with an empty want")
	void emptyManifest() {
		AssetSyncLink link = link("a").open();

		link.runToLoaded();

		assertEquals(0, link.chunkCount());
		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertTrue(link.client.manifest().orElseThrow().isEmpty());
	}

	@Test
	@DisplayName("zero-length files transfer as one empty chunk each")
	void zeroLengthFiles() {
		AssetSyncLink link = link("a").file("mymod/empty.json", new byte[0]).file("mymod/after.png", AssetSyncLink.filled(10, 1)).open();

		link.runToLoaded();

		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertTrue(link.cache.has(AssetManifest.sha256Hex(new byte[0])));
	}

	@Test
	@DisplayName("a manifest and a want list too big for one page are paged and still complete")
	void paging() {
		AssetSyncLink link = link("a");

		for (int i = 0; i < 60; i++)
			link.file(String.format("mymod/file%03d.png", i), AssetSyncLink.filled(50 + i, i));

		ServerAssetSession.Config server = new ServerAssetSession.Config(100, 1000, 8, 1500, 100, 500);
		ClientAssetSession.Config client = new ClientAssetSession.Config(true, 1_000_000, SyncLimits.DEFAULT, 7);

		link.open(server, client).runToLoaded();

		assertTrue(link.manifestPageCount() > 3);
		assertEquals(60, link.client.filesDownloaded());
		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertTrue(link.everSentToServer.stream().filter(WantPage.class::isInstance).count() > 3);
	}

	@Test
	@DisplayName("a want page never exceeds the wire's hash cap, however large a page the config asks for")
	void wantPageIsClampedToTheWireLimit() {
		AssetSyncLink link = link("a");

		for (int i = 0; i < 2500; i++)
			link.file("mymod/f" + i + ".png", new byte[] {(byte)i, (byte)(i >> 8), 1});

		link.open(ServerAssetSession.Config.DEFAULT, new ClientAssetSession.Config(true, 1L << 40, SyncLimits.DEFAULT, 100_000)).runToLoaded();

		List<WantPage> wants = link.everSentToServer.stream().filter(WantPage.class::isInstance).map(WantPage.class::cast).toList();

		assertTrue(wants.size() >= 3, "2,500 hashes need at least three pages of 1,000");
		assertTrue(wants.stream().allMatch(w -> w.hashes().size() <= AssetSyncMessage.MAX_WANT_HASHES));
		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
	}

	// ---------------------------------------------------------------------------------------------
	// Flow control
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the server never has more than the window unacknowledged, and resumes when acked")
	void flowControl() {
		List<AssetSyncMessage> sent = new ArrayList<>();
		byte[] data = AssetSyncLink.filled(10_000, 1);
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), data.length)), SyncLimits.DEFAULT);
		ServerAssetSession server = new ServerAssetSession(manifest, (entry, offset, length) -> Arrays.copyOfRange(data, (int)offset, (int)offset + length), SMALL, sent::add);

		server.start(0);
		server.receive(new WantPage(true, List.of(manifest.entries().get(0).sha256())), 0);

		for (int tick = 1; tick < 20; tick++)
			server.tick(tick);

		assertTrue(server.bytesSent() <= 300, "window is 300 bytes, sent " + server.bytesSent());
		assertEquals(300, server.bytesSent(), "and it fills the window");

		server.receive(new Ack(200), 20);
		server.tick(21);

		assertEquals(500, server.bytesSent(), "an ack of 200 lets 200 more out");
	}

	@Test
	@DisplayName("a window smaller than one chunk still makes progress")
	void windowSmallerThanChunk() {
		AssetSyncLink link = link("a").file("mymod/a.png", AssetSyncLink.filled(1000, 1)).open(new ServerAssetSession.Config(100, 10, 4, 1000, 100, 500), ClientAssetSession.Config.DEFAULT);

		link.runToLoaded();

		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
	}

	@Test
	@DisplayName("the per-tick cap bounds how many chunks one tick sends")
	void perTickCap() {
		List<AssetSyncMessage> sent = new ArrayList<>();
		byte[] data = AssetSyncLink.filled(100_000, 1);
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), data.length)), SyncLimits.DEFAULT);
		ServerAssetSession server = new ServerAssetSession(manifest, (entry, offset, length) -> Arrays.copyOfRange(data, (int)offset, (int)offset + length),
				new ServerAssetSession.Config(100, 1_000_000, 3, 1000, 100, 500), sent::add);

		server.start(0);
		sent.clear();
		server.receive(new WantPage(true, List.of(manifest.entries().get(0).sha256())), 0);
		server.tick(1);

		assertEquals(3, sent.size());
	}

	// ---------------------------------------------------------------------------------------------
	// The client's verdicts
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a player with asset sync off is told nothing more and the server sees DECLINED")
	void declined() {
		AssetSyncLink link = link("a").file("mymod/a.png", AssetSyncLink.filled(5000, 1))
				.open(ServerAssetSession.Config.DEFAULT, new ClientAssetSession.Config(false, 1_000_000, SyncLimits.DEFAULT, 800));

		link.runUntilSettled(50);
		link.step();

		assertEquals(SyncResult.DECLINED, link.server.result().orElseThrow());
		assertEquals(0, link.chunkCount());
		assertEquals(0, link.cache.totalBytes());
	}

	@Test
	@DisplayName("over the download cap is TOO_LARGE, but files already cached do not count against it")
	void tooLargeCountsOnlyWhatIsMissing() {
		byte[] big = AssetSyncLink.filled(5000, 1);
		byte[] small = AssetSyncLink.filled(50, 2);
		ClientAssetSession.Config cap = new ClientAssetSession.Config(true, 1000, SyncLimits.DEFAULT, 800);

		AssetSyncLink over = link("a").file("mymod/big.png", big).file("mymod/small.png", small).open(ServerAssetSession.Config.DEFAULT, cap);

		over.runUntilSettled(50);
		over.step();

		assertEquals(SyncResult.TOO_LARGE, over.server.result().orElseThrow());
		assertEquals(0, over.chunkCount());

		AssetSyncLink warm = link("b");

		warm.cache.put(big);
		warm.file("mymod/big.png", big).file("mymod/small.png", small).open(ServerAssetSession.Config.DEFAULT, cap).runToLoaded();

		assertEquals(SyncResult.LOADED, warm.server.result().orElseThrow(), "the 5000-byte file was already cached");
		assertEquals(50, warm.chunkBytesSent());
	}

	@Test
	@DisplayName("a manifest with a hostile path is refused whole: FAILED, nothing fetched")
	void hostileManifest() {
		AssetSyncLink link = link("a").file("mymod/good.png", AssetSyncLink.filled(100, 1)).open();

		link.serverToClientFilter = message -> {
			if (message instanceof ManifestPage page) {
				List<ManifestEntry> entries = new ArrayList<>(page.entries());

				entries.add(new ManifestEntry("mymod/../../mods/evil.png", "a".repeat(64), 10));

				return new ManifestPage(page.version(), page.last(), entries);
			}

			return message;
		};
		link.open();
		link.step();
		link.step();

		assertEquals(SyncResult.FAILED, link.client.result().orElseThrow());
		assertEquals(SyncResult.FAILED, link.server.result().orElseThrow());
		assertEquals(0, link.cache.totalBytes());
	}

	@Test
	@DisplayName("a file over the per-file cap is refused")
	void oversizeFileInManifest() {
		AssetSyncLink link = link("a").open();
		ClientAssetSession client = new ClientAssetSession(link.cache, new ClientAssetSession.Config(true, 1L << 40, new SyncLimits(1000), 800), link.toServer::add);

		client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, true, List.of(new ManifestEntry("mymod/a.png", "a".repeat(64), 1001))));

		assertEquals(SyncResult.FAILED, client.result().orElseThrow());
	}

	@Test
	@DisplayName("a server speaking another protocol version ends the sync cleanly")
	void versionMismatch() {
		AssetSyncLink link = link("a").open();
		ClientAssetSession client = new ClientAssetSession(link.cache, ClientAssetSession.Config.DEFAULT, link.toServer::add);

		client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION + 1, true, List.of()));

		assertEquals(SyncResult.FAILED, client.result().orElseThrow());
		assertEquals(new Done(SyncResult.FAILED), link.toServer.get(link.toServer.size() - 1));
	}

	@Test
	@DisplayName("tampered chunk data fails the sync and caches nothing")
	void tamperedData() {
		AssetSyncLink link = link("a").file("mymod/a.png", AssetSyncLink.filled(500, 1)).open();

		link.serverToClientFilter = message -> message instanceof Chunk chunk
				? new Chunk(chunk.hash(), chunk.offset(), AssetSyncLink.filled(chunk.data().length, 99))
				: message;
		link.open();
		link.runUntilSettled(50);
		link.step();

		assertEquals(SyncResult.FAILED, link.client.result().orElseThrow());
		assertEquals(SyncResult.FAILED, link.server.result().orElseThrow());
		assertEquals(0, link.cache.totalBytes());
	}

	@Test
	@DisplayName("a chunk for a file nobody asked for, or out of order, fails the sync")
	void unrequestedAndOutOfOrderChunks() {
		byte[] wanted = AssetSyncLink.filled(500, 1);
		byte[] other = AssetSyncLink.filled(500, 2);
		AssetSyncLink link = link("a").file("mymod/a.png", wanted).open();

		link.client.receive(new Chunk(AssetManifest.sha256Hex(other), 0, other));
		assertEquals(SyncResult.FAILED, link.client.result().orElseThrow(), "before any manifest");

		AssetSyncLink second = link("b").file("mymod/a.png", wanted).file("mymod/b.png", other).open();

		second.step();

		// Manifest accepted; the client wants both. Deliver the second file's chunk first
		second.toClient.clear();
		second.client.receive(new Chunk(AssetManifest.sha256Hex(other), 0, other));
		second.client.receive(new Chunk(AssetManifest.sha256Hex(wanted), 0, wanted));

		assertEquals(SyncResult.FAILED, second.client.result().orElseThrow());
	}

	@Test
	@DisplayName("a dropped chunk is caught: the next chunk arrives at the wrong offset")
	void droppedChunk() {
		AssetSyncLink link = link("a").file("mymod/a.png", AssetSyncLink.filled(1000, 1)).open();
		int[] seen = {0};

		link.serverToClientFilter = message -> message instanceof Chunk && seen[0]++ == 2 ? null : message;
		link.open(SMALL, ClientAssetSession.Config.DEFAULT);
		link.runUntilSettled(100);
		link.step();

		assertEquals(SyncResult.FAILED, link.client.result().orElseThrow());
		assertEquals(0, link.cache.totalBytes());
	}

	@Test
	@DisplayName("completing before ready is refused, and a client can report its own failure after a good transfer")
	void completeRules() {
		AssetSyncLink link = link("a").file("mymod/a.png", AssetSyncLink.filled(500, 1)).open();

		assertThrows(IllegalStateException.class, () -> link.client.complete(SyncResult.LOADED));

		link.runUntilSettled(50);
		link.client.complete(SyncResult.FAILED);
		link.step();

		assertEquals(SyncResult.FAILED, link.server.result().orElseThrow(), "e.g. the reload failed");
		assertThrows(IllegalStateException.class, () -> link.client.complete(SyncResult.LOADED));
	}

	@Test
	@DisplayName("aborting mid-transfer discards the partial file and tells the server")
	void abortMidTransfer() throws IOException {
		AssetSyncLink link = link("a").file("mymod/a.png", AssetSyncLink.filled(1000, 1)).open(SMALL, ClientAssetSession.Config.DEFAULT);

		link.step();
		link.step();
		link.step();
		link.client.abort(SyncResult.FAILED);
		link.step();

		assertEquals(SyncResult.FAILED, link.server.result().orElseThrow());
		assertEquals(0, link.cache.totalBytes());

		Path tmp = this.dir.resolve("a/tmp");

		try (var files = Files.isDirectory(tmp) ? Files.list(tmp) : Stream.<Path>empty()) {
			assertEquals(0, files.count());
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The server distrusts the client
	// ---------------------------------------------------------------------------------------------

	private ServerAssetSession serverWith(byte[] data, List<AssetSyncMessage> sent) {
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), data.length)), SyncLimits.DEFAULT);
		ServerAssetSession server = new ServerAssetSession(manifest, (entry, offset, length) -> Arrays.copyOfRange(data, (int)offset, (int)offset + length), SMALL, sent::add);

		server.start(0);

		return server;
	}

	@Test
	@DisplayName("wanting a hash the server never offered is a protocol error")
	void unknownHash() {
		ServerAssetSession server = serverWith(AssetSyncLink.filled(100, 1), new ArrayList<>());

		server.receive(new WantPage(true, List.of("f".repeat(64))), 1);

		assertEquals(SyncResult.PROTOCOL_ERROR, server.result().orElseThrow());
	}

	@Test
	@DisplayName("an ack for bytes never sent, or one that goes backwards, is a protocol error")
	void badAcks() {
		byte[] data = AssetSyncLink.filled(1000, 1);
		ServerAssetSession ahead = serverWith(data, new ArrayList<>());

		ahead.receive(new WantPage(true, List.of(AssetManifest.sha256Hex(data))), 1);
		ahead.tick(2);
		ahead.receive(new Ack(ahead.bytesSent() + 1), 3);

		assertEquals(SyncResult.PROTOCOL_ERROR, ahead.result().orElseThrow());

		ServerAssetSession backwards = serverWith(data, new ArrayList<>());

		backwards.receive(new WantPage(true, List.of(AssetManifest.sha256Hex(data))), 1);
		backwards.tick(2);
		backwards.receive(new Ack(100), 3);
		backwards.receive(new Ack(50), 4);

		assertEquals(SyncResult.PROTOCOL_ERROR, backwards.result().orElseThrow());
	}

	@Test
	@DisplayName("a message in the wrong state is a protocol error, and a finished session ignores everything")
	void wrongState() {
		ServerAssetSession early = serverWith(AssetSyncLink.filled(100, 1), new ArrayList<>());

		early.receive(new Ack(0), 1);

		assertEquals(SyncResult.PROTOCOL_ERROR, early.result().orElseThrow());

		early.receive(new Done(SyncResult.LOADED), 2);
		early.tick(3);

		assertEquals(SyncResult.PROTOCOL_ERROR, early.result().orElseThrow(), "the first verdict stands");

		ServerAssetSession twice = serverWith(AssetSyncLink.filled(100, 1), new ArrayList<>());

		twice.receive(new WantPage(true, List.of()), 1);
		twice.receive(new WantPage(true, List.of()), 2);

		assertEquals(SyncResult.PROTOCOL_ERROR, twice.result().orElseThrow(), "a second want after the first completed");
	}

	@Test
	@DisplayName("a client may only ever tell the server a result it is allowed to send")
	void serverAcceptsClientVerdicts() {
		for (SyncResult verdict : List.of(SyncResult.LOADED, SyncResult.DECLINED, SyncResult.TOO_LARGE, SyncResult.FAILED)) {
			ServerAssetSession server = serverWith(AssetSyncLink.filled(100, 1), new ArrayList<>());

			server.receive(new Done(verdict), 1);

			assertEquals(verdict, server.result().orElseThrow());
		}
	}

	@Test
	@DisplayName("a file the server cannot read fails the sync instead of hanging it")
	void readerFailure() {
		byte[] data = AssetSyncLink.filled(100, 1);
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), 100)), SyncLimits.DEFAULT);
		ServerAssetSession failing = new ServerAssetSession(manifest, (entry, offset, length) -> {
			throw new IOException("disk gone");
		}, SMALL, message -> {});
		ServerAssetSession shortRead = new ServerAssetSession(manifest, (entry, offset, length) -> new byte[length - 1], SMALL, message -> {});

		for (ServerAssetSession server : List.of(failing, shortRead)) {
			server.start(0);
			server.receive(new WantPage(true, List.of(manifest.entries().get(0).sha256())), 0);
			server.tick(1);

			assertEquals(SyncResult.FAILED, server.result().orElseThrow());
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Timeouts
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a client that never answers the manifest times out")
	void noWant() {
		ServerAssetSession server = serverWith(AssetSyncLink.filled(100, 1), new ArrayList<>());

		server.tick(100);
		assertTrue(server.result().isEmpty(), "at the limit, not past it");

		server.tick(101);
		assertEquals(SyncResult.TIMED_OUT, server.result().orElseThrow());
	}

	@Test
	@DisplayName("a client that stops acking times out, but one that keeps acking never does however long the transfer")
	void ackTimeout() {
		byte[] data = AssetSyncLink.filled(100_000, 1);
		ServerAssetSession silent = serverWith(data, new ArrayList<>());

		silent.receive(new WantPage(true, List.of(AssetManifest.sha256Hex(data))), 0);
		silent.tick(1);
		silent.tick(150);

		assertEquals(SyncResult.TIMED_OUT, silent.result().orElseThrow());

		ServerAssetSession live = serverWith(data, new ArrayList<>());

		live.receive(new WantPage(true, List.of(AssetManifest.sha256Hex(data))), 0);

		long acked = 0;

		for (long tick = 1; tick < 5000 && live.result().isEmpty() && live.bytesSent() < data.length; tick++) {
			live.tick(tick);
			acked = live.bytesSent();
			live.receive(new Ack(acked), tick);
		}

		assertTrue(live.result().isEmpty(), "still going after 1000 ticks with a 100-tick idle limit");
		assertEquals(data.length, live.bytesSent());
	}

	@Test
	@DisplayName("after the last byte, the client gets the longer load timeout to build its pack and reload")
	void loadTimeout() {
		byte[] data = AssetSyncLink.filled(100, 1);
		ServerAssetSession server = serverWith(data, new ArrayList<>());

		server.receive(new WantPage(true, List.of(AssetManifest.sha256Hex(data))), 0);
		server.tick(1);
		server.receive(new Ack(100), 1);

		server.tick(400);
		assertTrue(server.result().isEmpty(), "past the idle limit of 100 but inside the load limit of 500");

		server.tick(502);
		assertEquals(SyncResult.TIMED_OUT, server.result().orElseThrow());
	}

	// ---------------------------------------------------------------------------------------------
	// Soak
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("300 random file sets, cache states and configs: the client ends with exactly the server's files and only what was missing crossed the wire")
	void soak() throws IOException {
		for (long seed = 0; seed < 300; seed++) {
			Random random = new Random(seed);
			AssetSyncLink link = link("soak" + seed);
			int fileCount = random.nextInt(25);
			long missingBytes = 0;
			Set<String> counted = new HashSet<>();

			for (int i = 0; i < fileCount; i++) {
				byte[] data = AssetSyncLink.filled(random.nextInt(4) == 0 ? 0 : random.nextInt(3000), random.nextInt(6));
				String path = "ns" + random.nextInt(3) + "/f" + i + ".png";

				link.file(path, data);
			}

			for (byte[] data : link.files.values()) {
				String hash = AssetManifest.sha256Hex(data);

				if (random.nextInt(3) == 0)
					link.cache.put(data);
			}

			for (byte[] data : link.files.values()) {
				String hash = AssetManifest.sha256Hex(data);

				if (!link.cache.has(hash) && counted.add(hash))
					missingBytes += data.length;
			}

			ServerAssetSession.Config server = new ServerAssetSession.Config(1 + random.nextInt(400), 1 + random.nextInt(2000), 1 + random.nextInt(6), 200 + random.nextInt(2000), 1000, 1000);
			ClientAssetSession.Config client = new ClientAssetSession.Config(true, 1L << 40, SyncLimits.DEFAULT, 1 + random.nextInt(10));

			link.open(server, client).runToLoaded();

			assertEquals(SyncResult.LOADED, link.server.result().orElse(null), "seed " + seed);
			assertEquals(missingBytes, link.chunkBytesSent(), "seed " + seed + ": only missing content crosses the wire");

			for (byte[] data : link.files.values())
				assertArrayEquals(data, cached(link, data), "seed " + seed);
		}
	}
}
