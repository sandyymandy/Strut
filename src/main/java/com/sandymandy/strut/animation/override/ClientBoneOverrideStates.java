package com.sandymandy.strut.animation.override;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * The client-side registry of every tracked entity's {@link ClientBoneOverrideState}
 * <p>
 * The override-shaped twin of
 * {@link com.sandymandy.strut.animation.authority.ClientAnimationStates} - a separate registry
 * rather than folded into that one, since {@code ClientAnimationState}'s own doc comment already
 * scopes it tightly to controller/playback specifically, and overrides are a different concern
 * with a different shape (two stores instead of one - see {@link ClientBoneOverrideState})
 */
public final class ClientBoneOverrideStates {
	private static final Int2ObjectMap<ClientBoneOverrideState> STATES = new Int2ObjectOpenHashMap<>();
	/** The same for block entities, by {@link BlockPos#asLong()} in the world the client is in */
	private static final Long2ObjectMap<ClientBoneOverrideState> BLOCKS = new Long2ObjectOpenHashMap<>();

	private ClientBoneOverrideStates() {}

	/**
	 * The given entity's mirrored override state, creating it on first use
	 */
	public static ClientBoneOverrideState forEntity(int entityId) {
		return STATES.computeIfAbsent(entityId, id -> new ClientBoneOverrideState());
	}

	/**
	 * This entity's mirrored override state, or null if nothing has ever been received or set for it
	 */
	@Nullable
	public static ClientBoneOverrideState get(int entityId) {
		return STATES.get(entityId);
	}

	/**
	 * Drops an entity's mirrored override state entirely, on {@code ClientEntityEvents#ENTITY_UNLOAD}
	 * - the same cleanup {@code ClientAnimationStates#remove} gets, from the same call site
	 */
	public static void remove(int entityId) {
		STATES.remove(entityId);
	}

	/**
	 * Runs {@link ClientBoneOverrideState#clearFrameScopedLocal()} for every tracked entity - the
	 * per-frame sweep, run from a client render-frame hook.
	 * so the hook does not need to know about individual entities.
	 */
	public static void clearFrameScopedLocalForAll() {
		for (ClientBoneOverrideState state : STATES.values()) {
			state.clearFrameScopedLocal();
		}

		for (ClientBoneOverrideState state : BLOCKS.values()) {
			state.clearFrameScopedLocal();
		}
	}

	/**
	 * The mirrored override state of the block entity at {@code pos}, creating it on first use
	 */
	public static ClientBoneOverrideState forBlock(BlockPos pos) {
		return BLOCKS.computeIfAbsent(pos.asLong(), key -> new ClientBoneOverrideState());
	}

	/**
	 * The override state of the block entity at {@code pos}, or null if nothing has been received or set for it
	 */
	@Nullable
	public static ClientBoneOverrideState getBlock(BlockPos pos) {
		return BLOCKS.get(pos.asLong());
	}

	/**
	 * Drops the state of the block entity at {@code pos}, when it unloads or is removed
	 */
	public static void removeBlock(BlockPos pos) {
		BLOCKS.remove(pos.asLong());
	}

	/**
	 * Drops every block entity's state: the client left its world, or joined another
	 */
	public static void clearBlocks() {
		BLOCKS.clear();
	}
}
