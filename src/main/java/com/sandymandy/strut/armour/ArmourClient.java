package com.sandymandy.strut.armour;

import com.sandymandy.strut.animatable.EntityBoneOverrides;
import com.sandymandy.strut.animation.attachment.ClientModelAttachments;
import com.sandymandy.strut.animation.attachment.ModelAttachment;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.trim.ArmorTrim;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out, on the client, what each Strut-drawn wearer has on, and puts it there: a worn model for each slot with a piece in it, and the bones the slot covers
 * hidden. Called by the renderers before a wearer's pose is resolved
 * <p>
 * Nothing is sent over the network: equipment is already known to every client that sees the wearer. What a slot shows is looked up again only when what is in
 * it changes (the item, its dye, whether the slot is hidden)
 */
@ApiStatus.Internal
public final class ArmourClient {
	/** Below a mod's own overrides (priority 0), above a body's hidden bones ({@code -1000}) */
	private static final int COVER_PRIORITY = -500;
	private static final String SLOT_PREFIX = "armour:";
	private static final String TRIM_SUFFIX = ":trim";
	private static final String OVERLAY_SUFFIX = ":overlay";
	private static final String GLINT_SUFFIX = ":glint";
	/** The width and height in texels of the sheet the game's armour is drawn from, which its glint scale is measured against */
	private static final float VANILLA_SHEET = 64f;

	private static final Int2ObjectOpenHashMap<Worn> WORN = new Int2ObjectOpenHashMap<>();

	private ArmourClient() {}

	/** One wearer's resolved armour: a key per slot of what it was resolved from, and the bones its pieces cover */
	private static final class Worn {
		final Map<String, String> keys = new HashMap<>();
		final Map<String, List<String>> covers = new HashMap<>();
		/** What each slot resolved to, as words, for {@code /strutarmour} */
		final Map<String, String> notes = new LinkedHashMap<>();
		@Nullable
		Identifier profile;
	}

	/**
	 * What {@code entity} wears, in words: its wearer profile and set, and for each slot the item, the look that matched it, the model and the texture, and
	 * what the slot hides. For {@code /strutarmour}
	 */
	public static List<String> describe(Entity entity) {
		List<String> lines = new ArrayList<>();
		Identifier profileId = profileOf(entity);

		if (profileId == null) {
			lines.add("It has no wearer profile (StrutArmour.wearer for its type, ArmourWearer, or PlayerBody.Builder#armour)");

			return lines;
		}

		WearerProfile profile = ArmourData.profile(profileId);

		if (profile == null) {
			lines.add("Its wearer profile " + profileId + " is not loaded (assets/<ns>/strut/armour/wearers/<path>.json)");

			return lines;
		}

		ArmourSet set = ArmourData.set(profile.armourSet());

		lines.add("Wearer profile " + profileId + ", armour set " + (profile.armourSet() == null ? "none given" : profile.armourSet() + (set == null ? " (not loaded)" : "")));

		Worn worn = WORN.get(entity.getId());

		for (String slot : slotsOf(entity)) {
			ItemStack stack = stackOf(entity, slot);
			String note = worn == null ? null : worn.notes.get(slot);

			if (stack == null || stack.isEmpty())
				lines.add(slot + ": nothing worn");
			else if (StrutArmour.isHidden(entity, slot))
				lines.add(slot + ": " + Registries.ITEM.getId(stack.getItem()) + " (hidden by StrutArmour.setHidden)");
			else
				lines.add(slot + ": " + (note != null ? note : Registries.ITEM.getId(stack.getItem()) + " (not resolved yet)"));
		}

		return lines;
	}

	/** Forgets an entity that has gone */
	public static void remove(int entityId) {
		WORN.remove(entityId);
	}

	/** Forgets everything: the player left the world */
	public static void clearEverything() {
		WORN.clear();
	}

	/**
	 * Brings {@code entity}'s worn armour up to date and hides the bones its pieces cover for this frame. Does nothing for an entity with no wearer profile
	 * (and takes off what it wore, if its profile has gone)
	 */
	public static void update(Entity entity) {
		Identifier profileId = profileOf(entity);
		WearerProfile profile = ArmourData.profile(profileId);
		ArmourSet set = profile == null ? null : ArmourData.set(profile.armourSet());
		Worn worn = WORN.get(entity.getId());

		if (set == null) {
			if (worn != null)
				takeOffAll(entity, worn);

			return;
		}

		if (worn == null) {
			worn = new Worn();

			WORN.put(entity.getId(), worn);
		}

		if (!profileId.equals(worn.profile)) {
			takeOffAll(entity, worn);
			WORN.put(entity.getId(), worn);
			worn.profile = profileId;
		}

		EntityBoneOverrides overrides = null;

		for (String slot : slotsOf(entity)) {
			ItemStack stack = stackOf(entity, slot);
			boolean shown = stack != null && !stack.isEmpty() && !StrutArmour.isHidden(entity, slot);
			String key = shown ? key(stack) : "";

			if (!key.equals(worn.keys.get(slot))) {
				worn.keys.put(slot, key);
				worn.covers.remove(slot);
				worn.notes.remove(slot);

				if (!shown || !wear(entity, slot, stack, profile, set, worn))
					clearSlot(entity, slot);
			}

			List<String> bones = worn.covers.get(slot);

			if (bones != null && !bones.isEmpty()) {
				if (overrides == null)
					overrides = EntityBoneOverrides.forReplaced(entity);

				for (String bone : bones)
					overrides.setHidden(bone, true, OverrideScope.FRAME, COVER_PRIORITY, 1f, 0f);

			}
		}
	}

