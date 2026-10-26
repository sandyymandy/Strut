package com.sandymandy.strut.animatable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.client.StrutRenderProvider;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;

import java.util.function.Consumer;

/**
 * The {@link StrutAnimatable} interface specific to {@link Entity Entities}
 * <p>
 * This interface is <u>specifically</u> for entities replacing the rendering of other, existing entities
 *
 * @see "Strut documentation: doc/entities.html"
 */
public interface StrutReplacedEntity extends SingletonStrutAnimatable {
	/**
	 * Returns the {@link EntityType} this entity is intending to replace
	 */
	EntityType<?> getReplacingEntityType();

	/**
	 * Get server-synced animation data via its relevant {@link SerializableDataTicket}
	 * <p>
	 * Should only be used on the <u>client-side</u>
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param entity The entity instance relevant to the data being set
	 * @param dataTicket The data ticket for the data to retrieve
	 * @return The synced data, or null if no data of that type has been synced
	 */
	@ApiStatus.NonExtendable
	@Nullable
	default <D> D getAnimData(Entity entity, SerializableDataTicket<D> dataTicket) {
		return getAnimatableInstanceCache().getManagerForId(entity.getId()).getData(dataTicket);
	}

	/**
	 * Saves an arbitrary syncable piece of data to this animatable's {@link AnimatableManager}
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param relatedEntity An entity related to the state of the data for syncing
	 * @param dataTicket The DataTicket to sync the data for
	 * @param data The data to sync
	 */
	@ApiStatus.NonExtendable
	default <D> void setAnimData(Entity relatedEntity, SerializableDataTicket<D> dataTicket, D data) {
		if (relatedEntity.getWorld().isClient()) {
			getAnimatableInstanceCache().getManagerForId(relatedEntity.getId()).setData(dataTicket, data);
		}
		else {
			StrutServices.NETWORK.syncEntityAnimData(relatedEntity, true, dataTicket, data);
		}
	}

	/**
	 * Trigger an animation for this Entity, based on the controller name and animation name
	 * <p>
	 * This can be fired from either the client or the server, but optimally you would call it from the server
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param relatedEntity An entity related to the state of the data for syncing
	 * @param controllerName The name of the controller name the animation belongs to, or null to do an inefficient lazy search
	 * @param animName The name of animation to trigger. This needs to have been registered with the controller via {@link AnimationController#triggerableAnim AnimationController.triggerableAnim}
	 */
	@ApiStatus.NonExtendable
	default void triggerAnim(Entity relatedEntity, @Nullable String controllerName, String animName) {
		if (relatedEntity.getWorld().isClient()) {
			if (controllerName != null) {
				getAnimatableInstanceCache().getManagerForId(relatedEntity.getId()).tryTriggerAnimation(controllerName, animName);
			}
			else {
				getAnimatableInstanceCache().getManagerForId(relatedEntity.getId()).tryTriggerAnimation(animName);
			}
		}
		else {
			StrutServices.NETWORK.triggerEntityAnim(relatedEntity, true, controllerName, animName);
		}
	}

	/**
	 * Stop a previously triggered animation for this Entity for the given controller name and animation name
	 * <p>
	 * This can be fired from either the client or the server, but optimally you would call it from the server
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param relatedEntity An entity related to the state of the data for syncing
	 * @param controllerName The name of the controller the animation belongs to, or null to do an inefficient lazy search
	 * @param animName The name of the triggered animation to stop, or null to stop any currently playing triggered animation
	 */
	@ApiStatus.NonExtendable
	default void stopTriggeredAnim(Entity relatedEntity, @Nullable String controllerName, @Nullable String animName) {
		if (relatedEntity.getWorld().isClient()) {
			AnimatableManager<StrutAnimatable> animatableManager = getAnimatableInstanceCache().getManagerForId(relatedEntity.getId());

			if (animatableManager == null)
				return;

			if (controllerName != null) {
				animatableManager.stopTriggeredAnimation(controllerName, animName);
			}
			else {
				animatableManager.stopTriggeredAnimation(animName);
			}
		}
		else {
			StrutServices.NETWORK.stopTriggeredEntityAnim(relatedEntity, true, controllerName, animName);
		}
	}
	
	/**
	 * {@code relatedEntity}'s bone overrides - the replacement for {@code StrutRenderLayer} (the old
	 * layer system itself was removed; this is what replaced it,
	 * See {@link EntityBoneOverrides}'s own doc comment for how it
	 * dispatches server-authoritative vs. client-local
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param relatedEntity The vanilla entity this replaces the rendering of
	 */
	@ApiStatus.NonExtendable
	default EntityBoneOverrides overrides(Entity relatedEntity) {
		return new EntityBoneOverrides(relatedEntity, true);
	}

	/**
	 * {@code relatedEntity}'s attachments - the replacement for {@code BlockAndItemStrutLayer}
	 * See {@link EntityAttachments}'s own doc comment
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param relatedEntity The vanilla entity this replaces the rendering of
	 */
	@ApiStatus.NonExtendable
	default EntityAttachments attachments(Entity relatedEntity) {
		return new EntityAttachments(relatedEntity, true);
	}

	/**
	 * Returns the current age/tick of the animatable instance
	 * <p>
	 * By default this is just the animatable's age in ticks, but this method allows for non-ticking custom animatables to provide their own values
	 *
	 * @param entity The Entity representing this animatable
	 * @return The current tick/age of the animatable, for animation purposes
	 */
	@Override
	default double getTick(Object entity) {
		return ((Entity)entity).age;
	}

	// These methods aren't used for StrutReplacedEntity
	@ApiStatus.NonExtendable
	@Override
	default void createStrutRenderer(Consumer<StrutRenderProvider> consumer) {}

	// These methods aren't used for StrutReplacedEntity
	@ApiStatus.NonExtendable
	@Override
	default Object getRenderProvider() {
		return null;
	}
}
