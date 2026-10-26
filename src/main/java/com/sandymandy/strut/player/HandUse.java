package com.sandymandy.strut.player;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.Nullable;

/**
 * Which hand of a holder is occupied with an item right now, so that what the other hand holds can be kept out of the way: the hand using an item (a bow drawn, a
 * shield raised, food eaten), or the hand holding a crossbow that is loaded, which is aimed and fired in the same way though nothing is being used
 */
public final class HandUse {
	private HandUse() {}

	/** The hand that is busy, or null if neither is */
	@Nullable
	public static Hand busyHand(LivingEntity holder) {
		if (holder.isUsingItem())
			return holder.getActiveHand();

		if (isLoadedCrossbow(holder.getMainHandStack()))
			return Hand.MAIN_HAND;

		return isLoadedCrossbow(holder.getOffHandStack()) ? Hand.OFF_HAND : null;
	}

	/** Whether {@code hand}'s item is hidden because the other hand is busy */
	public static boolean hiddenBecauseOtherIsBusy(LivingEntity holder, Hand hand) {
		Hand busy = busyHand(holder);

		return busy != null && busy != hand;
	}

	private static boolean isLoadedCrossbow(ItemStack stack) {
		return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
	}
}
