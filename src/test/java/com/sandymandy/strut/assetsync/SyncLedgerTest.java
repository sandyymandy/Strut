package com.sandymandy.strut.assetsync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.SyncLedger;

/**
 * Who is sent fresh assets when a {@code /reload} changes them
 */
class SyncLedgerTest {
	@TempDir
	Path dir;

	private static final UUID PLAYER = new UUID(1, 1);
	private static final String OLD = "a".repeat(64);
	private static final String NEW = "b".repeat(64);
	private static final String EMPTY = AssetManifest.empty().hash();

	@Test
	@DisplayName("a player who accepted earlier assets is pushed the new ones, but not the ones they already have")
	void acceptedPlayerIsPushedChanges() {
		SyncLedger ledger = new SyncLedger();

		ledger.record(PLAYER, OLD, true);

		assertTrue(ledger.needsPush(PLAYER, NEW, false));
		assertFalse(ledger.needsPush(PLAYER, OLD, false), "unchanged: nothing to send");
	}

	@Test
	@DisplayName("a player who declined is never asked again, however many times the assets change")
	void declinedPlayerIsLeftAlone() {
		SyncLedger ledger = new SyncLedger();

		ledger.record(PLAYER, OLD, false);

		assertFalse(ledger.needsPush(PLAYER, NEW, false));
		assertFalse(ledger.needsPush(PLAYER, OLD, false));
		assertFalse(ledger.needsPush(PLAYER, EMPTY, true));
	}

	@Test
	@DisplayName("a player never asked is offered assets as soon as there are some, and left alone while there are none")
	void neverAskedPlayer() {
		SyncLedger ledger = new SyncLedger();

		assertTrue(ledger.needsPush(PLAYER, NEW, false));
		assertFalse(ledger.needsPush(PLAYER, EMPTY, true));
	}

	@Test
	@DisplayName("when a reload empties the server, a player holding assets is pushed the empty set so they drop them")
	void emptiedServerClearsAcceptedPlayers() {
		SyncLedger ledger = new SyncLedger();

		ledger.record(PLAYER, OLD, true);

		assertTrue(ledger.needsPush(PLAYER, EMPTY, true));

		ledger.record(PLAYER, EMPTY, true);

		assertFalse(ledger.needsPush(PLAYER, EMPTY, true), "and only once");
	}

	@Test
	@DisplayName("forgetting a player, as on disconnect, makes them new again; players are tracked independently")
	void forgetAndIndependence() {
		SyncLedger ledger = new SyncLedger();
		UUID other = new UUID(2, 2);

		ledger.record(PLAYER, OLD, true);
		ledger.record(other, OLD, false);

		assertEquals(2, ledger.size());
		assertTrue(ledger.needsPush(PLAYER, NEW, false));
		assertFalse(ledger.needsPush(other, NEW, false));

		ledger.forget(PLAYER);

		assertNull(ledger.lastHash(PLAYER));
		assertEquals(OLD, ledger.lastHash(other));
		assertEquals(1, ledger.size());
	}

	@Test
	@DisplayName("a later result replaces an earlier one: a decline can be undone by a later accepted sync")
	void laterResultWins() {
		SyncLedger ledger = new SyncLedger();

		ledger.record(PLAYER, OLD, false);
		ledger.record(PLAYER, NEW, true);

		assertFalse(ledger.needsPush(PLAYER, NEW, false));
		assertTrue(ledger.needsPush(PLAYER, OLD, false));
	}

	// ---------------------------------------------------------------------------------------------
	// The whole reload story, over the real sessions
	// ---------------------------------------------------------------------------------------------

	@Test
	@DisplayName("join, then a reload that edits one file: the push fetches only that file and the ledger tracks the new hash")
	void reloadAfterJoin() {
		SyncLedger ledger = new SyncLedger();
		byte[] model = AssetSyncLink.filled(50_000, 1);
		byte[] texture = AssetSyncLink.filled(4_000, 2);
		byte[] editedTexture = AssetSyncLink.filled(4_000, 3);

		AssetSyncLink join = new AssetSyncLink(this.dir.resolve("cache")).file("mymod/model.strutmodel", model).file("mymod/tex.png", texture);

		join.open().runToLoaded();
		ledger.record(PLAYER, join.manifest.hash(), join.server.result().orElseThrow().isSuccess());

		AssetSyncLink push = new AssetSyncLink(this.dir.resolve("cache")).file("mymod/model.strutmodel", model).file("mymod/tex.png", editedTexture);

		push.open();

		assertTrue(ledger.needsPush(PLAYER, push.manifest.hash(), false), "the reload changed the manifest");

		push.runToLoaded();
		ledger.record(PLAYER, push.manifest.hash(), true);

		assertEquals(4_000, push.chunkBytesSent(), "only the edited texture crossed the wire");
		assertEquals(1, push.client.filesDownloaded());
		assertFalse(ledger.needsPush(PLAYER, push.manifest.hash(), false));
	}

	@Test
	@DisplayName("a reload that removes every asset is a valid sync of an empty manifest that the client can act on")
	void reloadThatEmptiesTheServer() {
		byte[] data = AssetSyncLink.filled(1000, 1);

		new AssetSyncLink(this.dir.resolve("cache")).file("mymod/a.png", data).open().runToLoaded();

		AssetSyncLink push = new AssetSyncLink(this.dir.resolve("cache")).open();

		push.runToLoaded();

		assertEquals(SyncResult.LOADED, push.server.result().orElseThrow());
		assertTrue(push.client.manifest().orElseThrow().isEmpty(), "the client sees an empty manifest and drops its assets");
		assertEquals(0, push.chunkCount());
	}
}
