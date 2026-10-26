package com.sandymandy.strut.animation.attachment;

import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Strut models worn by Strut-drawn entities on this client, by entity and slot name (see {@link ModelAttachment})
 * <pre>{@code
 * ClientModelAttachments.set(entity, "hat", new Identifier("mymod", "armour/crown"), new Identifier("mymod", "textures/armour/crown.png"))
 *         .tint(0xFFFFD700);
 * ClientModelAttachments.clear(entity, "hat");
 * }</pre>
 * Local to this client, and gone when the entity is: a mod sets what it wants worn from the equipment it can already see (the armour system does), so nothing is
 * sent. Client side only
 */
public final class ClientModelAttachments {
	private static final Int2ObjectOpenHashMap<Map<String, ModelAttachment>> BY_ENTITY = new Int2ObjectOpenHashMap<>();

	private ClientModelAttachments() {}

	/**
	 * Puts a model on {@code entity} in {@code slot}, replacing what was there if it is another model; the same model keeps what was set on it
	 * ({@link ModelAttachment#tint}, {@link ModelAttachment#uvOffset}, hidden bones) and only changes texture
	 */
	public static ModelAttachment set(Entity entity, String slot, Identifier model, Identifier texture) {
		Map<String, ModelAttachment> slots = BY_ENTITY.computeIfAbsent(entity.getId(), id -> new LinkedHashMap<>());
		ModelAttachment current = slots.get(slot);

		if (current != null && current.model().equals(model))
			return current.texture(texture);

		ModelAttachment attachment = new ModelAttachment(slot, model, texture);

		slots.put(slot, attachment);

		return attachment;
	}

	/** What {@code entity} wears in {@code slot}, or null */
	@Nullable
	public static ModelAttachment get(Entity entity, String slot) {
		Map<String, ModelAttachment> slots = BY_ENTITY.get(entity.getId());

		return slots == null ? null : slots.get(slot);
	}

	/** Takes off what {@code entity} wears in {@code slot} */
	public static void clear(Entity entity, String slot) {
		Map<String, ModelAttachment> slots = BY_ENTITY.get(entity.getId());

		if (slots != null && slots.remove(slot) != null && slots.isEmpty())
			BY_ENTITY.remove(entity.getId());
	}

	/** Takes off everything {@code entity} wears */
	public static void clearAll(Entity entity) {
		BY_ENTITY.remove(entity.getId());
	}

	/** Forgets an entity that has gone ({@code ClientEntityEvents#ENTITY_UNLOAD}) */
	public static void remove(int entityId) {
		BY_ENTITY.remove(entityId);
	}

	/** Forgets everything: the player left the world */
	public static void clearEverything() {
		BY_ENTITY.clear();
	}

	/**
	 * Queues what {@code entity} wears for the GPU batch, posed on the wearer's own pose. Called by the renderer right after it queues the wearer, with the wearer's own
	 * transform and draw state
	 */
	public static void submitAll(Entity entity, BakedStrutModel wearer, Pose wearerPose, Matrix4f transform, GpuBatchQueue.RenderKey key, int packedLight, int packedOverlay,
								 int colour) {
		Map<String, ModelAttachment> slots = BY_ENTITY.get(entity.getId());

		if (slots == null)
			return;

		for (ModelAttachment attachment : slots.values())
			attachment.submit(wearer, wearerPose, transform, key, packedLight, packedOverlay, colour);
	}
}
