package com.sandymandy.strut.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationState;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.model.data.EntityModelData;

/**
 * {@link DefaultedStrutModel} specific to {@code Entities}
 * <p>
 * Using this class pre-sorts provided asset paths into the "entity" subdirectory
 * <p>
 * Additionally it can automatically handle head-turning if the entity has a "head" bone
 */
public class DefaultedEntityStrutModel<T extends StrutAnimatable> extends DefaultedStrutModel<T> {
	@Nullable
	protected String headBone;
	@Deprecated(forRemoval = true)
	protected boolean turnsHead; // Use headbone's non-null state instead

	/**
	 * Create a new instance of this model class
	 * <p>
	 * The asset path should be the truncated relative path from the base folder
	 * <p>
	 * E.G.
	 * <pre>{@code
	 * 	new Identifier("myMod", "animals/red_fish")
	 * }</pre>
	 */
	public DefaultedEntityStrutModel(Identifier assetSubpath) {
		this(assetSubpath, false);
	}

	public DefaultedEntityStrutModel(Identifier assetSubpath, boolean turnsHead) {
		this(assetSubpath, turnsHead ? "head" : null);
	}

	/**
	 * Create a new instance of this model class, optionally providing the name of the head bone to auto-handle rotating
	 */
	public DefaultedEntityStrutModel(Identifier assetSubpath, @Nullable String headBone) {
		super(assetSubpath);

		this.turnsHead = headBone != null;
		this.headBone = headBone;
	}

	/**
	 * Returns the subtype string for this type of model
	 * <p>
	 * This allows for sorting of asset files into neat subdirectories for clean management.
	 */
	@Override
	protected String subtype() {
		return "entity";
	}

	@Override
	public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState) {
		if (this.headBone == null || !this.turnsHead)
			return;

		getBone(animatable, instanceId, this.headBone).ifPresent(head -> {
			EntityModelData entityData = animationState.getData(DataTickets.ENTITY_MODEL_DATA);

			head.setRotX(entityData.headPitch() * MathHelper.RADIANS_PER_DEGREE);
			head.setRotY(entityData.netHeadYaw() * MathHelper.RADIANS_PER_DEGREE);
		});
	}

	/**
	 * Changes the constructor-defined model path for this model to an alternate
	 * <p>
	 * This is useful if your animatable shares a model path with another animatable that differs in path to the texture and animations for this model
	 */
	@Override
	public DefaultedEntityStrutModel<T> withAltModel(Identifier altPath) {
		return (DefaultedEntityStrutModel<T>)super.withAltModel(altPath);
	}

	/**
	 * Changes the constructor-defined animations path for this model to an alternate
	 * <p>
	 * This is useful if your animatable shares an animations path with another animatable that differs in path to the model and texture for this model
	 */
	@Override
	public DefaultedEntityStrutModel<T> withAltAnimations(Identifier altPath) {
		return (DefaultedEntityStrutModel<T>)super.withAltAnimations(altPath);
	}

	/**
	 * Changes the constructor-defined texture path for this model to an alternate
	 * <p>
	 * This is useful if your animatable shares a texture path with another animatable that differs in path to the model and animations for this model
	 */
	@Override
	public DefaultedEntityStrutModel<T> withAltTexture(Identifier altPath) {
		return (DefaultedEntityStrutModel<T>)super.withAltTexture(altPath);
	}
}
