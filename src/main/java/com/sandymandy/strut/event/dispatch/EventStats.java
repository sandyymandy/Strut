package com.sandymandy.strut.event.dispatch;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Running totals of what the event system has done, for the F9 overlay and {@code /strut events stats}
 * <p>
 * Exists because "did it actually run?" is the question every live test starts with, and a working event
 * and one that silently never fired look identical from the outside - the same lesson the Iris path taught
 * (a green run that never executed the code proves nothing). These counters are cumulative since the game
 * started; compare a value before and after to see what one action did
 */
public final class EventStats {
	private static final AtomicLong SERVER_DELIVERED = new AtomicLong();
	private static final AtomicLong CLIENT_DELIVERED = new AtomicLong();
	private static final AtomicLong TRUNCATED_SWEEPS = new AtomicLong();
	private static final AtomicLong UNBOUND_KEYS = new AtomicLong();

	private EventStats() {}

	public static void recordServerDelivered() {
		SERVER_DELIVERED.incrementAndGet();
	}

	public static void recordClientDelivered() {
		CLIENT_DELIVERED.incrementAndGet();
	}

	/** A sweep crossed more events than the cap allowed and dropped the rest */
	public static void recordTruncatedSweep() {
		TRUNCATED_SWEEPS.incrementAndGet();
	}

	/** A sound key fired that no binding set in the entity's chain defines */
	public static void recordUnboundKey() {
		UNBOUND_KEYS.incrementAndGet();
	}

	public static long serverDelivered() {
		return SERVER_DELIVERED.get();
	}

	public static long clientDelivered() {
		return CLIENT_DELIVERED.get();
	}

	public static long truncatedSweeps() {
		return TRUNCATED_SWEEPS.get();
	}

	public static long unboundKeys() {
		return UNBOUND_KEYS.get();
	}
}
