package com.sandymandy.strut.assetsync;

import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.Chunk;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage.ManifestPage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import com.sandymandy.strut.assetsync.client.AssetCache;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.SyncLimits;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.server.ServerAssetSession;

/**
 * A server session and a client session joined by in-memory queues, driven one tick at a time
 * <p>
 * Everything a real connection does that matters to the protocol can be done here to the messages in flight: hold them,
 * tamper with them, or swap what the server reads. The sessions are the real classes; only the wire is missing
 */
final class AssetSyncLink {
	/** The server's files: path (as in the manifest) to bytes */
	final Map<String, byte[]> files = new LinkedHashMap<>();
	final AssetCache cache;
	final List<AssetSyncMessage> toClient = new ArrayList<>();
	final List<AssetSyncMessage> toServer = new ArrayList<>();
	final List<AssetSyncMessage> everSentToClient = new ArrayList<>();
	final List<AssetSyncMessage> everSentToServer = new ArrayList<>();

	AssetManifest manifest;
	ServerAssetSession server;
	ClientAssetSession client;

	/** Applied to every server-to-client message; return null to drop it */
	UnaryOperator<AssetSyncMessage> serverToClientFilter = message -> message;
	/** Applied to every client-to-server message; return null to drop it */
	UnaryOperator<AssetSyncMessage> clientToServerFilter = message -> message;
	long tick;

	AssetSyncLink(Path cacheDir) {
		this.cache = new AssetCache(cacheDir);
	}

	static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	static byte[] filled(int size, int seed) {
		byte[] data = new byte[size];

		for (int i = 0; i < size; i++)
			data[i] = (byte)(i * 31 + seed);

		return data;
	}

	AssetSyncLink file(String path, byte[] data) {
		this.files.put(path, data);

		return this;
	}

	/**
	 * Builds both sessions. Call after adding files
	 */
	AssetSyncLink open(ServerAssetSession.Config serverConfig, ClientAssetSession.Config clientConfig) {
		AssetManifest.Builder builder = new AssetManifest.Builder(SyncLimits.DEFAULT);

		this.files.forEach((path, data) -> builder.add(path, AssetManifest.sha256Hex(data), data.length));

		this.manifest = builder.build();

		Map<String, byte[]> byHash = new LinkedHashMap<>();

		this.files.values().forEach(data -> byHash.put(AssetManifest.sha256Hex(data), data));

		this.server = new ServerAssetSession(this.manifest,
				(entry, offset, length) -> Arrays.copyOfRange(byHash.get(entry.sha256()), (int)offset, (int)offset + length),
				serverConfig, this::sendToClient);
		this.client = new ClientAssetSession(this.cache, clientConfig, this.toServer::add);

		this.server.start(this.tick);

		return this;
	}

	AssetSyncLink open() {
		return open(ServerAssetSession.Config.DEFAULT, ClientAssetSession.Config.DEFAULT);
	}

	private void sendToClient(AssetSyncMessage message) {
		AssetSyncMessage filtered = this.serverToClientFilter.apply(message);

		if (filtered != null) {
			this.toClient.add(filtered);
			this.everSentToClient.add(filtered);
		}
	}

	/**
	 * One tick: deliver everything queued each way, then tick the server
	 */
	void step() {
		this.tick++;

		List<AssetSyncMessage> forClient = new ArrayList<>(this.toClient);

		this.toClient.clear();
		forClient.forEach(this.client::receive);

		List<AssetSyncMessage> forServer = new ArrayList<>(this.toServer);

		this.toServer.clear();
		this.everSentToServer.addAll(forServer);
		for (AssetSyncMessage message : forServer) {
			AssetSyncMessage filtered = this.clientToServerFilter.apply(message);

			if (filtered != null)
				this.server.receive(filtered, this.tick);
		}
		this.server.tick(this.tick);
	}

	/**
	 * Runs until the client is ready or either side finishes, at most {@code maxTicks} ticks
	 */
	int runUntilSettled(int maxTicks) {
		for (int i = 0; i < maxTicks; i++) {
			step();

			if (this.client.isReady() || this.client.state() == ClientAssetSession.State.FINISHED || this.server.isFinished())
				return i + 1;
		}

		return maxTicks;
	}

	/**
	 * The full happy path: transfer, then the client completes as loaded, then the server sees it
	 */
	void runToLoaded() {
		runUntilSettled(100_000);
		this.client.complete(SyncResult.LOADED);
		step();
		step();
	}

	long chunkBytesSent() {
		return this.everSentToClient.stream().filter(Chunk.class::isInstance).mapToLong(m -> ((Chunk)m).data().length).sum();
	}

	long chunkCount() {
		return this.everSentToClient.stream().filter(Chunk.class::isInstance).count();
	}

	long manifestPageCount() {
		return this.everSentToClient.stream().filter(ManifestPage.class::isInstance).count();
	}
}
