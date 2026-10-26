package com.sandymandy.strut.mixin.common;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.component.ComponentMapImpl;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.util.InternalUtil;

/**
 * Injection into ItemStack functionality to handle duplication and splitting with Strut stack identifiers
 */
@Mixin(ItemStack.class)
public class ItemStackMixin {
    /**
     * Remove the Strut stack ID when splitting up a stack into two
     */
    @WrapOperation(method = "split", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;copyWithCount(I)Lnet/minecraft/item/ItemStack;"))
    public ItemStack strut$removeStrutIdOnCopy(ItemStack instance, int count, Operation<ItemStack> original) {
        ItemStack copy = original.call(instance, count);

        if (count < instance.getCount() && copy.contains(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get()))
            copy.remove(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get());

        return copy;
    }

    /**
     * Consider ItemStacks equal if the only difference is their Strut stack ID
     * <p>
     * We do this so that the game doesn't prevent combining stacks due solely to Strut sync IDs.
     * <p>
     * This is the single {@code Objects.equals} on the two stacks' component maps at the tail of
     * {@code areItemsAndComponentsEqual}, which is what every other stack-parity check in the game
     * (including {@link ItemStack#areEqual}) ultimately funnels through.
     */
    @WrapOperation(method = "areItemsAndComponentsEqual", at = @At(value = "INVOKE", target = "Ljava/util/Objects;equals(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private static boolean strut$skipStrutIdOnCompare(Object a, Object b, Operation<Boolean> original) {
        if (original.call(a, b))
            return true;

        if (!(a instanceof ComponentMapImpl components) || !(b instanceof ComponentMapImpl components2))
            return false;

        return InternalUtil.areComponentsMatchingIgnoringStrutId(components, components2);
    }
}
