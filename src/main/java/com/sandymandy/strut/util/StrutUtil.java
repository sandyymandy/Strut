package com.sandymandy.strut.util;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.instance.AnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.InstancedAnimatableInstanceCache;
import com.sandymandy.strut.animatable.instance.SingletonAnimatableInstanceCache;
import com.sandymandy.strut.animation.Animation;
import com.sandymandy.strut.animation.EasingType;
import com.sandymandy.strut.constant.DataTickets;
import com.sandymandy.strut.constant.dataticket.DataTicket;
import com.sandymandy.strut.constant.dataticket.SerializableDataTicket;
import com.sandymandy.strut.loading.object.BakedModelFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Helper class for various Strut-specific functions.
 */
public final class StrutUtil {
	private static final IdentityHashMap<StrutAnimatable, String> ANIMATABLE_IDENTITIES = new IdentityHashMap<>();
	public static final Map<String, StrutAnimatable> SYNCED_ANIMATABLES = new Object2ObjectOpenHashMap<>();

	/**
	 * Creates a new AnimatableInstanceCache for the given animatable object
	 *
	 * @param animatable The animatable object
	 */
	public static AnimatableInstanceCache createInstanceCache(StrutAnimatable animatable) {
		AnimatableInstanceCache cache = animatable.animatableCacheOverride();

		return cache != null ? cache : createInstanceCache(animatable, !(animatable instanceof Entity) && !(animatable instanceof BlockEntity));
	}

	/**
	 * Creates a new AnimatableInstanceCache for the given animatable object
	 * <p>
	 * Recommended to use {@link StrutUtil#createInstanceCache(StrutAnimatable)} unless you know what you're doing
	 *
	 * @param animatable The animatable object
	 * @param singletonObject Whether the object is a singleton/flyweight object, and uses ints to differentiate animatable instances
	 */
	public static AnimatableInstanceCache createInstanceCache(StrutAnimatable animatable, boolean singletonObject) {
		AnimatableInstanceCache cache = animatable.animatableCacheOverride();

		if (cache != null)
			return cache;

		return singletonObject ? new SingletonAnimatableInstanceCache(animatable) : new InstancedAnimatableInstanceCache(animatable);
	}

	/**
	 * Register a custom {@link Animation.LoopType} with Strut, allowing for dynamic handling of post-animation looping
	 * <p>
	 * <b><u>MUST be called during mod construct</u></b>
	 *
	 * @param name The name of the {@code LoopType} handler
	 * @param loopType The {@code LoopType} implementation to use for the given name
	 */
	synchronized public static Animation.LoopType addCustomLoopType(String name, Animation.LoopType loopType) {
		return Animation.LoopType.register(name, loopType);
	}

	/**
	 * Register a custom {@link EasingType} with Strut allowing for dynamic handling of animation transitions and curves
	 * <p>
	 * <b><u>MUST be called during mod construct</u></b>
	 *
	 * @param name The name of the {@code EasingType} handler
	 * @param easingType The {@code EasingType} implementation to use for the given name
	 */
	synchronized public static EasingType addCustomEasingType(String name, EasingType easingType) {
		return EasingType.register(name, easingType);
	}

	/**
	 * Register a custom {@link BakedModelFactory} with Strut, allowing for dynamic handling of geo model loading
	 * <p>
	 * <b><u>MUST be called during mod construct</u></b>
	 *
	 * @param namespace The namespace (modid) to register the factory for
	 * @param factory The factory responsible for model loading under the given namespace
	 */
	synchronized public static void addCustomBakedModelFactory(String namespace, BakedModelFactory factory) {
		BakedModelFactory.register(namespace, factory);
	}

	/**
	 * Register a custom {@link SerializableDataTicket} with Strut for handling custom data transmission
	 * <p>
	 * NOTE: You do not need to register non-serializable {@link DataTicket DataTickets}.
	 * <p>
	 * <b><u>MUST be called during mod construct</u></b>
	 *
	 * @param dataTicket The SerializableDataTicket to register
	 * @return The dataTicket you passed in
	 */
	synchronized public static <D> SerializableDataTicket<D> addDataTicket(SerializableDataTicket<D> dataTicket) {
		return DataTickets.registerSerializable(dataTicket);
	}

	/**
	 * Registers a synced {@link StrutAnimatable} object for networking support
	 * <p>
	 * It is recommended that you don't call this directly, instead implementing and calling {@link com.sandymandy.strut.animatable.SingletonStrutAnimatable#registerSyncedAnimatable}
	 */
	synchronized public static void registerSyncedAnimatable(StrutAnimatable animatable) {
		synchronized (SYNCED_ANIMATABLES) {
			StrutAnimatable existing = SYNCED_ANIMATABLES.put(getSyncedSingletonAnimatableId(animatable), animatable);

			if (existing == null)
				StrutConstants.LOGGER.debug("Registered SyncedAnimatable for {}", animatable.getClass());
		}
	}

	/**
	 * Gets a registered synced {@link StrutAnimatable} object by name
	 * <p>
	 * The input string <b>MUST</b> be the {@link Class#getName()} of the animatable, passed through
	 * {@link #getSyncedSingletonAnimatableId(StrutAnimatable)}
	 *
	 * @param syncedAnimatableId the animatable id
	 */
	@Nullable
	public static StrutAnimatable getSyncedAnimatable(String syncedAnimatableId) {
		synchronized (SYNCED_ANIMATABLES) {
			StrutAnimatable animatable = SYNCED_ANIMATABLES.get(syncedAnimatableId);

			if (animatable == null)
				StrutConstants.LOGGER.error("Attempting to retrieve unregistered synced animatable! ({})", syncedAnimatableId);

			return animatable;
		}
	}

	/**
	 * Get a synced singleton animatable's id for use with {@link #SYNCED_ANIMATABLES}
	 * <p>
	 * This <b><u>MUST</u></b> be used when retrieving from {@link #SYNCED_ANIMATABLES}
	 * as this method eliminates class duplication collisions
	 */
	public static String getSyncedSingletonAnimatableId(StrutAnimatable animatable) {
		synchronized (SYNCED_ANIMATABLES) {
			return ANIMATABLE_IDENTITIES.computeIfAbsent(animatable, key -> {
				String baseId = animatable.getClass().getName();
				int i = 0;

				while (SYNCED_ANIMATABLES.containsKey(baseId + i)) {
					i++;
				}

				return baseId + i;
			});
		}
	}
}
