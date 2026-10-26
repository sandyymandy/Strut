package com.sandymandy.strut.assetsync.client;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.assetsync.client.AssetCache;
import com.sandymandy.strut.assetsync.protocol.AssetManifest;
import com.sandymandy.strut.assetsync.client.AssetSyncClientConfig;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncPayloads;
import com.sandymandy.strut.assetsync.client.ClientAssetSession;
import com.sandymandy.strut.assetsync.protocol.SyncResult;
import com.sandymandy.strut.assetsync.client.SyncedAssetIndex;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/**
 * Connects asset sync's client half to Fabric: receives the server's messages during the configuration phase, feeds a
 * {@link ClientAssetSession}, and once every needed file is cached, activates the synced pack and reloads resources
 * <p>
 * Everything here runs on the client thread: the game applies configuration payloads there, and completion callbacks are
 * hopped back onto it, so no locking is needed. The cache writes and hashing are small (one chunk at a time)
 * <p>
 * <b>The reload is the point of the whole feature.</b> The server is holding the player in configuration until this
 * client says it is done, and this client says so only after the resources reload with the synced pack on top. So a
 * player never spawns before the server's models, sounds and textures exist. Leaving the server drops the pack and
 * reloads once more. (Keeping the pack across a rejoin of an identical server would save that reload; not done yet)
 */
public final class ClientAssetSyncFabric {
	private static final SyncedAssetPackProvider PROVIDER = SyncedAssetPackProvider.INSTANCE;

	private static AssetSyncClientConfig config = AssetSyncClientConfig.DEFAULT;
	private static AssetCache cache;
	private static ClientAssetSession session;

	private ClientAssetSyncFabric() {}

	/**
	 * Called once from {@link com.sandymandy.strut.StrutClient#onInitializeClient()}
	 */
	public static void init() {
		FabricLoader loader = FabricLoader.getInstance();

		config = AssetSyncClientConfig.load(loader.getConfigDir().resolve("strut-client.json"));
		cache = new AssetCache(loader.getGameDir().resolve("strut").resolve("asset-cache"));

		ClientConfigurationNetworking.registerGlobalReceiver(AssetSyncPayloads.ToClient.TYPE,
				(payload, context) -> onMessage(payload.message(), context.client(), false));

		// The same messages arrive in the play phase when the server's assets change while connected (a /reload)
		ClientPlayNetworking.registerGlobalReceiver(AssetSyncPayloads.ToClient.TYPE,
				(payload, context) -> onMessage(payload.message(), context.client(), true));

		// One sync per connection: a new connection starts clean, and a lost one discards what it had in flight
		ClientConfigurationConnectionEvents.INIT.register((handler, client) -> discardSession());
		ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect(client));
	}

	private static void onMessage(AssetSyncMessage message, MinecraftClient client, boolean play) {
		if (session == null || session.state() == ClientAssetSession.State.FINISHED) {
			// A sync begins with the manifest. Anything else means the server is confused, or this is a stray from a sync
			// that already ended: ignore it rather than answer. A finished session followed by a manifest is a new sync: the
			// server pushing changed assets after a reload
			if (!(message instanceof AssetSyncMessage.ManifestPage))
				return;

			session = new ClientAssetSession(cache, config.toSessionConfig(), play ? ClientAssetSyncFabric::sendPlay : ClientAssetSyncFabric::send);
		}

		session.receive(message);

		if (session.isReady())
			beginLoad(client, session);
	}

	private static void sendPlay(AssetSyncMessage message) {
		try {
			ClientPlayNetworking.send(new AssetSyncPayloads.ToServer(message));
		}
		catch (IllegalStateException e) {
			StrutConstants.LOGGER.debug("[Strut asset sync] could not send {}: {}", message.getClass().getSimpleName(), e.toString());
		}
	}

	private static void send(AssetSyncMessage message) {
		try {
			ClientConfigurationNetworking.send(new AssetSyncPayloads.ToServer(message));
		}
		catch (IllegalStateException e) {
			// The connection ended between the decision and the send
			StrutConstants.LOGGER.debug("[Strut asset sync] could not send {}: {}", message.getClass().getSimpleName(), e.toString());
		}
	}

	/**
	 * Every needed file is cached: build the pack, reload, and tell the server how it went
	 */
	private static void beginLoad(MinecraftClient client, ClientAssetSession ready) {
		AssetManifest manifest = ready.manifest().orElseThrow();

		if (manifest.isEmpty()) {
			// The server no longer has any assets (a reload emptied it). Drop the ones we hold, if any
			if (PROVIDER.active() == null) {
				ready.complete(SyncResult.LOADED);

				return;
			}

			PROVIDER.setActive(null, config.maxTexturePixels());
			client.reloadResources().whenComplete((ignored, error) -> client.execute(() -> onReloaded(client, ready, error)));

			return;
		}

		SyncedAssetIndex index;

		try {
			cache.prune(config.cacheLimitBytes(), manifest.distinctHashes());
			index = SyncedAssetIndex.build(manifest, cache);
		}
		catch (RuntimeException e) {
			StrutConstants.LOGGER.warn("[Strut asset sync] could not prepare the synced assets: {}", e.toString());
			ready.complete(SyncResult.FAILED);

			return;
		}

		SyncedAssetIndex current = PROVIDER.active();

		if (current != null && current.manifestHash().equals(index.manifestHash())) {
			// The very same assets are already loaded: nothing to reload
			StrutAssetSyncClient.recordSync(ready.filesDownloaded(), ready.bytesReceived(), cache.totalBytes());
			ready.complete(SyncResult.LOADED);
			StrutAssetSyncClient.fireReady();

			return;
		}

		PROVIDER.setActive(index, config.maxTexturePixels());
		StrutAssetSyncClient.recordSync(ready.filesDownloaded(), ready.bytesReceived(), cache.totalBytes());
		StrutConstants.LOGGER.info("[Strut asset sync] loading {} synced file(s) ({} downloaded)", index.fileCount(), ready.filesDownloaded());

		client.reloadResources().whenComplete((ignored, error) -> client.execute(() -> onReloaded(client, ready, error)));
	}

	private static void onReloaded(MinecraftClient client, ClientAssetSession finished, Throwable error) {
		if (finished.state() != ClientAssetSession.State.READY)
			return;

		if (error != null) {
			StrutConstants.LOGGER.warn("[Strut asset sync] the resource reload with synced assets failed: {}", error.toString());
			PROVIDER.setActive(null, config.maxTexturePixels());
			finished.complete(SyncResult.FAILED);
			client.reloadResources();

			return;
		}

		finished.complete(SyncResult.LOADED);
		StrutAssetSyncClient.fireReady();
	}

	private static void discardSession() {
		if (session != null) {
			session.discard();
			session = null;
		}
	}

	private static void onDisconnect(MinecraftClient client) {
		discardSession();

		if (PROVIDER.active() != null) {
			PROVIDER.setActive(null, config.maxTexturePixels());
			client.execute(client::reloadResources);
		}
	}
}
