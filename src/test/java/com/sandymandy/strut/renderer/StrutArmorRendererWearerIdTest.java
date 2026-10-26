package com.sandymandy.strut.renderer;

import net.minecraft.entity.EquipmentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StrutArmorRenderer#getInstanceId} used to fall back to
 * {@code Math.pow(entity.getId(), 7) * -(slot.ordinal() + 1)} for a worn stack that hasn't been
 * assigned a real {@code StrutItem} id yet. {@code entity.getId()} is a numeric id Minecraft frees
 * and reuses once an entity is removed, and that id addresses a
 * {@link com.sandymandy.strut.animatable.instance.SingletonAnimatableInstanceCache} shared by
 * every wearer of the armor item - so a despawned wearer's slot could hand its animation state to
 * whichever new entity the server later reused that numeric id for.
 * <p>
 * {@link StrutArmorRenderer#deriveWearerInstanceId} replaced the numeric id with the wearer's UUID,
 * which is never reused. This proves that directly, without needing a real world-bound entity:
 * two different wearers - even ones a reused numeric id could have made indistinguishable - must
 * never derive the same instance id, and one wearer must always derive the same id it did before
 */
class StrutArmorRendererWearerIdTest {
	@Test
	@DisplayName("two different wearers in the same slot never derive the same instance id")
	void differentWearersDoNotCollide() {
		UUID firstWearer = UUID.randomUUID();
		UUID secondWearer = UUID.randomUUID();

		long firstId = StrutArmorRenderer.deriveWearerInstanceId(firstWearer, EquipmentSlot.CHEST);
		long secondId = StrutArmorRenderer.deriveWearerInstanceId(secondWearer, EquipmentSlot.CHEST);

		assertNotEquals(firstId, secondId,
				"two distinct wearers must never derive the same instance id, no matter what numeric entity id either was assigned");
	}

	@Test
	@DisplayName("the same wearer and slot always derive the same instance id")
	void sameWearerAndSlotIsStable() {
		UUID wearer = UUID.randomUUID();

		long first = StrutArmorRenderer.deriveWearerInstanceId(wearer, EquipmentSlot.HEAD);
		long second = StrutArmorRenderer.deriveWearerInstanceId(wearer, EquipmentSlot.HEAD);

		assertEquals(first, second, "deriving the id twice for the same wearer and slot must be stable, or repeated renders would each get a fresh manager");
	}

	@Test
	@DisplayName("one wearer's different equipment slots derive different instance ids")
	void differentSlotsForOneWearerDoNotCollide() {
		UUID wearer = UUID.randomUUID();

		long chestId = StrutArmorRenderer.deriveWearerInstanceId(wearer, EquipmentSlot.CHEST);
		long headId = StrutArmorRenderer.deriveWearerInstanceId(wearer, EquipmentSlot.HEAD);

		assertNotEquals(chestId, headId, "one wearer's chest and head armor must be able to animate independently, per this renderer's own paired-negative-id contract");
	}
}