	/** Puts the piece in {@code stack} on in {@code slot}; false if there is nothing to draw for it */
	private static boolean wear(Entity entity, String slot, ItemStack stack, WearerProfile profile, ArmourSet set, Worn worn) {
		Identifier itemId = Registries.ITEM.getId(stack.getItem());
		ArmourLook look = ArmourData.lookFor(itemId, tag -> stack.isIn(TagKey.of(RegistryKeys.ITEM, tag)), materialOf(stack), set.id(), slot);
		Identifier model = look != null && look.model() != null ? look.model() : set.model(slot);

		if (model == null) {
			worn.notes.put(slot, itemId + ": the set " + set.id() + " has no model for this slot, so nothing is drawn");

			return false;
		}

		Identifier texture = look != null && look.texture() != null ? look.texture() : set.texture();
		ModelAttachment attachment = ClientModelAttachments.set(entity, SLOT_PREFIX + slot, model, texture).boneMap(profile.bones()).fit(profile.fit());

		// a look that brings its own model is drawn with that model's own UVs unless it names a column
		if (look != null && (look.model() == null || look.column() != 0 || look.row() != 0)) {
			float[] uv = set.uvOffset(look.column(), look.row());

			attachment.uvOffset(uv[0], uv[1]);
		}
		else {
			attachment.uvOffset(0f, 0f);
		}

		DyedColorComponent dye = stack.get(DataComponentTypes.DYED_COLOR);

		attachment.tint(look == null ? 0xFFFFFFFF : look.tintFor(dye == null ? -1 : dye.rgb()));
		worn.covers.put(slot, profile.coversOf(slot));
		worn.notes.put(slot, itemId + ": " + (look == null ? "no look matches (drawn untinted, column 0)" : "look " + look.id() + " (column " + look.column() + ", row " + look.row() + ")")
				+ ", model " + model + ", texture " + texture + (profile.coversOf(slot).isEmpty() ? "" : ", hides " + profile.coversOf(slot)));
		wearTrim(entity, slot, stack, profile, set, model);
		wearOverlay(entity, slot, look, set, profile, model, texture);
		wearGlint(entity, slot, stack, profile, set, model);

		return true;
	}

	/** A look's second cell of the atlas, drawn over the piece untinted (leather's buckles): the slot's model again, blended */
	private static void wearOverlay(Entity entity, String slot, @Nullable ArmourLook look, ArmourSet set, WearerProfile profile, Identifier model, Identifier texture) {
		String id = SLOT_PREFIX + slot + OVERLAY_SUFFIX;

		if (look == null || look.overlayColumn() == null) {
			ClientModelAttachments.clear(entity, id);

			return;
		}

		float[] uv = set.uvOffset(look.overlayColumn(), look.overlayRow());

		ClientModelAttachments.set(entity, id, model, texture).boneMap(profile.bones()).fit(profile.fit()).translucent(true).uvOffset(uv[0], uv[1]).tint(0xFFFFFFFF);
	}

	/**
	 * An enchanted piece's glint, if the set has one and the wearer shows it: the slot's model again, in the glint texture scrolling over it, added to the
	 * armour (see {@link ModelAttachment#additive})
	 */
	private static void wearGlint(Entity entity, String slot, ItemStack stack, WearerProfile profile, ArmourSet set, Identifier model) {
		String id = SLOT_PREFIX + slot + GLINT_SUFFIX;
		ArmourSet.Glint glint = set.glint();

		if (!stack.hasGlint() || glint.texture() == null || !profile.showsGlint()) {
			ClientModelAttachments.clear(entity, id);

			return;
		}

		int level = Math.round(Math.max(0f, Math.min(1f, glint.strength())) * 255f);

		// The game's glint is laid over its 64 texel armour sheet at 0.16 of a glint texture across it. The pieces' UVs are in the atlas (a piece spans a hundredth of
		// it), so the same scale there has to follow the texel, not the UV: as many glint texture widths across 64 atlas texels as the game has across its sheet
		float repeatU = glint.repeat() * set.textureWidth() / VANILLA_SHEET;
		float repeatV = glint.repeat() * set.textureHeight() / VANILLA_SHEET;

		// added to the armour as the game does it (the colour of the texture, times its own colour): the strength dims the texture
		ClientModelAttachments.set(entity, id, model, glint.texture()).boneMap(profile.bones()).fit(profile.fit()).additive(true).glint(repeatU, repeatV)
				.tint(0xFF000000 | (level << 16) | (level << 8) | level);
	}

