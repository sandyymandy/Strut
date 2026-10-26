package com.sandymandy.strut.armour;

import com.mojang.serialization.Codec;
import com.sandymandy.strut.StrutConstants;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Armour for Strut-drawn models: the worn pieces are models of their own, one for each slot, bound to the wearer's bones by name ({@code ModelAttachment}) and
 * worked out by each client from the equipment it already sees
 * <pre>{@code
 * StrutArmour.wearer(MyEntities.CREATURE, new Identifier("mymod", "creature"));      // the profile of an entity type (a file: strut/armour/wearers/creature.json)
 * StrutArmour.setHidden(serverEntity, "chest", true);                        // hide a slot for everyone who sees the entity; saved with it
 * StrutArmour.isHidden(entity, "chest");                                     // either side
 * }</pre>
 * The files are: a wearer profile (which set the wearer wears, which of its bones each slot hides), an armour set (a model
 * for each slot and an atlas), and looks (which cell of the atlas an item uses). The slots are {@code head}, {@code chest}, {@code legs}, {@code feet} and
 * {@code body}, and any a wearer adds ({@link ArmourWearer#extraSlots})
 */
public final class StrutArmour {
	/** The slot names of the game's equipment slots that hold armour */
	public static final Map<String, EquipmentSlot> EQUIPMENT_SLOTS = Map.of("head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST, "legs", EquipmentSlot.LEGS,
			"feet", EquipmentSlot.FEET, "body", EquipmentSlot.BODY);

	/** The slots of every wearer, in the order they are drawn */
	public static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "body");

	private static final Map<EntityType<?>, Identifier> WEARERS = new ConcurrentHashMap<>();

	/** The slots hidden on an entity: synced to every client that sees it and saved with it */
	private static final AttachmentType<List<String>> HIDDEN = AttachmentRegistry.create(StrutConstants.id("armour_hidden"),
			builder -> builder.persistent(Codec.STRING.listOf()).syncWith(PacketCodecs.STRING.collect(PacketCodecs.toList()).cast(), AttachmentSyncPredicate.all()));

	private StrutArmour() {}

	/** Loads the class so the attachment type is registered at startup. Called from the common entrypoint */
	public static void init() {}

	/**
	 * Gives every entity of {@code type} the wearer profile {@code profile} (a file {@code assets/<ns>/strut/armour/wearers/<path>.json}), unless the entity says
	 * otherwise ({@link ArmourWearer})
	 */
	public static void wearer(EntityType<?> type, Identifier profile) {
		WEARERS.put(type, profile);
	}

	/** Every registered wearer: the profile of each entity type, by the type's id */
	public static Map<Identifier, Identifier> registeredWearers() {
		Map<Identifier, Identifier> map = new HashMap<>();

		WEARERS.forEach((type, profile) -> map.put(Registries.ENTITY_TYPE.getId(type), profile));

		return map;
	}

	/** The profile registered for an entity type, or null */
	@Nullable
	public static Identifier wearerOf(EntityType<?> type) {
		return WEARERS.get(type);
	}

	/** Whether {@code slot} is hidden on {@code entity}: worn pieces in it are not drawn. Works on either side */
	public static boolean isHidden(Entity entity, String slot) {
		List<String> hidden = entity.getAttached(HIDDEN);

		return hidden != null && hidden.contains(slot);
	}

	/**
	 * Hides or shows {@code slot} on {@code entity}, for everyone who can see it. Call it on the server; the flag is saved with the entity, so a character's
	 * "show armour" button and a scene that strips her are one call
	 */
	public static void setHidden(Entity entity, String slot, boolean hidden) {
		List<String> current = entity.getAttached(HIDDEN);
		List<String> next = current == null ? new ArrayList<>() : new ArrayList<>(current);

		if (hidden ? !next.contains(slot) : next.remove(slot)) {
			if (hidden)
				next.add(slot);

			entity.setAttached(HIDDEN, next.isEmpty() ? null : List.copyOf(next));
		}
	}

	/**
	 * Hides the armour in every slot of {@code entity}, for everyone who can see it: a character undressing, a scene that wants her bare. Undone with
	 * {@link #showAll}, and the worn items are untouched
	 */
	public static void hideAll(Entity entity) {
		entity.setAttached(HIDDEN, List.copyOf(SLOTS));
	}

	/** Shows every slot of {@code entity} again */
	public static void showAll(Entity entity) {
		entity.removeAttached(HIDDEN);
	}

	/** The slots hidden on {@code entity} now */
	public static Map<String, Boolean> hiddenSlots(Entity entity) {
		Map<String, Boolean> map = new HashMap<>();

		for (String slot : entity.getAttachedOrElse(HIDDEN, List.<String>of()))
			map.put(slot, true);

		return map;
	}
}
