package com.sandymandy.strut.animation.override;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * One tracked entity's authoritative bone overrides, held server-side
 * <p>
 * Deliberately the override-shaped twin of
 * {@link com.sandymandy.strut.animation.authority.ServerAnimationState}: a key-keyed map, a
 * per-key sequence counter, and dirty tracking, nothing else. Folded into
 * {@code ServerAnimationStates}'s own per-entity {@code Entry} rather than a parallel registry, so
 * override replication rides the exact same start-tracking/tick/resync lifecycle animation
 * playback already does
 */
public final class ServerBoneOverrideState {
	private final Map<OverrideKey, BoneOverride> overrides = new Object2ObjectLinkedOpenHashMap<>();
	private final Object2LongOpenHashMap<OverrideKey> nextSequence = new Object2LongOpenHashMap<>();
	private final Set<OverrideKey> dirty = new ObjectLinkedOpenHashSet<>();

	/**
	 * Sets (or replaces) one bone's override for one property
	 */
	public void set(String bone, OverrideProperty property, OverrideValue value, OverrideScope scope,
					 int priority, float blendWeight, float transitionTicks) {
		OverrideKey key = new OverrideKey(bone, property);
		BoneOverride override = new BoneOverride(value, scope, priority, blendWeight, transitionTicks, nextSequenceFor(key));

		this.overrides.put(key, override);
		this.dirty.add(key);
	}

	/**
	 * Clears one bone's override for one property, if it had one
	 * <p>
	 * Still advances the key's sequence even though nothing is left to carry it - the same reason
	 * {@link com.sandymandy.strut.animation.authority.ServerAnimationState#stop} does: a delayed
	 * clear must not be able to resurrect an override a newer {@link #set} already replaced, and a
	 * delayed set must not be cancelled by an older clear that arrives after it
	 */
	public void clear(String bone, OverrideProperty property) {
		OverrideKey key = new OverrideKey(bone, property);

		if (this.overrides.remove(key) != null) {
			nextSequenceFor(key);
			this.dirty.add(key);
		}
	}

	/**
	 * This bone's current override for this property, or null if none is set
	 */
	@Nullable
	public BoneOverride get(String bone, OverrideProperty property) {
		return this.overrides.get(new OverrideKey(bone, property));
	}

	/**
	 * The most recent sequence issued for this key, including one issued by a {@link #clear} that
	 * left no {@link BoneOverride} to read it off of
	 */
	public long lastSequenceFor(String bone, OverrideProperty property) {
		return this.nextSequence.getLong(new OverrideKey(bone, property));
	}

	/**
	 * Every currently-set override, keyed by bone/property - what a full snapshot packet sends a
	 * client that has just started tracking this entity
	 */
	public Map<OverrideKey, BoneOverride> snapshot() {
		return this.overrides;
	}

	/**
	 * The keys that changed since the last call to this method, with their marks cleared
	 */
	public Set<OverrideKey> drainDirty() {
		Set<OverrideKey> drained = new ObjectLinkedOpenHashSet<>(this.dirty);

		this.dirty.clear();

		return drained;
	}

	private long nextSequenceFor(OverrideKey key) {
		return this.nextSequence.addTo(key, 1L) + 1L;
	}
}
