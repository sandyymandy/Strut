package com.sandymandy.strut.assetsync.protocol;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Ack;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Done;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.WantPage;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;

/**
 * The byte layout of {@link AssetSyncMessage}s
 * <p>
 * Every read here is <b>bounded before it allocates</b>: counts are capped, strings and byte arrays carry a maximum
 * length, and hashes are fixed-width. A peer that lies about a length gets a {@link DecoderException} and the connection
 * is dropped by the game, never an out-of-memory error. Direction is checked too: a client cannot send a chunk, and a
 * server cannot send a want
 * <p>
 * Layout (all integers are the game's var-ints / var-longs):
 * <pre>
 * tag(byte)
 *   0 ManifestPage: version, last(bool), count, count x [path(string), sha256(32 bytes), size]
 *   1 WantPage:     last(bool), count, count x sha256(32 bytes)
 *   2 Chunk:        sha256(32 bytes), offset, data(byte array)
 *   3 Ack:          bytesReceived
 *   4 Done:         result(byte)
 * </pre>
 */
public final class AssetSyncWire {
	/** Most entries one manifest page may carry when decoding. The server's byte budget keeps real pages far smaller */
	public static final int MAX_PAGE_ENTRIES = 20_000;

	private static final int TAG_MANIFEST = 0;
	private static final int TAG_WANT = 1;
	private static final int TAG_CHUNK = 2;
	private static final int TAG_ACK = 3;
	private static final int TAG_DONE = 4;

	private AssetSyncWire() {}

	/**
	 * Writes a message a server may send
	 *
	 * @throws IllegalArgumentException for one only a client may send
	 */
	public static void writeServerToClient(PacketByteBuf buf, AssetSyncMessage message) {
		switch (message) {
			case ManifestPage page -> {
				buf.writeByte(TAG_MANIFEST);
				buf.writeVarInt(page.version());
				buf.writeBoolean(page.last());
				buf.writeVarInt(page.entries().size());

				for (ManifestEntry entry : page.entries()) {
					buf.writeString(entry.path(), AssetPath.MAX_LENGTH);
					writeHash(buf, entry.sha256());
					buf.writeVarLong(entry.size());
				}
			}
			case Chunk chunk -> {
				buf.writeByte(TAG_CHUNK);
				writeHash(buf, chunk.hash());
				buf.writeVarLong(chunk.offset());
				buf.writeByteArray(chunk.data());
			}
			default -> throw new IllegalArgumentException("A server does not send " + message.getClass().getSimpleName());
		}
	}

	/**
	 * Writes a message a client may send
	 *
	 * @throws IllegalArgumentException for one only a server may send
	 */
	public static void writeClientToServer(PacketByteBuf buf, AssetSyncMessage message) {
		switch (message) {
			case WantPage page -> {
				buf.writeByte(TAG_WANT);
				buf.writeBoolean(page.last());
				buf.writeVarInt(page.hashes().size());
				page.hashes().forEach(hash -> writeHash(buf, hash));
			}
			case Ack ack -> {
				buf.writeByte(TAG_ACK);
				buf.writeVarLong(ack.bytesReceived());
			}
			case Done done -> {
				buf.writeByte(TAG_DONE);
				buf.writeByte(done.result().ordinal());
			}
			default -> throw new IllegalArgumentException("A client does not send " + message.getClass().getSimpleName());
		}
	}

	/**
	 * Reads what a server sent
	 *
	 * @throws DecoderException for anything malformed, oversize, or not a server-to-client message
	 */
	public static AssetSyncMessage readServerToClient(PacketByteBuf buf) {
		int tag = buf.readUnsignedByte();

		return switch (tag) {
			case TAG_MANIFEST -> {
				int version = buf.readVarInt();
				boolean last = buf.readBoolean();
				int count = boundedCount(buf, MAX_PAGE_ENTRIES, 1 + 32 + 1);
				List<ManifestEntry> entries = new ArrayList<>();

				for (int i = 0; i < count; i++) {
					String path = buf.readString(AssetPath.MAX_LENGTH);
					String hash = readHash(buf);
					long size = buf.readVarLong();

					entries.add(new ManifestEntry(path, hash, size));
				}

				yield new ManifestPage(version, last, entries);
			}
			case TAG_CHUNK -> {
				String hash = readHash(buf);
				long offset = buf.readVarLong();
				byte[] data = buf.readByteArray(ClientAssetSession.MAX_CHUNK_BYTES);

				yield new Chunk(hash, offset, data);
			}
			default -> throw new DecoderException("Unexpected asset sync tag " + tag + " from the server");
		};
	}

	/**
	 * Reads what a client sent
	 *
	 * @throws DecoderException for anything malformed, oversize, or not a client-to-server message
	 */
	public static AssetSyncMessage readClientToServer(PacketByteBuf buf) {
		int tag = buf.readUnsignedByte();

		return switch (tag) {
			case TAG_WANT -> {
				boolean last = buf.readBoolean();
				int count = boundedCount(buf, AssetSyncMessage.MAX_WANT_HASHES, 32);
				List<String> hashes = new ArrayList<>();

				for (int i = 0; i < count; i++)
					hashes.add(readHash(buf));

				yield new WantPage(last, hashes);
			}
			case TAG_ACK -> {
				long received = buf.readVarLong();

				if (received < 0)
					throw new DecoderException("Negative ack");

				yield new Ack(received);
			}
			case TAG_DONE -> {
				try {
					yield new Done(SyncResult.fromWire(buf.readUnsignedByte()));
				}
				catch (IllegalArgumentException e) {
					throw new DecoderException(e.getMessage());
				}
			}
			default -> throw new DecoderException("Unexpected asset sync tag " + tag + " from a client");
		};
	}

	/**
	 * A count that is within its cap and could plausibly fit in the bytes that are actually there - checked before anything
	 * is allocated for it
	 */
	private static int boundedCount(PacketByteBuf buf, int max, int minBytesEach) {
		int count = buf.readVarInt();

		if (count < 0 || count > max)
			throw new DecoderException("Count " + count + " is over the limit of " + max);

		if ((long)count * minBytesEach > buf.readableBytes())
			throw new DecoderException("Count " + count + " cannot fit in the " + buf.readableBytes() + " bytes that remain");

		return count;
	}

	private static void writeHash(PacketByteBuf buf, String sha256) {
		buf.writeBytes(HexFormat.of().parseHex(sha256));
	}

	private static String readHash(PacketByteBuf buf) {
		byte[] raw = new byte[32];

		buf.readBytes(raw);

		return HexFormat.of().formatHex(raw);
	}
}
