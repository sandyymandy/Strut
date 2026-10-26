package com.sandymandy.strut.animatable;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.util.StrutUtil;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.minecraft.registry.Registries;

/**
 * Registers every {@link StrutItem} for animation syncing as it is added to the item registry
 * <p>
 * An item's animation triggers and data reach clients by an id derived from the item class and the <i>order</i> the items were
 * registered in, so the id is the same on both sides only if registration happens in the same order everywhere. Doing it from
 * the item registry's own entry callback gives exactly that, with no call in the item's constructor. Calling
 * {@code StrutItem.registerSyncedAnimatable} yourself is still harmless: registering the same item twice changes nothing
 */
public final class StrutSyncedItems {
	private StrutSyncedItems() {}

	/**
	 * Called once from {@link com.sandymandy.strut.Strut#onInitialize()}
	 */
	public static void init() {
		// The callback also fires for entries already registered when it is added, so the order does not matter to the caller
		RegistryEntryAddedCallback.event(Registries.ITEM).register((rawId, id, item) -> {
			if (item instanceof StrutItem strutItem) {
				StrutUtil.registerSyncedAnimatable(strutItem);
				StrutConstants.LOGGER.debug("[Strut] registered synced item {}", id);
			}
		});
	}
}
