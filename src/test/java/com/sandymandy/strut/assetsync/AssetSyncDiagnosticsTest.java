package com.sandymandy.strut.assetsync;

import com.google.gson.JsonParser;
import com.sandymandy.strut.assetsync.AssetSyncReport.ActiveSync;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.client.AssetSyncClientConfig;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.PngGuard;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;
import com.sandymandy.strut.assetsync.server.ServerAssetStore;

/**
 * What /strut assets prints, the sync's progress figures, and the built-in demo assets
 */
class AssetSyncDiagnosticsTest {
	@TempDir
	Path dir;

	@AfterEach
	void reset() {
		StrutAssetSync.setRequired(false);
	}

	private static ServerAssetStore store(Object... pathsAndBytes) {
		ServerAssetStore.Builder builder = ServerAssetStore.builder(1_000_000, 10_000_000);

		for (int i = 0; i < pathsAndBytes.length; i += 2)
			builder.add((String)pathsAndBytes[i], (byte[])pathsAndBytes[i + 1]);

		return builder.build();
	}

	// ---------------------------------------------------------------------------------------------
	// Report
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("byte counts are written in the largest sensible unit")
	void formatBytes() {
		assertEquals("0 B", AssetSyncReport.formatBytes(0));
		assertEquals("1023 B", AssetSyncReport.formatBytes(1023));
		assertEquals("1.0 KiB", AssetSyncReport.formatBytes(1024));
		assertEquals("1.5 KiB", AssetSyncReport.formatBytes(1536));
		assertEquals("2.0 MiB", AssetSyncReport.formatBytes(2L * 1024 * 1024));
		assertEquals("3.00 GiB", AssetSyncReport.formatBytes(3L * 1024 * 1024 * 1024));
	}

	@Test
	@DisplayName("an empty server says how to give it something to offer")
	void statusWhenEmpty() {
		List<String> lines = AssetSyncReport.status(ServerAssetStore.EMPTY, false, List.of());

		assertTrue(lines.get(0).contains("nothing to offer"));
		assertTrue(lines.get(0).contains("asset_sync"), "it names the folder to use");
		assertTrue(lines.contains("In progress: none"));
	}

	@Test
	@DisplayName("an offering server reports its files, size, memory, manifest and whether assets are required")
	void statusWhenOffering() {
		ServerAssetStore store = store("mymod/a.png", new byte[2048], "mymod/b.png", new byte[2048]);

		String optional = AssetSyncReport.status(store, false, List.of()).get(0);
		String required = AssetSyncReport.status(store, true, List.of()).get(0);

		assertTrue(optional.contains("offering 2 file(s)"));
		assertTrue(optional.contains("4.0 KiB"));
		assertTrue(optional.contains("2.0 KiB held in memory"), "identical content is held once");
		assertTrue(optional.contains(store.manifest().hash().substring(0, 8)));
		assertTrue(optional.endsWith("optional"));
		assertTrue(required.endsWith("REQUIRED"));
	}

	@Test
	@DisplayName("syncs in progress are listed per player with their progress, and a player name is made log-safe")
	void statusListsActiveSyncs() {
		List<String> lines = AssetSyncReport.status(store("mymod/a.png", new byte[10]), false, List.of(
				new ActiveSync("Alex", "sending", 1024 * 1024, 4L * 1024 * 1024),
				new ActiveSync("${jndi:x}", "waiting", 0, 0)));

		assertTrue(lines.contains("In progress: 2"));
		assertTrue(lines.contains("  Alex: sending 1.0 MiB / 4.0 MiB"));
		assertTrue(lines.stream().anyMatch(line -> line.startsWith("  ??jndi?x?: waiting")), "hostile text neutralised, no progress figure before a want");
		assertTrue(lines.stream().noneMatch(line -> line.contains("${")));
	}

	@Test
	@DisplayName("files skipped at the last reload are reported, five at most, with a count of the rest")
	void statusReportsSkipped() {
		Object[] input = new Object[16];

		for (int i = 0; i < 8; i++) {
			input[i * 2] = "mymod/bad" + i + ".class";
			input[i * 2 + 1] = new byte[1];
		}

		List<String> lines = AssetSyncReport.status(store(input), false, List.of());

		assertTrue(lines.contains("Skipped at the last reload: 8"));
		assertEquals(5, lines.stream().filter(line -> line.startsWith("  mymod/bad")).count());
		assertTrue(lines.stream().anyMatch(line -> line.contains("and 3 more")));
	}

