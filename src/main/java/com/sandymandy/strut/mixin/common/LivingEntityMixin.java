package com.sandymandy.strut.mixin.common;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.sandymandy.strut.StrutConstants;

/**
 * Injection into the equipment change handling to allow for bypassing Strut ItemStack ID parity
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    /**
     * In {@code ItemStackMixin#strut$skipStrutIdOnCompare}, we tell Minecraft to ignore the contents of Strut
     * stack ids for the purposes of ItemStack parity.
     * <p>
     * We temporarily reinstate it here so that the game syncs changes to this specific component.
     * <p>
     * {@code areItemsDifferent} is the per-slot comparison {@code getEquipmentChanges} runs against the
     * last-synced stacks; making its inner {@code areEqual} stricter makes the slot count as changed
     * when only the Strut stack ID differs.
     */
    @WrapOperation(method = "areItemsDifferent", at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;areEqual(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z"))
    public boolean strut$allowLazyStackIdParity(ItemStack remoteStack, ItemStack localStack, Operation<Boolean> original) {
        return original.call(remoteStack, localStack)
                && remoteStack.getOrDefault(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get(), Long.MIN_VALUE)
                .equals(localStack.getOrDefault(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get(), Long.MIN_VALUE));
    }
}
