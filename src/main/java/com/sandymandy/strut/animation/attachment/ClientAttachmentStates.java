package com.sandymandy.strut.animation.attachment;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * The client-side registry of every tracked entity's {@link ClientAttachmentState} - the
 * attachment-shaped twin of {@link com.sandymandy.strut.animation.override.ClientBoneOverrideStates}
 */
public final class ClientAttachmentStates {
	private static final Int2ObjectMap<ClientAttachmentState> STATES = new Int2ObjectOpenHashMap<>();

	private ClientAttachmentStates() {}

	/**
	 * The given entity's mirrored attachment state, creating it on first use
	 */
	public static ClientAttachmentState forEntity(int entityId) {
		return STATES.computeIfAbsent(entityId, id -> new ClientAttachmentState());
	}

	/**
	 * This entity's mirrored attachment state, or null if nothing has ever been received for it
	 */
	@Nullable
	public static ClientAttachmentState get(int entityId) {
		return STATES.get(entityId);
	}

	/**
	 * Drops an entity's mirrored attachment state entirely, on {@code ClientEntityEvents#ENTITY_UNLOAD}
	 * - the same cleanup {@code ClientBoneOverrideStates#remove} gets, from the same call site
	 */
	public static void remove(int entityId) {
		STATES.remove(entityId);
	}
}