	private static void clearSlot(Entity entity, String slot) {
		ClientModelAttachments.clear(entity, SLOT_PREFIX + slot);
		ClientModelAttachments.clear(entity, SLOT_PREFIX + slot + TRIM_SUFFIX);
		ClientModelAttachments.clear(entity, SLOT_PREFIX + slot + OVERLAY_SUFFIX);
		ClientModelAttachments.clear(entity, SLOT_PREFIX + slot + GLINT_SUFFIX);
	}

	/**
	 * The trim of a piece, if the set has trims, the wearer shows them and the piece has one: the slot's model again, blended over the armour in the trim
	 * pattern's texture, tinted by the trim material. Trims are optional on every level, and the piece is drawn without one otherwise
	 */
	private static void wearTrim(Entity entity, String slot, ItemStack stack, WearerProfile profile, ArmourSet set, Identifier model) {
		String id = SLOT_PREFIX + slot + TRIM_SUFFIX;
		ArmorTrim trim = stack.get(DataComponentTypes.TRIM);
		Identifier pattern = trim == null ? null : trim.getPattern().getKey().map(RegistryKey::getValue).orElse(null);
		Identifier material = trim == null ? null : trim.getMaterial().getKey().map(RegistryKey::getValue).orElse(null);
		Identifier texture = !set.hasTrims() || !profile.showsTrims() || pattern == null || material == null ? null : set.trimTextureFor(pattern);

		if (texture == null) {
			ClientModelAttachments.clear(entity, id);

			return;
		}

		ClientModelAttachments.set(entity, id, model, texture).boneMap(profile.bones()).fit(profile.fit()).translucent(true).tint(0xFF000000 | set.trimColour(material));
	}

	private static void takeOffAll(Entity entity, Worn worn) {
		for (String slot : worn.keys.keySet())
			clearSlot(entity, slot);

		worn.keys.clear();
		worn.covers.clear();
		worn.profile = null;
		WORN.remove(entity.getId());
	}

	/** What a slot is resolved from: the item, its dye, and the set it is for */
	private static String key(ItemStack stack) {
		DyedColorComponent dye = stack.get(DataComponentTypes.DYED_COLOR);

		ArmorTrim trim = stack.get(DataComponentTypes.TRIM);

		return Registries.ITEM.getId(stack.getItem()) + "#" + (dye == null ? -1 : dye.rgb()) + "#" + stack.hasGlint() + "#" + (trim == null ? "" : trim.getPattern().getKey() + "/" + trim.getMaterial().getKey());
	}

	@Nullable
	private static Identifier materialOf(ItemStack stack) {
		if (stack.getItem() instanceof ArmorItem armor) {
			Optional<RegistryKey<ArmorMaterial>> key = armor.getMaterial().getKey();

			return key.map(RegistryKey::getValue).orElse(null);
		}

		return null;
	}

	/** The wearer profile of {@code entity}: its own say, a player's body, or the one registered for its type */
	@Nullable
	static Identifier profileOf(Entity entity) {
		if (entity instanceof ArmourWearer wearer) {
			Identifier own = wearer.armourProfile();

			if (own != null)
				return own;
		}

		if (entity instanceof PlayerEntity player) {
			PlayerBody body = StrutPlayers.body(player);

			if (body != null && body.armourProfile(StrutPlayers.variant(player)) != null)
				return body.armourProfile(StrutPlayers.variant(player));
		}

		return StrutArmour.wearerOf(entity.getType());
	}

	private static List<String> slotsOf(Entity entity) {
		if (entity instanceof ArmourWearer wearer && !wearer.extraSlots().isEmpty()) {
			List<String> all = new ArrayList<>(StrutArmour.SLOTS);

			all.addAll(wearer.extraSlots());

			return all;
		}

		return StrutArmour.SLOTS;
	}

	@Nullable
	private static ItemStack stackOf(Entity entity, String slot) {
		if (entity instanceof ArmourWearer wearer) {
			ItemStack own = wearer.armourStack(slot);

			if (own != null)
				return own;
		}

		if (entity instanceof LivingEntity living && StrutArmour.EQUIPMENT_SLOTS.containsKey(slot)) {
			ItemStack equipped = living.getEquippedStack(StrutArmour.EQUIPMENT_SLOTS.get(slot));

			// an elytra is worn in the chest slot but is not armour: no chestplate model is drawn for it (capes and elytras are not drawn by Strut)
			return slot.equals("chest") && equipped.isOf(Items.ELYTRA) ? ItemStack.EMPTY : equipped;
		}

		return null;
	}
}
