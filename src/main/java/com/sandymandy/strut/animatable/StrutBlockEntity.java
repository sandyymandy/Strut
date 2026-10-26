package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.StrutCacheHolder;
import com.sandymandy.strut.util.StrutUtil;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.util.RenderUtil;

/**
 * The {@link StrutAnimatable} interface specific to {@link BlockEntity BlockEntities}
 *
 * @see "Strut documentation: doc/items-and-blocks.html"
 */
public interface StrutBlockEntity extends StrutAnimatable {
	/**
	 * This block entity's bone overrides: hide, move, scale, tint or re-texture a bone of its model, as {@code entity.overrides()} does on an entity. See
	 * {@link BlockEntityBoneOverrides} for what a call on the server and on a client does
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 */
	@ApiStatus.NonExtendable
	default BlockEntityBoneOverrides overrides() {
		return new BlockEntityBoneOverrides((BlockEntity)this);
	}

	/**
	 * Get server-synced animation data via its relevant {@link SerializableDataTicket}.
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
		return getAnimatableInstanceCache().getManagerForId(0).getData(dataTicket);
	}

	/**
	 * Saves an arbitrary piece of data to this animatable's {@link AnimatableManager}
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param dataTicket The DataTicket to sync the data for
	 * @param data The data to sync
	 */
	@ApiStatus.NonExtendable
	default <D> void setAnimData(SerializableDataTicket<D> dataTicket, D data) {
		BlockEntity blockEntity = (BlockEntity)this;
		World world = blockEntity.getWorld();

		if (world == null) {
			StrutConstants.LOGGER.error("Attempting to set animation data for BlockEntity too early! Must wait until after the BlockEntity has been set in the world. (" + blockEntity.getClass().toString() + ")");

			return;
		}

		if (world.isClient()) {
			getAnimatableInstanceCache().getManagerForId(0).setData(dataTicket, data);
		}
		else {
			StrutServices.NETWORK.syncBlockEntityAnimData(blockEntity.getPos(), dataTicket, data, (ServerWorld)world);
		}
	}

	/**
	 * Trigger an animation for this BlockEntity, based on the controller name and animation name
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
		BlockEntity blockEntity = (BlockEntity)this;
		World world = blockEntity.getWorld();

		if (world == null) {
			StrutConstants.LOGGER.error("Attempting to trigger an animation for a BlockEntity too early! Must wait until after the BlockEntity has been set in the world. (" + blockEntity.getClass().toString() + ")");

			return;
		}

		if (world.isClient()) {
			if (controllerName != null) {
				getAnimatableInstanceCache().getManagerForId(0).tryTriggerAnimation(controllerName, animName);
			}
			else {
				getAnimatableInstanceCache().getManagerForId(0).tryTriggerAnimation(animName);
			}
		}
		else {
			StrutServices.NETWORK.triggerBlockEntityAnim(blockEntity.getPos(), controllerName, animName, (ServerWorld)world);
		}
	}

	/**
	 * Stop a previously triggered animation for this BlockEntity for the given controller name and animation name
	 * <p>
	 * This can be fired from either the client or the server, but optimally you would call it from the server
	 * <p>
	 * <b><u>DO NOT OVERRIDE</u></b>
	 *
	 * @param controllerName The name of the controller name the animation belongs to, or null to do an inefficient lazy search
	 * @param animName The name of the triggered animation to stop, or null to stop any currently playing triggered animation
	 */
	@ApiStatus.NonExtendable
	default void stopTriggeredAnim(@Nullable String controllerName, @Nullable String animName) {
		BlockEntity blockEntity = (BlockEntity)this;
		World world = blockEntity.getWorld();

		if (world == null) {
			StrutConstants.LOGGER.error("Attempting to stop a triggered animation for a BlockEntity too early! Must wait until after the BlockEntity has been set in the world. (" + blockEntity.getClass().toString() + ")");

			return;
		}

		if (world.isClient()) {
			AnimatableManager<StrutAnimatable> animatableManager = getAnimatableInstanceCache().getManagerForId(0);

			if (controllerName != null) {
				animatableManager.stopTriggeredAnimation(controllerName, animName);
			}
			else {
				animatableManager.stopTriggeredAnimation(animName);
			}
		}
		else {
			StrutServices.NETWORK.stopTriggeredBlockEntityAnim(blockEntity.getPos(), (ServerWorld)world, controllerName, animName);
		}
	}

	/**
	 * Returns the current age/tick of the animatable instance
	 * <p>
	 * By default this is just the animatable's age in ticks, but this method allows for non-ticking custom animatables to provide their own values
	 *
	 * @param blockEntity The BlockEntity representing this animatable
	 * @return The current tick/age of the animatable, for animation purposes
	 */
	/**
	 * This block entity's instance cache, held in a slot Strut's mixin adds to {@code BlockEntity}, so a class declares no cache field
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

	@Override
	default double getTick(Object blockEntity) {
		return RenderUtil.getCurrentTick();
	}
}
