package com.sandymandy.strut.armour;

import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Implemented by an entity that wears armour by its own rules: which wearer profile it uses (a character that has more than one, a custom creature), and where its
 * armour is kept when that is not the game's equipment. An entity that does not implement it uses the profile registered for its type
 * ({@link StrutArmour#wearer}) and its equipment slots
 * <p>
 * The client reads this every frame it draws the entity, so it must be cheap and answer from what the client knows
 */
public interface ArmourWearer {
	/** The wearer profile of this entity now, or null to use the one registered for its type */
	@Nullable
	Identifier armourProfile();

	/**
	 * What the entity wears in {@code slot} ({@code head}, {@code chest}, {@code legs}, {@code feet}, {@code body}, or one of {@link #extraSlots}); null to read the
	 * game's equipment for the slots it has
	 */
	@Nullable
	default ItemStack armourStack(String slot) {
		return null;
	}

	/** Slots besides the game's five that this entity has, such as {@code cape} */
	default List<String> extraSlots() {
		return List.of();
	}
}
