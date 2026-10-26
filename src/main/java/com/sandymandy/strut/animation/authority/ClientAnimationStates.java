package com.sandymandy.strut.animation.authority;

import com.sandymandy.strut.network.packet.AnimationResyncRequestPacket;
import com.sandymandy.strut.network.packet.MultiloaderPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The client-side registry of every tracked entity's {@link ClientAnimationState}
 * <p>
 * The mirror image of {@link ServerAnimationStates}, and mostly simpler: it needs no live
 * {@code Entity} or {@code World} to be exercised, just an entity id. Packet-side wiring for
 * receiving lives on {@link com.sandymandy.strut.network.packet.AnimationPlaybackSnapshotPacket}
 * and {@link com.sandymandy.strut.network.packet.AnimationPlaybackDeltaPacket}'s own
 * {@code receiveMessage}; cleanup on entity unload is wired from {@code StrutClient} rather than
 * from here, since the Fabric event it needs ({@code ClientEntityEvents}) is client-only and this
 * class is not
 *
 * <h2>Requesting a resync</h2>
 *
 * <p>{@link #requestResync} sends {@link AnimationResyncRequestPacket}, but cannot call
 * {@code ClientPlayNetworking.send} directly - that class lives under Fabric's own
 * {@code api.client.*} package, the boundary this project already treats as the client-only side
 * marker (the same reasoning {@code ClientEntityEvents}'s registration in {@code StrutClient} was
 * kept out of this class for). Referencing it from this common-loaded class would risk
 * the same class-loading crash on a dedicated server that boundary exists to prevent.
 * {@link #setSender} installs a plain hook instead: this class calls it without knowing what is on
 * the other end, and {@code StrutClient#onInitializeClient} - the one place that already imports
 * {@code ClientPlayNetworking} safely - is what actually sets it
 */
public final class ClientAnimationStates {
	private static final Int2ObjectMap<ClientAnimationState> STATES = new Int2ObjectOpenHashMap<>();
	private static Consumer<MultiloaderPacket> sender = packet -> {};
	private static LongSupplier clock = null;

	private ClientAnimationStates() {}

	/**
	 * The given entity's mirrored animation state, creating it on first use
	 */
	public static ClientAnimationState forEntity(int entityId) {
		return STATES.computeIfAbsent(entityId, id -> new ClientAnimationState(clock));
	}

	/**
	 * This entity's mirrored state, or null if nothing has ever been received for it
	 */
	@Nullable
	public static ClientAnimationState get(int entityId) {
		return STATES.get(entityId);
	}

	/**
	 * The ids of every entity with a mirrored state, as a copy - callers may mirror new states into the
	 * registry while walking it, which inserting into the live map mid-iteration would not survive
	 */
	public static int[] entityIds() {
		return STATES.keySet().toIntArray();
	}

	/**
	 * Drops an entity's mirrored state entirely, on {@code ClientEntityEvents#ENTITY_UNLOAD}
	 */
	public static void remove(int entityId) {
		STATES.remove(entityId);
	}

	/**
	 * Installs how {@link #requestResync} actually reaches the network
	 * <p>
	 * A no-op until this is called - safe on a dedicated server, which never calls it and never
	 * needs to request anything from itself
	 */
	/**
	 * Installs the game clock every state stamps its changes with, so a change is recorded at the time it
	 * <i>arrived</i>. Set once from {@code StrutClient}, for the same reason {@link #setSender} is: reading the
	 * world's time means naming a client class, which this common-side class must not do
	 * <p>
	 * States created before this is called (none, in practice) have no clock and fall back to the time of the
	 * event sweep that reads them
	 */
	public static void setClock(LongSupplier gameTime) {
		clock = gameTime;
	}

	public static void setSender(Consumer<MultiloaderPacket> packetSender) {
		sender = packetSender;
	}

	/**
	 * Asks the server for {@code entityId}'s full current state, ignoring whatever this side is
	 * currently mirroring for it
	 * <p>
	 * Called from {@code AnimationController} when a {@code SERVER}-authority controller finds no
	 * mirrored state at all for its own entity - not "nothing is playing", which is a normal,
	 * expected state {@link ClientAnimationState#get} answers with null just as readily, but "this
	 * entity has never been snapshotted at all", which should not be possible once tracking has
	 * started and is worth actively asking about rather than silently waiting out
	 */
	public static void requestResync(int entityId) {
		sender.accept(new AnimationResyncRequestPacket(entityId));
	}
}
