package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.StrutCacheHolder;
import com.sandymandy.strut.util.StrutUtil;
import net.minecraft.component.ComponentMapImpl;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.instance.SingletonAnimatableInstanceCache;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.animation.ContextAwareAnimatableManager;
import com.sandymandy.strut.cache.AnimatableIdCache;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.util.RenderUtil;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link StrutAnimatable StrutAnimatable} interface specific to {@code Items}
 * <p>
 * This also applies to armor, as they are just items too.
 *
 * @see "Strut documentation: doc/items-and-blocks.html"
 * @see "Strut documentation: doc/items-and-blocks.html (Armor)"
 */
public interface StrutItem extends SingletonStrutAnimatable {
	/**
	 * Register this as a synched {@code StrutAnimatable} instance with Strut's networking functions
	 * <p>
	 * This should be called inside the constructor of your object.
	 */
	static void registerSyncedAnimatable(StrutAnimatable animatable) {
		SingletonStrutAnimatable.registerSyncedAnimatable(animatable);
	}

	/**
	 * Gets the unique identifying number from this ItemStack's {@link NbtElement NBT},
	 * or {@link Long#MAX_VALUE} if one hasn't been assigned
	 */
	static long getId(ItemStack stack) {
		return Optional.ofNullable(stack.getComponentChanges().get(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get()))
				.filter(Optional::isPresent)
				.<Long>map(Optional::get)
				.orElse(Long.MAX_VALUE);
	}

	/**
	 * Gets the unique identifying number from this ItemStack's {@link NbtElement NBT}
	 * <p>
	 * If no ID has been reserved for this stack yet, it will reserve a new id and assign it
	 */
	static long getOrAssignId(ItemStack stack, ServerWorld level) {
		if (!(stack.getComponents() instanceof ComponentMapImpl components))
			return Long.MAX_VALUE;

		Long id = components.get(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get());

		if (id == null)
			components.set(StrutConstants.STACK_ANIMATABLE_ID_COMPONENT.get(), id = AnimatableIdCache.getFreeId(level));

		return id;
	}
	
	/**
	 * This item's instance cache, held in a slot Strut's mixin adds to {@code Item}, so a class declares no cache field
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
	 * @param itemStack The ItemStack representing this animatable
	 * @return The current tick/age of the animatable, for animation purposes
	 */
	@Override
	default double getTick(Object itemStack) {
		return RenderUtil.getCurrentTick();
	}

    /**
     * Whether this item animatable is perspective-aware, handling animations differently depending on the {@link ModelTransformationMode render perspective}
     */
	default boolean isPerspectiveAware() {
		return false;
	}

	/**
	 * Replaces the default AnimatableInstanceCache for StrutItems if {@link StrutItem#isPerspectiveAware()} is true, for perspective-dependent handling
	 */
	@Nullable
	@Override
	default AnimatableInstanceCache animatableCacheOverride() {
		if (isPerspectiveAware())
			return new ContextBasedAnimatableInstanceCache(this);

		return SingletonStrutAnimatable.super.animatableCacheOverride();
	}

	/**
	 * AnimatableInstanceCache specific to StrutItems, for doing render perspective based animations
	 * <p>
	 * You should <b><u>NOT</u></b> be instantiating this directly unless you know what you are doing.
	 * Use {@link com.sandymandy.strut.util.StrutUtil#createInstanceCache StrutUtil.createInstanceCache} instead
	 */
	class ContextBasedAnimatableInstanceCache extends SingletonAnimatableInstanceCache {
		public ContextBasedAnimatableInstanceCache(StrutAnimatable animatable) {
			super(animatable);
		}

		/**
		 * Gets an {@link AnimatableManager} instance from this cache, cached under the id provided, or a new one if one doesn't already exist
		 * <p>
		 * This subclass assumes that all animatable instances will be sharing this cache instance, and so differentiates data by ids
		 */
		@Override
		public AnimatableManager<?> getManagerForId(long uniqueId) {
			return computeIfAbsent(uniqueId, () -> new ContextAwareAnimatableManager<StrutItem, ModelTransformationMode>(this.animatable) {
				@Override
				protected Map<ModelTransformationMode, AnimatableManager<StrutItem>> buildContextOptions(StrutAnimatable animatable) {
					Map<ModelTransformationMode, AnimatableManager<StrutItem>> map = new EnumMap<>(ModelTransformationMode.class);

					for (ModelTransformationMode context : ModelTransformationMode.values()) {
						map.put(context, new AnimatableManager<>(animatable));
					}

					return map;
				}

				@Override
				public ModelTransformationMode getCurrentContext() {
					ModelTransformationMode context = getData(DataTickets.ITEM_RENDER_PERSPECTIVE);

					return context == null ? ModelTransformationMode.NONE : context;
				}
			});
		}
	}
}
