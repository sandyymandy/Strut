package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideState;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.animation.override.OverrideValue;
import com.sandymandy.strut.animation.override.ServerBoneOverrideState;
import net.minecraft.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The entity-facing bone-override API - {@code entity.overrides()} on {@link StrutEntity}, or
 * {@code entity.overrides(relatedEntity)} on {@link StrutReplacedEntity}. The setters are on {@link BoneOverrides}
 * <p>
 * Called server-side, an override is authoritative and replicates to every client tracking this entity, via the exact same
 * start-tracking/tick/resync machinery {@code ServerAnimationStates} already runs for animation playback
 */
public final class EntityBoneOverrides extends BoneOverrides {
	private final Entity entity;
	private final boolean isReplacedEntity;

	EntityBoneOverrides(Entity entity, boolean isReplacedEntity) {
		this.entity = entity;
		this.isReplacedEntity = isReplacedEntity;
	}

	/**
	 * The overrides of an entity whose rendering Strut replaces, such as a player with a body: what {@code StrutReplacedEntity#overrides} returns
	 */
	public static EntityBoneOverrides forReplaced(Entity entity) {
		return new EntityBoneOverrides(entity, true);
	}

	@Override
	public void clear(String bone, OverrideProperty property) {
		if (this.entity.getWorld().isClient()) {
			ClientBoneOverrideStates.forEntity(this.entity.getId()).clearLocal(bone, property);
		}
		else {
			ServerAnimationStates.overridesFor(this.entity, this.isReplacedEntity).clear(bone, property);
		}
	}

	@Override
	@Nullable
	public BoneOverride get(String bone, OverrideProperty property) {
		if (this.entity.getWorld().isClient()) {
			ClientBoneOverrideState clientState = ClientBoneOverrideStates.get(this.entity.getId());

			return clientState == null ? null : clientState.resolve(bone, property);
		}

		ServerBoneOverrideState serverState = ServerAnimationStates.getOverrides(this.entity);

		return serverState == null ? null : serverState.get(bone, property);
	}

	@Override
	void apply(String bone, OverrideProperty property, OverrideValue value, OverrideScope scope,
				int priority, float blendWeight, float transitionTicks) {
		if (this.entity.getWorld().isClient()) {
			ClientBoneOverrideStates.forEntity(this.entity.getId())
					.setLocal(bone, property, value, scope, priority, blendWeight, transitionTicks);
		}
		else {
			ServerAnimationStates.overridesFor(this.entity, this.isReplacedEntity)
					.set(bone, property, value, scope, priority, blendWeight, transitionTicks);
		}
	}
}