	@Test
	@DisplayName("the file list is capped, shows path, size and a short hash, and says when it is empty")
	void fileList() {
		Object[] input = new Object[10];

		for (int i = 0; i < 5; i++) {
			input[i * 2] = "mymod/f" + i + ".png";
			input[i * 2 + 1] = new byte[100 + i];
		}

		ServerAssetStore store = store(input);
		List<String> all = AssetSyncReport.files(store, 40);
		List<String> capped = AssetSyncReport.files(store, 2);

		assertEquals(5, all.size());
		assertTrue(all.get(0).startsWith("mymod/f0.png  100 B  "));
		assertEquals(3, capped.size());
		assertEquals("... and 3 more", capped.get(2));
		assertEquals(List.of("No files offered."), AssetSyncReport.files(ServerAssetStore.EMPTY, 40));
	}

	// ---------------------------------------------------------------------------------------------
	// Progress figures the command reads
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("a session reports waiting, then sending with bytes sent out of bytes wanted, then loading, then finished")
	void sessionProgress() {
		byte[] a = AssetSyncLink.filled(500, 1);
		byte[] b = AssetSyncLink.filled(300, 2);
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a")).file("mymod/a.png", a).file("mymod/b.png", b);
		ServerAssetSession.Config config = new ServerAssetSession.Config(100, 200, 4, 900_000, 1000, 1000);

		link.open(config, ClientAssetSession.Config.DEFAULT);

		assertEquals("waiting", link.server.stateName());
		assertEquals(0, link.server.wantedBytes(), "no want yet");

		link.step();
		link.step();

		assertEquals("sending", link.server.stateName());
		assertEquals(800, link.server.wantedBytes());
		assertTrue(link.server.bytesSent() > 0 && link.server.bytesSent() <= 800);

		link.runUntilSettled(10_000);

		assertEquals("loading", link.server.stateName());
		assertEquals(800, link.server.bytesSent());

		link.client.complete(SyncResult.LOADED);
		link.step();
		link.step();

		assertEquals("finished", link.server.stateName());
	}

	@Test
	@DisplayName("wanted bytes count each distinct file once and only what the client asked for")
	void wantedBytesOnlyCountsWhatWasAsked() {
		byte[] cached = AssetSyncLink.filled(500, 1);
		byte[] fresh = AssetSyncLink.filled(300, 2);
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a")).file("mymod/a.png", cached).file("mymod/b.png", fresh);

		link.cache.put(cached);
		link.open();
		link.step();
		link.step();

		assertEquals(300, link.server.wantedBytes());
	}

	// ---------------------------------------------------------------------------------------------
	// Demo assets
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("the demo assets are a valid lang file and a real 16x16 PNG that passes the client's texture guard")
	void demoAssetsAreValid() throws IOException {
		List<String> added = new ArrayList<>();
		byte[][] captured = new byte[2][];

		AssetSyncDemo.contribute((namespace, path, bytes) -> {
			assertEquals(AssetSyncDemo.NAMESPACE, namespace);
			added.add(path);
			captured[added.size() - 1] = bytes;

			return null;
		});

		assertEquals(List.of("lang/en_us.json", AssetSyncDemo.TEXTURE), added);
		assertEquals(AssetSyncDemo.LANG_VALUE, JsonParser.parseString(new String(captured[0], StandardCharsets.UTF_8)).getAsJsonObject().get(AssetSyncDemo.LANG_KEY).getAsString());

		var image = ImageIO.read(new ByteArrayInputStream(captured[1]));

		assertEquals(16, image.getWidth());
		assertEquals(16, image.getHeight());

		Path file = this.dir.resolve("demo.png");

		Files.write(file, captured[1]);

		assertNull(PngGuard.problem(file, AssetSyncClientConfig.DEFAULT.maxTexturePixels()));
	}

	@Test
	@DisplayName("the demo assets are accepted by the store and survive a full sync unchanged")
	void demoSurvivesASync() throws IOException {
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a"));

		AssetSyncDemo.contribute((namespace, path, bytes) -> {
			link.file(namespace + "/" + path, bytes);

			return null;
		});

		link.open().runToLoaded();

		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertEquals(2, link.client.manifest().orElseThrow().entries().size());

		for (byte[] data : link.files.values())
			assertArrayEquals(data, Files.readAllBytes(link.cache.pathOf(AssetManifest.sha256Hex(data))));
	}

	@Test
	@DisplayName("the demo is off unless its system property is set")
	void demoIsOptIn() {
		assertFalse(AssetSyncDemo.isEnabled());

		System.setProperty(AssetSyncDemo.PROPERTY, "true");

		try {
			assertTrue(AssetSyncDemo.isEnabled());
		}
		finally {
			System.clearProperty(AssetSyncDemo.PROPERTY);
		}
	}
}
