package com.sandymandy.strut.assetsync.server;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.event.type.StrutEventTypes;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import com.sandymandy.strut.event.timeline.SyncedAssetTimelineSource;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationNetworkHandler;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import com.sandymandy.strut.assetsync.AssetSyncReport;
import com.sandymandy.strut.assetsync.StrutAssetSync;
import com.sandymandy.strut.assetsync.protocol.AssetPath;
import com.sandymandy.strut.assetsync.protocol.AssetSyncMessage;
import com.sandymandy.strut.assetsync.protocol.AssetSyncPayloads;
import com.sandymandy.strut.assetsync.protocol.SyncResult;

/**
 * Connects asset sync's server half to Fabric: reads the data packs, registers the payloads, syncs every joining player
 * in the configuration phase, re-syncs connected players when a {@code /reload} changes the assets, and drives every
 * {@link ServerAssetSession} from the server tick
 * <p>
 * <b>Threading.</b> Fabric delivers configuration payloads on the network thread (play payloads arrive on the server
 * thread). Nothing here lets a network thread touch a session: it only appends to the connection's inbox. All session work
 * (starting, receiving, ticking, sending, finishing) happens on the server thread in {@link #tick}, so a session is only
 * ever used from one thread
 * <p>
 * <b>At join</b> a configuration task holds the player until the client says it is done, as vanilla's own resource-pack task
 * does. Nothing is added for a server with nothing to sync, or for a client without Strut's channel (which cannot render
 * Strut content anyway) - unless the assets are required, which refuses that client
 * <p>
 * <b>After a reload</b> ({@link SyncLedger} decides who): each player who accepted the earlier assets and now differs is
 * sent the new manifest in the play phase, fetches only what changed, and reloads once. A player who declined is not asked
 * again. A player is also checked at join, which covers a reload that happened while they were still configuring
 */
public final class ServerAssetSyncFabric {
	private static final Logger LOGGER = LogManager.getLogger("Strut");
	private static final ServerPlayerConfigurationTask.Key KEY = new ServerPlayerConfigurationTask.Key(StrutConstants.MODID + ":asset_sync");

	/** Keyed by the configuration handler while joining, and by the player's UUID once in play */
	private static final Map<Object, Connection> CONNECTIONS = new ConcurrentHashMap<>();
	private static final SyncLedger LEDGER = new SyncLedger();

	private ServerAssetSyncFabric() {}

