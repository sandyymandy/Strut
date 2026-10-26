package com.sandymandy.strut.animatable.instance;

import com.google.common.base.Suppliers;
import org.apache.commons.lang3.mutable.MutableObject;
import com.sandymandy.strut.StrutServices;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animatable.SingletonStrutAnimatable;
import com.sandymandy.strut.animatable.client.StrutRenderProvider;
import com.sandymandy.strut.animation.AnimatableManager;
import com.sandymandy.strut.constant.dataticket.DataTicket;

import java.util.function.Supplier;

/**
 * The base cache class responsible for returning the {@link AnimatableManager} for a given instanceof of a {@link StrutAnimatable}
 * <p>
 * This class is abstracted and not intended for direct use. See either {@link SingletonAnimatableInstanceCache} or {@link InstancedAnimatableInstanceCache}
 */
public abstract class AnimatableInstanceCache {
	protected final StrutAnimatable animatable;
	protected final Supplier<StrutRenderProvider> renderProvider;

	public AnimatableInstanceCache(StrutAnimatable animatable) {
		this.animatable = animatable;
		this.renderProvider = Suppliers.memoize(() -> {
			if (!(this.animatable instanceof SingletonStrutAnimatable singleton) || !StrutServices.PLATFORM.isPhysicalClient())
				return null;

			final MutableObject<StrutRenderProvider> consumer = new MutableObject<>(StrutRenderProvider.DEFAULT);

			singleton.createStrutRenderer(consumer::setValue);

			return consumer.getValue();
		});
	}

	/**
	 * This creates or gets the cached animatable manager for any unique ID
	 * <p>
	 * For itemstacks, this is typically a reserved ID provided by Strut. {@code Entities} and {@code BlockEntities}
	 * pass their position or int ID. They typically only have one {@link AnimatableManager} per cache anyway
	 *
	 * @param uniqueId A unique ID. For every ID the same animation manager
	 *                 will be returned.
	 */
	public abstract <T extends StrutAnimatable> AnimatableManager<T> getManagerForId(long uniqueId);

	/**
	 * Helper method to set a data point in the {@link AnimatableManager#setData manager} for this animatable
	 *
	 * @param uniqueId The unique identifier for this animatable instance
	 * @param dataTicket The DataTicket for the data
	 * @param data The data to store
	 */
	public <D> void addDataPoint(long uniqueId, DataTicket<D> dataTicket, D data) {
		getManagerForId(uniqueId).setData(dataTicket, data);
	}

	/**
	 * Helper method to get a data point from the {@link AnimatableManager#getData data collection} for this animatable
	 *
	 * @param uniqueId The unique identifier for this animatable instance
	 * @param dataTicket The DataTicket for the data
	 */
	public <D> D getDataPoint(long uniqueId, DataTicket<D> dataTicket) {
		return getManagerForId(uniqueId).getData(dataTicket);
	}

	/**
	 * Get the {@link StrutRenderProvider} for this animatable
	 * <p>
	 * Because only {@link SingletonStrutAnimatable}s use this functionality, it this method should not be used and will always return null for anything other than a SingletonStrutAnimatable
	 * <p>
	 * The returned object is upcast to Object for side-safety
	 *
	 * @return The cached StrutRenderProvider instance for this animatable, or null if one does not exist
	 */
	public Object getRenderProvider() {
		return this.renderProvider.get();
	}
}
