package com.sandymandy.strut.mixin.client;

import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.state.ModelInstance;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.model.ModelBounds;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Widens
 * {@code Entity#getVisibilityBoundingBox()} to cover a Strut model's actual rest-pose extent for any
 * entity implementing {@link StrutAnimatable}, fixing a real gap: vanilla's own frustum cull
 * (`EntityRenderer#shouldRender`) only ever tests the entity's hitbox, so a model reaching well past
 * it (wings, a tail, a long weapon) can pop out of view while still partly on screen
 * <p>
 * {@code Entity#getVisibilityBoundingBox()} is exactly vanilla's own extension point for this - many
 * vanilla entities already override it (an ender dragon, an armor stand) - but Strut can't override
 * a consumer mod's own arbitrary {@code Entity} subclass, so this mixin reaches every one of them at
 * once instead, narrowly scoped to the one interface Strut does own
 * <p>
 * Reads the entity's own already-bound {@link BakedStrutModel} via its {@link ModelInstance}
 * (deliberately not the renderer - no {@code EntityRenderer} reference is available or needed here),
 * so this only ever widens the box starting from the first frame the entity actually renders; before
 * that, {@link ModelInstance#boundModel()} is {@code null} and the vanilla hitbox box is left alone,
 * a harmless one-frame gap rather than a correctness problem
 */
@Mixin(Entity.class)
public abstract class EntityVisibilityBoundsMixin {
	@Inject(method = "getVisibilityBoundingBox", at = @At("RETURN"), cancellable = true)
	private void strut$expandForModelBounds(CallbackInfoReturnable<Box> cir) {
		Entity self = (Entity)(Object)this;

		if (!(self instanceof StrutAnimatable animatable))
			return;

		AnimatableManager<?> manager = animatable.getAnimatableInstanceCache().getManagerForId(self.getId());
		ModelInstance modelInstance = manager.getModelInstance();
		BakedStrutModel model = modelInstance.boundModel();

		if (model == null)
			return;

		Box modelBox = ModelBounds.restPoseBoundsFor(model);

		if (modelBox == null)
			return;

		Box entityBox = translate(modelBox, self.getX(), self.getY(), self.getZ());

		cir.setReturnValue(cir.getReturnValue().union(entityBox));
	}

	private static Box translate(Box modelBox, double x, double y, double z) {
		return new Box(modelBox.minX + x, modelBox.minY + y, modelBox.minZ + z,
				modelBox.maxX + x, modelBox.maxY + y, modelBox.maxZ + z);
	}
}
