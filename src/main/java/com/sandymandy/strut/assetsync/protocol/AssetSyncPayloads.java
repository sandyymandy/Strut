package com.sandymandy.strut.assetsync.protocol;

import com.sandymandy.strut.StrutConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * The two configuration-phase payloads asset sync rides on: one per direction, each carrying an
 * {@link AssetSyncMessage} in the layout {@link AssetSyncWire} defines
 * <p>
 * Configuration phase, not play: this all happens while the player is still joining, before they have a world
 */
public final class AssetSyncPayloads {
	private AssetSyncPayloads() {}

	/**
	 * Server to client: a manifest page or a chunk
	 */
	public record ToClient(AssetSyncMessage message) implements CustomPayload {
		public static final Id<ToClient> TYPE = new Id<>(StrutConstants.id("asset_sync_to_client"));
		public static final PacketCodec<PacketByteBuf, ToClient> CODEC = PacketCodec.of(
				(payload, buf) -> AssetSyncWire.writeServerToClient(buf, payload.message()),
				buf -> new ToClient(AssetSyncWire.readServerToClient(buf)));

		@Override
		public Id<? extends CustomPayload> getId() {
			return TYPE;
		}
	}

	/**
	 * Client to server: a want page, an ack, or the final verdict
	 */
	public record ToServer(AssetSyncMessage message) implements CustomPayload {
		public static final Id<ToServer> TYPE = new Id<>(StrutConstants.id("asset_sync_to_server"));
		public static final PacketCodec<PacketByteBuf, ToServer> CODEC = PacketCodec.of(
				(payload, buf) -> AssetSyncWire.writeClientToServer(buf, payload.message()),
				buf -> new ToServer(AssetSyncWire.readClientToServer(buf)));

		@Override
		public Id<? extends CustomPayload> getId() {
			return TYPE;
		}
	}
}
