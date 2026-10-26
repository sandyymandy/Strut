package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animation.attachment.Attachment;
import com.sandymandy.strut.animation.attachment.ServerAttachmentState;
import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.OverrideKey;
import com.sandymandy.strut.animation.override.ServerBoneOverrideState;
import com.sandymandy.strut.animatable.StrutEntity;
import com.sandymandy.strut.event.server.EventContext;
import com.sandymandy.strut.event.StrutEvents;
import com.sandymandy.strut.event.dispatch.ClipResolution;
import com.sandymandy.strut.event.dispatch.ServerEventDispatcher;
import com.sandymandy.strut.event.timeline.ClipTimelines;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import java.util.function.Consumer;
import com.sandymandy.strut.network.packet.AnimationPlaybackDeltaPacket;
import com.sandymandy.strut.network.packet.AnimationPlaybackSnapshotPacket;
import com.sandymandy.strut.network.packet.AttachmentDeltaPacket;
import com.sandymandy.strut.network.packet.AttachmentSnapshotPacket;
import com.sandymandy.strut.network.packet.BoneOverrideDeltaPacket;
import com.sandymandy.strut.network.packet.BoneOverrideSnapshotPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The server-side registry of every tracked entity's {@link ServerAnimationState}, and the glue
 * that gets it onto the wire
 * <p>
 * Deliberately just a registry plus event wiring - it holds no pose, no bone hierarchy, nothing a
 * {@link ServerAnimationState} does not already refuse to hold (see that class's own doc comment).
 * Its whole job is answering three questions: "what does a newly-tracking player need to catch up
 * on", "what changed since the last time anyone was told", and "what does everyone need
 * to hear again, just in case".
 * <p>
 * {@link ServerBoneOverrideState} is folded into
 * this same registry rather than standing up a parallel one - every tracked entity's bone overrides
 * ride the identical start-tracking/tick/resync lifecycle its animation state already has, answering
 * the same three questions for a different kind of authoritative state
 *
 * <h2>Keyed by entity id, not by {@code StrutAnimatable}</h2>
 *
 * <p>Tracking is a property of an {@link Entity} - {@code EntityTrackingEvents} fires with the
 * vanilla entity object, whether or not that object is itself a {@code StrutEntity} or is instead the
 * vanilla type a {@code StrutReplacedEntity} has bound itself to. {@code isReplacedEntity} is recorded
 * alongside the state precisely because the entity id alone cannot say which case this is - the
 * same distinction the existing trigger/stateless packets already carry as an explicit caller-given
 * flag, for the same reason: nothing server-side can ask a replaced-entity binding, which only
 * {@link com.sandymandy.strut.util.RenderUtil#getReplacedAnimatable} resolves, and that resolution
 * is itself client-only ({@code MinecraftClient.getInstance()})
 */
public final class ServerAnimationStates {
	/**
	 * How often, in server ticks, every tracked entity's <b>full</b> state is re-sent to everyone
	 * tracking it, on top of the ordinary delta stream
	 * <p>
	 * Deltas alone are correct as long as every one of them actually arrives and gets applied - but
	 * a client's mirror has no way to notice its own corruption or an event-ordering edge case that
	 * left it stale, since {@link ClientAnimationState#applyDelta} only ever sees the deltas that do
	 * arrive, never the ones that do not. A periodic full resend is the cheap, general answer: it
	 * cannot be rejected the way a delta with a stale sequence can (see
	 * {@link ClientAnimationState#applySnapshot}'s own doc comment on why a snapshot is never
	 * sequence-guarded), so however a mirror drifted, this corrects it within one interval. Ten
	 * seconds is arbitrary but deliberately not tiny
	 */
	private static final int RESNAPSHOT_INTERVAL_TICKS = 200;

	private static final Int2ObjectMap<Entry> STATES = new Int2ObjectOpenHashMap<>();

	/**
	 * Typed event dispatch rides this same registry and the same per-tick sweep,
	 * so events see exactly the playbacks the deltas are about to describe
	 */
	private static final ServerEventDispatcher EVENTS = new ServerEventDispatcher(StrutEvents.serverListeners(), StrutEvents.log());

	private ServerAnimationStates() {}

	/**
	 * {@code overrides}/{@code attachments} fold the override state into the exact same per-entity
	 * entry animation playback already uses - all three ride the same start-tracking/tick/resync
	 * lifecycle below, rather than a parallel registry per kind, keyed the same way
	 */
	private record Entry(ServerAnimationState state, ServerBoneOverrideState overrides, ServerAttachmentState attachments,
						  boolean isReplacedEntity) {}

	/**
	 * The given entity's authoritative animation state, creating it (and its siblings
	 * {@link ServerBoneOverrideState}/{@link ServerAttachmentState}) on first use
	 * <p>
	 * {@code isReplacedEntity} is fixed the first time this is called for a given entity id and
	 * ignored on every later call - an entity does not change which kind of animatable it is
	 * mid-life, so there is nothing to reconcile if a later call happened to disagree
	 */
	public static ServerAnimationState forEntity(Entity entity, boolean isReplacedEntity) {
		return entryFor(entity, isReplacedEntity).state();
	}

	/**
	 * The given entity's authoritative bone overrides, creating its entry (and its siblings) on
	 * first use - see {@link #forEntity}'s own doc comment on {@code isReplacedEntity}
	 */
	public static ServerBoneOverrideState overridesFor(Entity entity, boolean isReplacedEntity) {
		return entryFor(entity, isReplacedEntity).overrides();
	}

	/**
	 * The given entity's authoritative attachments, creating its entry (and its siblings) on first
	 * use - see {@link #forEntity}'s own doc comment on {@code isReplacedEntity}
	 */
	public static ServerAttachmentState attachmentsFor(Entity entity, boolean isReplacedEntity) {
		return entryFor(entity, isReplacedEntity).attachments();
	}

	private static Entry entryFor(Entity entity, boolean isReplacedEntity) {
		return STATES.computeIfAbsent(entity.getId(),
				id -> new Entry(new ServerAnimationState(), new ServerBoneOverrideState(), new ServerAttachmentState(), isReplacedEntity));
	}

	/**
	 * This entity's authoritative animation state, or null if nothing has ever called
	 * {@link #forEntity} for it
	 */
	@Nullable
	public static ServerAnimationState get(Entity entity) {
		Entry entry = STATES.get(entity.getId());

		return entry != null ? entry.state() : null;
	}

	/**
	 * This entity's authoritative bone overrides, or null if nothing has ever called
	 * {@link #forEntity}/{@link #overridesFor} for it
	 */
	@Nullable
	public static ServerBoneOverrideState getOverrides(Entity entity) {
		Entry entry = STATES.get(entity.getId());

		return entry != null ? entry.overrides() : null;
	}

	/**
	 * This entity's authoritative attachments, or null if nothing has ever called
	 * {@link #forEntity}/{@link #attachmentsFor} for it
	 */
	@Nullable
	public static ServerAttachmentState getAttachments(Entity entity) {
		Entry entry = STATES.get(entity.getId());

		return entry != null ? entry.attachments() : null;
	}

	/**
	 * Drops an entity's state entirely, on {@link ServerEntityEvents#ENTITY_UNLOAD}
	 */
	private static void remove(Entity entity) {
		STATES.remove(entity.getId());
		EVENTS.forget(entity.getId());
		StrutEvents.trace().stop(entity.getId());
		StrutEvents.log().forget(entity.getId());
	}

	/**
	 * Wires the four Fabric events this class needs: a snapshot on start-tracking, cleanup on
	 * unload, a per-world-tick sweep that turns {@link ServerAnimationState#drainDirty()} into delta
	 * packets (with a periodic full resend layered on top), and a server-bound listener for
	 * a client's explicit resync request. Called once, from
	 * {@link com.sandymandy.strut.Strut#onInitialize()}
	 * <p>
	 * Safe to register unconditionally on the common entrypoint: every one of these is a server-side
	 * Fabric API event or a server-bound packet receiver, neither of which fires on a client with no
	 * integrated server running - the same reasoning {@code StrutNetworking#init} already relies on
	 * for registering both bound directions of every packet from the same common entrypoint
	 */
	public static void init() {
		EntityTrackingEvents.START_TRACKING.register(ServerAnimationStates::sendSnapshotTo);

		// A player never starts tracking themselves, so START_TRACKING does not fire for their own client: send them the state of their own entity
		// (a body with a scene playing, bone overrides) when they join, respawn or arrive in another dimension
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendOwnSnapshot(handler.getPlayer()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sendOwnSnapshot(newPlayer));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> sendOwnSnapshot(player));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> remove(entity));
		ServerTickEvents.END_WORLD_TICK.register(ServerAnimationStates::tick);
	}

	/**
	 * Sends {@code entity}'s current, full state to {@code player} - what a snapshot always is,
	 * whichever of the three things that triggers it (start-tracking, a periodic resend, or an
	 * explicit {@code AnimationResyncRequestPacket})
	 * <p>
	 * Public, unlike the rest of this class's write path, specifically so
	 * {@code AnimationResyncRequestPacket#receiveMessage} can call it directly for the one player who
	 * asked, without needing its own copy of this lookup-and-send logic
	 *
	 * @param sendEvenIfEmpty Whether to send a snapshot naming no controllers at all. Skipped for
	 *                        start-tracking (a freshly-tracking player has nothing playing by
	 *                        default, so an empty snapshot is redundant) but not for a resync of any
	 *                        kind - the whole point there may be correcting a player who is stuck
	 *                        believing something plays when the server says nothing does, which an
	 *                        empty snapshot is the only way to say
	 */
	public static void sendSnapshotTo(Entity entity, ServerPlayerEntity player, boolean sendEvenIfEmpty, SnapshotReason reason) {
		Entry entry = STATES.get(entity.getId());

		if (entry == null)
			return;

		List<AnimationPlayback> playbacks = List.copyOf(entry.state().snapshot());

		if (!playbacks.isEmpty() || sendEvenIfEmpty) {
			StrutServices.NETWORK.sendToPlayer(
					new AnimationPlaybackSnapshotPacket(entity.getId(), entry.isReplacedEntity(), reason, playbacks), player);
		}

		List<BoneOverrideSnapshotPacket.Entry> overrides = toWireEntries(entry.overrides().snapshot());

		if (!overrides.isEmpty() || sendEvenIfEmpty) {
			StrutServices.NETWORK.sendToPlayer(
					new BoneOverrideSnapshotPacket(entity.getId(), entry.isReplacedEntity(), overrides), player);
		}

		List<AttachmentSnapshotPacket.Entry> attachments = attachmentWireEntries(entry.attachments().snapshot());

		if (!attachments.isEmpty() || sendEvenIfEmpty) {
			StrutServices.NETWORK.sendToPlayer(
					new AttachmentSnapshotPacket(entity.getId(), entry.isReplacedEntity(), attachments), player);
		}
	}

	private static void sendOwnSnapshot(ServerPlayerEntity player) {
		sendSnapshotTo(player, player, false, SnapshotReason.START_TRACKING);
	}

	private static List<BoneOverrideSnapshotPacket.Entry> toWireEntries(Map<OverrideKey, BoneOverride> overrides) {
		List<BoneOverrideSnapshotPacket.Entry> entries = new ArrayList<>(overrides.size());

		for (Map.Entry<OverrideKey, BoneOverride> mapEntry : overrides.entrySet()) {
			entries.add(new BoneOverrideSnapshotPacket.Entry(mapEntry.getKey(), mapEntry.getValue()));
		}

		return entries;
	}

	private static List<AttachmentSnapshotPacket.Entry> attachmentWireEntries(Map<String, Attachment> attachments) {
		List<AttachmentSnapshotPacket.Entry> entries = new ArrayList<>(attachments.size());

		for (Map.Entry<String, Attachment> mapEntry : attachments.entrySet()) {
			entries.add(new AttachmentSnapshotPacket.Entry(mapEntry.getKey(), mapEntry.getValue()));
		}

		return entries;
	}

	private static void sendSnapshotTo(Entity trackedEntity, ServerPlayerEntity player) {
		sendSnapshotTo(trackedEntity, player, false, SnapshotReason.START_TRACKING);
	}

	/**
	 * Once per world per server tick: drains deltas as before, and every
	 * {@value #RESNAPSHOT_INTERVAL_TICKS} ticks also resends every tracked entity's full state to
	 * everyone tracking it
	 */
	private static void tick(ServerWorld world) {
		// Events first: a handler that starts or stops a clip is then flushed to clients in this same tick
		dispatchEvents(world);
		flushDirty(world);

		/*
		 * Keyed off the world's own time rather than a manually incremented counter, and
		 * deliberately not a shared one across worlds: END_WORLD_TICK fires once per loaded world
		 * per server tick, so a single counter incremented here would advance once per world - three
		 * loaded dimensions would fire the resnapshot three times as often as intended. Each world's
		 * own getTime() already advances exactly once per that world's own tick, independent of how
		 * many other worlds exist, so it is both simpler and correct where a shared counter was not.
		 */
		if (world.getTime() % RESNAPSHOT_INTERVAL_TICKS == 0)
			resnapshotAll(world);
	}

	/**
	 * Once per world per server tick, before {@link #flushDirty}: delivers the typed events each tracked
	 * entity's playing clips crossed since the last tick - see {@link ServerEventDispatcher}
	 * <p>
	 * Walks a <b>copy</b> of the entity ids, not the live map: a handler is free to call
	 * {@link #forEntity} for another entity, which inserts into {@link #STATES}, and inserting into a
	 * fastutil map while iterating it is undefined
	 */
	private static void dispatchEvents(ServerWorld world) {
		for (int id : STATES.keySet().toIntArray()) {
			Entity entity = world.getEntityById(id);
			Entry entry = STATES.get(id);

			if (entity == null || entry == null)
				continue;

			Consumer<EventContext> hook = entity instanceof StrutEntity geo ? geo::onAnimationEvent : null;
			List<Identifier> declared = entity instanceof StrutEntity geo ? geo.getAnimationFiles() : List.of();

			// a player with a body: the body says what a StrutEntity says for itself
			if (entity instanceof PlayerEntity player) {
				PlayerBody body = StrutPlayers.body(player);

				if (body != null) {
					hook = body::onServerEvent;
					declared = body.animationFiles(StrutPlayers.variant(player));
				}
			}

			List<Identifier> declaredFiles = declared;

			EVENTS.sweep(new ServerEventDispatcher.Subject(id, entity.getUuid(), entity, hook), entry.state(),
					clip -> ClipResolution.resolve(ClipTimelines.instance(), declaredFiles, clip), world.getTime());
		}
	}

	/**
	 * Once per world per server tick: every entity with a registered state that belongs to this
	 * world gets its dirty controllers <em>and</em> dirty bone overrides drained and sent as deltas
	 * to everyone tracking it
	 * <p>
	 * An entity id whose owning world is not {@code world} simply fails {@code getEntityById} here
	 * and is skipped for this call - it is picked up on its own world's own tick instead, since
	 * {@link ServerTickEvents#END_WORLD_TICK} fires once for every loaded world, every tick
	 */
	private static void flushDirty(ServerWorld world) {
		for (Int2ObjectMap.Entry<Entry> mapEntry : STATES.int2ObjectEntrySet()) {
			Entity entity = world.getEntityById(mapEntry.getIntKey());

			if (entity == null)
				continue;

			Entry entry = mapEntry.getValue();

			for (String controller : entry.state().drainDirty()) {
				AnimationPlayback playback = entry.state().get(controller);
				long sequence = entry.state().lastSequenceFor(controller);

				StrutServices.NETWORK.sendToAllPlayersTrackingEntity(
						new AnimationPlaybackDeltaPacket(entity.getId(), entry.isReplacedEntity(), controller, sequence, playback,
								entry.state().takePendingKind(controller)),
						entity);
			}

			for (OverrideKey key : entry.overrides().drainDirty()) {
				BoneOverride override = entry.overrides().get(key.bone(), key.property());
				long sequence = entry.overrides().lastSequenceFor(key.bone(), key.property());

				StrutServices.NETWORK.sendToAllPlayersTrackingEntity(
						new BoneOverrideDeltaPacket(entity.getId(), entry.isReplacedEntity(), key, sequence, override),
						entity);
			}

			for (String slot : entry.attachments().drainDirty()) {
				Attachment attachment = entry.attachments().get(slot);
				long sequence = entry.attachments().lastSequenceFor(slot);

				StrutServices.NETWORK.sendToAllPlayersTrackingEntity(
						new AttachmentDeltaPacket(entity.getId(), entry.isReplacedEntity(), slot, sequence, attachment),
						entity);
			}
		}
	}

	/**
	 * The safety net: every tracked entity in {@code world} gets its full current state
	 * resent to everyone tracking it, empty or not - see {@link #RESNAPSHOT_INTERVAL_TICKS}
	 * <p>
	 * Manually walks {@link PlayerLookup#tracking} plus the "entity is itself a player" case, the
	 * same pair {@code StrutNetworking#sendToAllPlayersTrackingEntity} covers in one call - not
	 * reused here because that method sends one already-built packet to everyone, while this needs
	 * {@link #sendSnapshotTo} itself (with {@code sendEvenIfEmpty} true) per recipient
	 */
	private static void resnapshotAll(ServerWorld world) {
		for (Int2ObjectMap.Entry<Entry> mapEntry : STATES.int2ObjectEntrySet()) {
			Entity entity = world.getEntityById(mapEntry.getIntKey());

			if (entity == null)
				continue;

			if (entity instanceof ServerPlayerEntity self)
				sendSnapshotTo(entity, self, true, SnapshotReason.PERIODIC);

			for (ServerPlayerEntity player : PlayerLookup.tracking(entity)) {
				sendSnapshotTo(entity, player, true, SnapshotReason.PERIODIC);
			}
		}
	}
}
