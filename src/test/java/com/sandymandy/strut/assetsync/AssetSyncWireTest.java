package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Ack;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Done;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.WantPage;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.Random;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncWire;
import com.sandymandy.strut.assetsync.protocol.ManifestEntry;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;

/**
 * The byte layout of every message, and that a lying peer cannot make a decoder allocate or hang
 */
class AssetSyncWireTest {
	@TempDir
	Path dir;

	private static final String HASH = "ab".repeat(32);

	private static PacketByteBuf buf() {
		return new PacketByteBuf(Unpooled.buffer());
	}

	private static AssetSyncMessage viaServerWire(AssetSyncMessage message) {
		PacketByteBuf buf = buf();

		AssetSyncWire.writeServerToClient(buf, message);

		AssetSyncMessage read = AssetSyncWire.readServerToClient(buf);

		assertEquals(0, buf.readableBytes(), "the decoder must consume exactly what was written");

		return read;
	}

	private static AssetSyncMessage viaClientWire(AssetSyncMessage message) {
		PacketByteBuf buf = buf();

		AssetSyncWire.writeClientToServer(buf, message);

		AssetSyncMessage read = AssetSyncWire.readClientToServer(buf);

		assertEquals(0, buf.readableBytes());

		return read;
	}

	@Test
	@DisplayName("every server-to-client message survives the wire unchanged")
	void serverMessagesRoundTrip() {
		ManifestPage page = new ManifestPage(1, true, List.of(new ManifestEntry("mymod/geo/eve.strutmodel", HASH, 1_234_567), new ManifestEntry("mymod/sounds.json", "01".repeat(32), 0)));

		assertEquals(page, viaServerWire(page));
		assertEquals(new ManifestPage(1, false, List.of()), viaServerWire(new ManifestPage(1, false, List.of())));

		Chunk chunk = (Chunk)viaServerWire(new Chunk(HASH, 262_144, AssetSyncLink.filled(1000, 3)));

		assertEquals(HASH, chunk.hash());
		assertEquals(262_144, chunk.offset());
		assertArrayEquals(AssetSyncLink.filled(1000, 3), chunk.data());
		assertEquals(0, ((Chunk)viaServerWire(new Chunk(HASH, 0, new byte[0]))).data().length);
	}

	@Test
	@DisplayName("every client-to-server message survives the wire unchanged")
	void clientMessagesRoundTrip() {
		assertEquals(new WantPage(true, List.of(HASH, "cd".repeat(32))), viaClientWire(new WantPage(true, List.of(HASH, "cd".repeat(32)))));
		assertEquals(new WantPage(false, List.of()), viaClientWire(new WantPage(false, List.of())));
		assertEquals(new Ack(123_456_789_012L), viaClientWire(new Ack(123_456_789_012L)));

		for (SyncResult result : List.of(SyncResult.LOADED, SyncResult.DECLINED, SyncResult.TOO_LARGE, SyncResult.FAILED))
			assertEquals(new Done(result), viaClientWire(new Done(result)));
	}

	@Test
	@DisplayName("a sender cannot write a message the other direction owns, and a receiver cannot read one")
	void directionIsEnforced() {
		assertThrows(IllegalArgumentException.class, () -> AssetSyncWire.writeServerToClient(buf(), new Ack(1)));
		assertThrows(IllegalArgumentException.class, () -> AssetSyncWire.writeServerToClient(buf(), new WantPage(true, List.of())));
		assertThrows(IllegalArgumentException.class, () -> AssetSyncWire.writeClientToServer(buf(), new Chunk(HASH, 0, new byte[0])));
		assertThrows(IllegalArgumentException.class, () -> AssetSyncWire.writeClientToServer(buf(), new ManifestPage(1, true, List.of())));

		// A client claiming a server-only result is refused by the server's reader
		PacketByteBuf claim = buf();

		AssetSyncWire.writeClientToServer(claim, new Done(SyncResult.TIMED_OUT));
		assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(claim));

