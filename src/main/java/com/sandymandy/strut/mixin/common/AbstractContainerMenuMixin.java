package com.sandymandy.strut.mixin.common;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.sandymandy.strut.StrutConstants;

/**
 * Injection into the base container functionality to handle ItemStack duplication and splitting with Strut stack identifiers
 */
@Mixin(ScreenHandler.class)
public class AbstractContainerMenuMixin {
    /**
     * Remove the Strut stack ID from a stack when copying it with middle-click
     * <p>
     * {@code internalOnSlotClick} makes two {@code copyWithCount} calls; the second (ordinal 1) is
     * the one in the {@code SlotActionType.CLONE} branch.
     */
    @WrapOperation(method = "internalOnSlotClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;copyWithCount(I)Lnet/minecraft/item/ItemStack;", ordinal = 1))
    public ItemStack strut$removeStrutIdOnCopy(ItemStack instance, int count, Operation<ItemStack> original) {
        ItemStack copy = original.call(instance, count);

        if (copy.contains(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get()))
            copy.remove(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get());

        return copy;
    }

    /**
     * Force ItemStacks that don't match their Strut stack ID to sync to the remote, even though
     * Strut tells the game they're equivalent.
     * <p>
     * {@code checkSlotUpdates} is the slot-diff pass that feeds {@code ScreenHandlerSyncHandler.updateSlot}.
     */
    @WrapOperation(method = "checkSlotUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;areEqual(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z"))
    public boolean strut$forceStrutIdSync(ItemStack stack, ItemStack other, Operation<Boolean> original) {
        return original.call(stack, other) && strut$sameStrutId(stack, other);
    }

    /**
     * Force ItemStacks that don't match their Strut stack ID to trigger slot listeners, even though
     * Strut tells the game they're equivalent.
     * <p>
     * {@code updateTrackedSlot} is the slot-diff pass that feeds {@code ScreenHandlerListener.onSlotUpdate}.
     */
    @WrapOperation(method = "updateTrackedSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;areEqual(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z"))
    public boolean strut$forceStrutSlotChange(ItemStack stack, ItemStack other, Operation<Boolean> original) {
        return original.call(stack, other) && strut$sameStrutId(stack, other);
    }

    private static boolean strut$sameStrutId(ItemStack stack, ItemStack other) {
        return stack.getOrDefault(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get(), -1L)
                .equals(other.getOrDefault(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get(), -1L));
    }
}
