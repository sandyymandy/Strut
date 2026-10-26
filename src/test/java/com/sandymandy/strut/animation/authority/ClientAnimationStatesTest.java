package com.sandymandy.strut.animation.authority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ServerAnimationStates} has no test of its own - it needs a live {@code Entity} and
 * {@code World} to be exercised at all, per that class's own doc comment. This registry needs
 * neither, just an entity id, so unlike its server-side counterpart it is directly testable
 */
class ClientAnimationStatesTest {
	@Test
	@DisplayName("forEntity creates state on first use and returns the same instance on later calls")
	void forEntityCreatesAndReuses() {
		ClientAnimationState first = ClientAnimationStates.forEntity(1001);
		ClientAnimationState second = ClientAnimationStates.forEntity(1001);

		assertSame(first, second);
	}

	@Test
	@DisplayName("different entity ids get independent state")
	void differentEntitiesGetIndependentState() {
		ClientAnimationState a = ClientAnimationStates.forEntity(2001);
		ClientAnimationState b = ClientAnimationStates.forEntity(2002);

		assertNotSame(a, b);
	}

	@Test
	@DisplayName("get returns null for an entity id nothing has ever touched")
	void getReturnsNullForUntouchedEntity() {
		assertNull(ClientAnimationStates.get(999_999));
	}

	@Test
	@DisplayName("get returns the same instance forEntity would create")
	void getMatchesForEntity() {
		ClientAnimationState created = ClientAnimationStates.forEntity(3001);

		assertSame(created, ClientAnimationStates.get(3001));
	}

	@Test
	@DisplayName("remove drops an entity's state, so a later forEntity creates a fresh one")
	void removeDropsState() {
		ClientAnimationState original = ClientAnimationStates.forEntity(4001);

		ClientAnimationStates.remove(4001);

		assertNull(ClientAnimationStates.get(4001));
		assertNotSame(original, ClientAnimationStates.forEntity(4001));
	}
}
