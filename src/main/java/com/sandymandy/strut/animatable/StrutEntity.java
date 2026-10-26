package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.StrutCacheHolder;
import com.sandymandy.strut.util.StrutUtil;
import com.sandymandy.strut.event.client.ClientEventContext;
import com.sandymandy.strut.event.server.EventContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;

/**
 * The {@link StrutAnimatable} interface specific to {@link net.minecraft.entity.Entity Entities}
 * <p>
 * This also applies to Projectiles and other Entity subclasses
 * <p>
 * <b>NOTE:</b> This <u>cannot</u> be used for entities using the {@link com.sandymandy.strut.renderer.StrutReplacedEntityRenderer}
 * as you aren't extending {@code Entity}. Use {@link StrutReplacedEntity} instead.
 *
 * @see "Strut documentation: doc/entities.html"
 */
public interface StrutEntity extends StrutAnimatable {
	/**
	 * Called on the <b>server</b>, exactly once, each time an animation event with server authority
	 * fires on this entity's clips - the place for this entity's own gameplay reaction to an event
	 * <p>
	 * Runs after the event type's own handler and before any {@code StrutEvents.onServer} listener.
	 * Anything done here to animation state (starting, stopping or seeking a clip) takes effect on the
	 * next sweep, so it cannot recurse into the dispatch that called it.
	 * <p>
	 * Does nothing by default
	 */
	default void onAnimationEvent(EventContext context) {}

	/**
	 * Called on each <b>client</b>, once, each time an animation event whose authority includes clients
	 * fires on this entity - the client counterpart of {@link #onAnimationEvent}
	 * <p>
	 * Fired from that client's own mirrored timeline, on the client tick, so it runs whether or not the
	 * entity is on screen. Runs after the event type's own client handler and before any
	 * {@code StrutEvents.onClient} listener. Does nothing by default
	 */
	default void onClientAnimationEvent(ClientEventContext context) {}

	/**
	 * The animation files this entity's clips come from, most preferred first - so the server, which has
	 * no model to ask, can find a clip's event timeline
	 * <p>
	 * Optional. With none declared the server looks a clip up by name across every data pack and mod,
	 * which works whenever that name is unique to one file. Declare files when two files reuse a clip
	 * name, or to fail loudly rather than fall back. Ids are the animation resource's, e.g.
	 * {@code mymod:strut/animations/entity/foo.strutanim}
	 */
	default List<Identifier> getAnimationFiles() {
		return List.of();
	}

	/**
	 * The event binding sets this entity uses, most specific first - what turns a clip's cue into <i>this
	 * character's</i> sound
	 * <p>
	 * A clip says "the {@code anal_startMSG1} cue happened"; the first set in this list that defines that key says
	 * what it means here. So one shared animation can carry a different voice per character, with no code. A
	 * typical chain is {@code [mymod:creatures/ava, mymod:creatures/default]}: the character's own lines, then the shared
	 * ones. Each id names a file at {@code assets/<namespace>/strut/event_bindings/<path>.json}
	 * <p>
	 * Empty by default, in which case sound-channel cues that are keys play nothing. See
	 * {@code com.sandymandy.strut.event.binding.EventBindings}
	 */
	default List<Identifier> getEventBindings() {
		return List.of();
	}

	/**
	 * Get server-synced animation data via its relevant {@link SerializableDataTicket}
	 * <p>
	 * Should only be used on the <u>client-side</u>
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param dataTicket The data ticket for the data to retrieve
	 * @return The synced data, or null if no data of that type has been synced
	 */
	@ApiStatus.NonExtendable
	@Nullable
	default <D> D getAnimData(SerializableDataTicket<D> dataTicket) {
		return getAnimatableInstanceCache().getManagerForId(((Entity)this).getId()).getData(dataTicket);
	}

	/**
	 * Saves an arbitrary syncable piece of data to this animatable's {@link AnimatableManager}
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param dataTicket The DataTicket to sync the data for
	 * @param data The data to sync
	 */
	@ApiStatus.NonExtendable
	default <D> void setAnimData(SerializableDataTicket<D> dataTicket, D data) {
		Entity entity = (Entity)this;

		if (entity.getEntityWorld().isClient()) {
			getAnimatableInstanceCache().getManagerForId(entity.getId()).setData(dataTicket, data);
		}
		else {
			StrutServices.NETWORK.syncEntityAnimData(entity, false, dataTicket, data);
		}
	}

	/**
	 * Trigger an animation for this Entity, based on the controller name and animation name
	 * <p>
	 * This can be fired from either the client or the server, but optimally you would call it from the server
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param controllerName The name of the controller name the animation belongs to, or null to do an inefficient lazy search
	 * @param animName The name of animation to trigger. This needs to have been registered with the controller via {@link AnimationController#triggerableAnim AnimationController.triggerableAnim}
	 */
	@ApiStatus.NonExtendable
	default void triggerAnim(@Nullable String controllerName, String animName) {
		Entity entity = (Entity)this;

		if (entity.getWorld().isClient()) {
			if (controllerName != null) {
				getAnimatableInstanceCache().getManagerForId(entity.getId()).tryTriggerAnimation(controllerName, animName);
			}
			else {
				getAnimatableInstanceCache().getManagerForId(entity.getId()).tryTriggerAnimation(animName);
			}
		}
		else {
			StrutServices.NETWORK.triggerEntityAnim(entity, false, controllerName, animName);
		}
	}

	/**
	 * Stop a previously triggered animation for this Entity for the given controller name and animation name
	 * <p>
	 * This can be fired from either the client or the server, but optimally you would call it from the server
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param controllerName The name of the controller the animation belongs to, or null to do an inefficient lazy search
	 * @param animName The name of the triggered animation to stop, or null to stop any currently playing triggered animation
	 */
	@ApiStatus.NonExtendable
	default void stopTriggeredAnim(@Nullable String controllerName, @Nullable String animName) {
		Entity entity = (Entity)this;

		if (entity.getWorld().isClient()) {
			AnimatableManager<StrutAnimatable> animatableManager = getAnimatableInstanceCache().getManagerForId(entity.getId());

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
			StrutServices.NETWORK.stopTriggeredEntityAnim(entity, false, controllerName, animName);
		}
	}
	
	/**
	 * This entity's bone overrides - the replacement for {@code StrutRenderLayer} (the old layer
	 * system itself was removed; this is what replaced it,
	 * See {@link EntityBoneOverrides}'s own doc comment for how it
	 * dispatches server-authoritative vs. client-local
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 */
	@ApiStatus.NonExtendable
	default EntityBoneOverrides overrides() {
		return new EntityBoneOverrides((Entity)this, false);
	}

	/**
	 * This entity's attachments - the replacement for {@code BlockAndItemStrutLayer}
	 * See {@link EntityAttachments}'s own doc comment
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 */
	@ApiStatus.NonExtendable
	default EntityAttachments attachments() {
		return new EntityAttachments((Entity)this, false);
	}

	/**
	 * This entity's instance cache, held in a slot Strut's mixin adds to {@code Entity}, so an entity declares no cache field
	 * <p>
	 * Created on first use. A class that overrides this with its own cache still works exactly as before
	 */
	@Override
	default AnimatableInstanceCache getAnimatableInstanceCache() {
		StrutCacheHolder holder = (StrutCacheHolder)this;
		AnimatableInstanceCache cache = holder.strut$getCache();

		if (cache == null)
			holder.strut$setCache(cache = StrutUtil.createInstanceCache(this));

		return cache;
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
}