		// A hand-built client message fed to the server-side reader, and the reverse
		PacketByteBuf ack = buf();

		AssetSyncWire.writeClientToServer(ack, new Ack(5));
		assertThrows(DecoderException.class, () -> AssetSyncWire.readServerToClient(ack));

		PacketByteBuf chunk = buf();

		AssetSyncWire.writeServerToClient(chunk, new Chunk(HASH, 0, new byte[3]));
		assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(chunk));
	}

	@Test
	@DisplayName("an unknown tag is refused in both directions")
	void unknownTags() {
		for (int tag : new int[] {5, 99, 255}) {
			assertThrows(DecoderException.class, () -> AssetSyncWire.readServerToClient(buf().writeByte(tag)));
			assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(buf().writeByte(tag)));
		}
	}

	@Test
	@DisplayName("a manifest page claiming a huge count is refused before anything is allocated, whatever bytes follow")
	void hugeManifestCount() {
		for (int lie : new int[] {AssetSyncWire.MAX_PAGE_ENTRIES + 1, Integer.MAX_VALUE, 1_000_000}) {
			PacketByteBuf buf = buf();

			buf.writeByte(0).writeVarInt(1).writeBoolean(true).writeVarInt(lie);

			assertThrows(DecoderException.class, () -> AssetSyncWire.readServerToClient(buf), "count " + lie);
		}

		// Within the cap but far more than the remaining bytes could hold
		PacketByteBuf tooFew = buf();

		tooFew.writeByte(0).writeVarInt(1).writeBoolean(true).writeVarInt(19_000).writeBytes(new byte[100]);

		assertThrows(DecoderException.class, () -> AssetSyncWire.readServerToClient(tooFew));
	}

	@Test
	@DisplayName("a negative count is refused")
	void negativeCount() {
		PacketByteBuf buf = buf();

		buf.writeByte(0).writeVarInt(1).writeBoolean(true).writeVarInt(-1);

		assertThrows(DecoderException.class, () -> AssetSyncWire.readServerToClient(buf));
	}

	@Test
	@DisplayName("a want page over its cap, or too big to fit, is refused")
	void hugeWant() {
		PacketByteBuf over = buf();

		over.writeByte(1).writeBoolean(true).writeVarInt(AssetSyncMessage.MAX_WANT_HASHES + 1).writeBytes(new byte[(AssetSyncMessage.MAX_WANT_HASHES + 1) * 32]);

		assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(over));

		PacketByteBuf lie = buf();

		lie.writeByte(1).writeBoolean(true).writeVarInt(500).writeBytes(new byte[64]);

		assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(lie));

		// The largest legal page fits in the 32,767-byte limit a client-to-server payload has
		PacketByteBuf full = buf();

		AssetSyncWire.writeClientToServer(full, new WantPage(true, Arrays.asList(new String[AssetSyncMessage.MAX_WANT_HASHES]).stream().map(h -> HASH).toList()));

		assertTrue(full.readableBytes() < 32_767, "a full want page is " + full.readableBytes() + " bytes");
	}

	@Test
	@DisplayName("a chunk claiming more data than the cap is refused before the bytes are read")
	void oversizeChunk() {
		PacketByteBuf buf = buf();

		buf.writeByte(2).writeBytes(new byte[32]).writeVarLong(0).writeVarInt(ClientAssetSession.MAX_CHUNK_BYTES + 1);

		assertThrows(DecoderException.class, () -> AssetSyncWire.readServerToClient(buf));

		PacketByteBuf lie = buf();

		lie.writeByte(2).writeBytes(new byte[32]).writeVarLong(0).writeVarInt(100_000).writeBytes(new byte[10]);

		assertThrows(RuntimeException.class, () -> AssetSyncWire.readServerToClient(lie), "a length longer than the bytes present");
	}

	@Test
	@DisplayName("a path over the length cap, a negative ack, a truncated message and a bad result code are all refused")
	void malformed() {
		PacketByteBuf longPath = buf();

		longPath.writeByte(0).writeVarInt(1).writeBoolean(true).writeVarInt(1);
		longPath.writeVarInt(AssetPath.MAX_LENGTH * 4).writeBytes(new byte[AssetPath.MAX_LENGTH * 4]);

		assertThrows(RuntimeException.class, () -> AssetSyncWire.readServerToClient(longPath));

		PacketByteBuf negativeAck = buf();

		negativeAck.writeByte(3).writeVarLong(-1);

		assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(negativeAck));

		assertThrows(RuntimeException.class, () -> AssetSyncWire.readClientToServer(buf().writeByte(3)));
		assertThrows(RuntimeException.class, () -> AssetSyncWire.readServerToClient(buf()));

		for (int code : new int[] {4, 5, 200})
			assertThrows(DecoderException.class, () -> AssetSyncWire.readClientToServer(buf().writeByte(4).writeByte(code)), "result " + code);
	}

	@Test
	@DisplayName("random garbage never decodes into anything but a clean refusal")
	void fuzz() {
		Random random = new Random(7);

		for (int i = 0; i < 20_000; i++) {
			byte[] garbage = new byte[random.nextInt(80)];

			random.nextBytes(garbage);

			for (boolean toClient : new boolean[] {true, false}) {
				PacketByteBuf buf = buf();

				buf.writeBytes(garbage);

				try {
					if (toClient)
						AssetSyncWire.readServerToClient(buf);
					else
						AssetSyncWire.readClientToServer(buf);
				}
				catch (NegativeArraySizeException | OutOfMemoryError bad) {
					fail("a lying length reached an allocation: " + bad);
				}
				catch (RuntimeException expected) {
					// A clean refusal. Anything else (out of memory, negative array size, hang) fails the test
				}
			}
		}
	}

	@Test
	@DisplayName("a full sync over the real encoding, both ways, matches one over plain objects")
	void fullSyncOverTheWire() throws Exception {
		AssetSyncLink link = new AssetSyncLink(this.dir.resolve("a"))
				.file("mymod/geo/eve.strutmodel", AssetSyncLink.filled(700_000, 1))
				.file("mymod/textures/eve.png", AssetSyncLink.filled(20_000, 2))
				.file("mymod/sounds.json", AssetSyncLink.bytes("{}"))
				.file("mymod/empty.json", new byte[0]);

		link.serverToClientFilter = message -> viaServerWire(message);
		link.clientToServerFilter = message -> viaClientWire(message);
		link.open().runToLoaded();

		assertEquals(SyncResult.LOADED, link.server.result().orElseThrow());
		assertEquals(SyncResult.LOADED, link.client.result().orElseThrow());
		assertEquals(link.manifest.hash(), link.client.manifest().orElseThrow().hash());
		assertEquals(4, link.client.manifest().orElseThrow().entries().size());
	}

	@Test
	@DisplayName("the largest server message stays well under the 1 MiB payload limit")
	void serverMessagesFitTheLimit() {
		PacketByteBuf chunk = buf();

		AssetSyncWire.writeServerToClient(chunk, new Chunk(HASH, 0, new byte[ServerAssetSession.Config.DEFAULT.chunkBytes()]));

		assertTrue(chunk.readableBytes() < 1_048_576 / 2);

		// A manifest page at the server's byte budget, made of the longest allowed paths
		List<ManifestEntry> entries = new ArrayList<>();

		for (int i = 0; i < 4000; i++)
			entries.add(new ManifestEntry("ns/" + "a".repeat(AssetPath.MAX_LENGTH - 20) + i + ".png", HASH, i));

		AssetManifest manifest = AssetManifest.of(entries, SyncLimits.DEFAULT);

		for (List<ManifestEntry> page : manifest.pages(ServerAssetSession.Config.DEFAULT.pageBytes())) {
			PacketByteBuf buf = buf();

			AssetSyncWire.writeServerToClient(buf, new ManifestPage(1, true, page));

			assertTrue(buf.readableBytes() < 1_048_576, "a manifest page encoded to " + buf.readableBytes() + " bytes");
		}
	}
}