	/**
	 * Called once from {@link com.sandymandy.strut.Strut#onInitialize()}
	 */
	public static void init() {
		PayloadTypeRegistry.configurationS2C().register(AssetSyncPayloads.ToClient.TYPE, AssetSyncPayloads.ToClient.CODEC);
		PayloadTypeRegistry.configurationC2S().register(AssetSyncPayloads.ToServer.TYPE, AssetSyncPayloads.ToServer.CODEC);
		PayloadTypeRegistry.playS2C().register(AssetSyncPayloads.ToClient.TYPE, AssetSyncPayloads.ToClient.CODEC);
		PayloadTypeRegistry.playC2S().register(AssetSyncPayloads.ToServer.TYPE, AssetSyncPayloads.ToServer.CODEC);

		SyncedAssetTimelineSource timelines = new SyncedAssetTimelineSource(StrutEventTypes.registry());

		ClipTimelines.instance().addSource(timelines);

		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public Identifier getFabricId() {
				return Identifier.of(StrutConstants.MODID, "asset_sync");
			}

			@Override
			public void reload(ResourceManager manager) {
				timelines.reload(StrutAssetSync.rebuild(sink -> scan(manager, sink)));
			}
		});

		ServerConfigurationNetworking.registerGlobalReceiver(AssetSyncPayloads.ToServer.TYPE, (payload, context) -> {
			Connection connection = CONNECTIONS.get(context.networkHandler());

			// A message from a client that was never asked to sync is ignored, not an error worth a disconnect
			if (connection != null)
				connection.inbox.add(payload.message());
		});

		ServerPlayNetworking.registerGlobalReceiver(AssetSyncPayloads.ToServer.TYPE, (payload, context) -> {
			Connection connection = CONNECTIONS.get(context.player().getUuid());

			if (connection != null)
				connection.inbox.add(payload.message());
		});

		ServerConfigurationConnectionEvents.CONFIGURE.register(ServerAssetSyncFabric::onConfigure);
		ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> CONNECTIONS.remove(handler));

		// Reload while connected, and the join-time check that covers a reload that happened during someone's configuration
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
				pushIfNeeded(player);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> pushIfNeeded(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			CONNECTIONS.remove(handler.getPlayer().getUuid());
			LEDGER.forget(handler.getPlayer().getUuid());
		});

		ServerTickEvents.END_SERVER_TICK.register(ServerAssetSyncFabric::tick);
	}

	/**
	 * Adds every data-pack file under {@code data/<namespace>/asset_sync/} to the sink
	 */
	private static void scan(ResourceManager manager, StrutAssetSync.Sink sink) {
		String folder = AssetPath.DATA_FOLDER + "/";
		long limit = StrutAssetSync.maxFileBytes();

		for (Map.Entry<Identifier, Resource> entry : manager.findResources(AssetPath.DATA_FOLDER, id -> true).entrySet()) {
			Identifier id = entry.getKey();
			String rest = id.getPath().substring(folder.length());

			// Refuse by name before reading a single byte: a file of a forbidden type is never worth loading
			if (AssetPath.rejection(id.getNamespace() + "/" + rest) != null) {
				sink.add(id.getNamespace(), rest, new byte[0]);

				continue;
			}

			try (InputStream in = entry.getValue().getInputStream()) {
				// One byte over the limit is enough for the store to refuse it, and bounds what is read
				sink.add(id.getNamespace(), rest, in.readNBytes((int)Math.min(limit + 1, Integer.MAX_VALUE - 8)));
			}
			catch (IOException e) {
				LOGGER.warn("[Strut asset sync] could not read {}: {}", id, e.toString());
			}
		}
	}

	/**
	 * Every sync in progress, for {@code /strut assets status}
	 */
	public static List<AssetSyncReport.ActiveSync> activeSyncs() {
		List<AssetSyncReport.ActiveSync> syncs = new ArrayList<>();

		for (Connection connection : CONNECTIONS.values()) {
			ServerAssetSession session = connection.session;

			syncs.add(new AssetSyncReport.ActiveSync(connection.name, (connection.isPlay ? "reload: " : "") + session.stateName(), session.bytesSent(), session.wantedBytes()));
		}

		return syncs;
	}

	private static void onConfigure(ServerConfigurationNetworkHandler handler, MinecraftServer server) {
		ServerAssetStore store = StrutAssetSync.currentStore();

		if (store.isEmpty())
			return;

		if (!ServerConfigurationNetworking.canSend(handler, AssetSyncPayloads.ToClient.TYPE)) {
			if (StrutAssetSync.isRequired())
				handler.disconnect(Text.translatableWithFallback("strut.asset_sync.unsupported", "This server requires its custom assets, which needs the Strut mod."));

			return;
		}

		Connection connection = new Connection(handler, handler.getDebugProfile().getId(), handler.getDebugProfile().getName(), store, false,
				message -> ServerConfigurationNetworking.send(handler, new AssetSyncPayloads.ToClient(message)),
				handler::disconnect,
				() -> complete(handler));

		((FabricServerConfigurationNetworkHandler)handler).addTask(new Task(connection));
	}

	/**
	 * Sends this connected player the current assets if they accepted earlier ones and differ, or have never been asked and
	 * there is now something to send
	 */
	private static void pushIfNeeded(ServerPlayerEntity player) {
		UUID id = player.getUuid();
		ServerAssetStore store = StrutAssetSync.currentStore();

		if (CONNECTIONS.containsKey(id) || !LEDGER.needsPush(id, store.manifest().hash(), store.isEmpty()))
			return;

		if (!ServerPlayNetworking.canSend(player, AssetSyncPayloads.ToClient.TYPE))
			return;

		Connection connection = new Connection(id, id, player.getGameProfile().getName(), store, true,
				message -> ServerPlayNetworking.send(player, new AssetSyncPayloads.ToClient(message)),
				text -> player.networkHandler.disconnect(text),
				() -> {});

		CONNECTIONS.put(id, connection);
		LOGGER.info("[Strut asset sync] {}: the server's assets changed, sending the difference", AssetSyncReport.safeName(connection.name));
	}

	/**
	 * Advances every connection that is syncing. Server thread only
	 */
	private static void tick(MinecraftServer server) {
		long now = server.getTicks();

		for (Connection connection : CONNECTIONS.values()) {
			if (!connection.started) {
				connection.started = true;
				StrutAssetSync.recordStarted();
				connection.session.start(now);
			}

			AssetSyncMessage message;

			while ((message = connection.inbox.poll()) != null)
				connection.session.receive(message, now);

			connection.session.tick(now);

			if (connection.session.isFinished())
				finish(connection, server);
		}
	}

	private static void finish(Connection connection, MinecraftServer server) {
		SyncResult result = connection.session.result().orElseThrow();
		String hash = connection.store.manifest().hash();

		CONNECTIONS.remove(connection.key);
		StrutAssetSync.recordFinished(result, connection.session.bytesSent());
		LOGGER.info("[Strut asset sync] {}: {} ({} bytes sent)", AssetSyncReport.safeName(connection.name), result, connection.session.bytesSent());

		switch (result) {
			case LOADED -> {
				LEDGER.record(connection.player, hash, true);
				connection.proceed.run();
			}
			case DECLINED, TOO_LARGE, FAILED -> {
				// Remembered as a "no", so a later reload does not ask again
				LEDGER.record(connection.player, hash, false);

				if (StrutAssetSync.isRequired())
					connection.disconnect.accept(rejection(result));
				else
					connection.proceed.run();
			}
			// A client that went quiet or broke the protocol is not let in, or kept, either way
			default -> connection.disconnect.accept(Text.translatableWithFallback("strut.asset_sync.broken", "Asset sync with the server failed."));
		}

		// The assets changed again while this player was being synced: bring them up to date
		if (connection.isPlay && result == SyncResult.LOADED) {
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(connection.player);

			if (player != null)
				pushIfNeeded(player);
		}
	}

	private static void complete(ServerConfigurationNetworkHandler handler) {
		try {
			((FabricServerConfigurationNetworkHandler)handler).completeTask(KEY);
		}
		catch (IllegalStateException e) {
			// The connection ended between the verdict and here
			LOGGER.debug("[Strut asset sync] could not complete the task: {}", e.toString());
		}
	}

	private static Text rejection(SyncResult result) {
		return switch (result) {
			case DECLINED -> Text.translatableWithFallback("strut.asset_sync.declined",
					"This server requires its custom assets, but server assets are turned off in your Strut settings.");
			case TOO_LARGE -> Text.translatableWithFallback("strut.asset_sync.too_large",
					"This server's custom assets are larger than your Strut download limit.");
			default -> Text.translatableWithFallback("strut.asset_sync.failed", "The server's custom assets could not be loaded.");
		};
	}

	/**
	 * One player's sync: the session, and the inbox the network thread fills for the server thread to drain. The same
	 * class serves the join-time task and a mid-session push; only how it sends and how it ends differ
	 */
	private static final class Connection {
		final Object key;
		final UUID player;
		final String name;
		final ServerAssetStore store;
		final boolean isPlay;
		final ServerAssetSession session;
		final Consumer<Text> disconnect;
		final Runnable proceed;
		final Queue<AssetSyncMessage> inbox = new ConcurrentLinkedQueue<>();
		boolean started;

		Connection(Object key, UUID player, String name, ServerAssetStore store, boolean isPlay, Consumer<AssetSyncMessage> send,
				   Consumer<Text> disconnect, Runnable proceed) {
			this.key = key;
			this.player = player;
			this.name = name;
			this.store = store;
			this.isPlay = isPlay;
			this.disconnect = disconnect;
			this.proceed = proceed;
			this.session = new ServerAssetSession(store.manifest(), store.reader(), ServerAssetSession.Config.DEFAULT, send);
		}
	}

	/**
	 * Holds the player in configuration until the sync ends. It sends nothing itself: the session starts on the next server
	 * tick, on the server thread
	 */
	private record Task(Connection connection) implements ServerPlayerConfigurationTask {
		@Override
		public void sendPacket(Consumer<Packet<?>> sender) {
			CONNECTIONS.put(this.connection.key, this.connection);
		}

		@Override
		public Key getKey() {
			return KEY;
		}
	}
}
