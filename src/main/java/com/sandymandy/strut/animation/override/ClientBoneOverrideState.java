package com.sandymandy.strut.animation.override;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * One tracked entity's bone overrides, held client-side - two independent stores rather than one
 * <p>
 * {@code synced} is a plain mirror of the server's {@link ServerBoneOverrideState}, applied the
 * same delayed-packet-safe way {@link com.sandymandy.strut.animation.authority.ClientAnimationState}
 * mirrors {@code AnimationPlayback}. {@code local} is this client's own, set only by client-side
 * calls to {@code EntityBoneOverrides}, never sent anywhere - the store the first-person-own-head
 * rule lives in, and the reason this class exists
 * separately from {@code ClientAnimationState} rather than folded into it: animation playback has
 * no client-local half to mirror alongside the server's
 * <p>
 * {@link #resolve} is where the two meet: <b>local beats synced</b>, by design
 * - a client override is the more specific context, so it wins ties, and
 * only loses to a synced override of strictly higher priority. Landed as tested, freestanding
 * infrastructure that
 * {@code ModelInstance#effectivePose()} calls.
 */
public final class ClientBoneOverrideState {
	private final Map<OverrideKey, BoneOverride> synced = new Object2ObjectOpenHashMap<>();
	private final Object2LongOpenHashMap<OverrideKey> lastSyncedSequence = new Object2LongOpenHashMap<>();
	private final Map<OverrideKey, BoneOverride> local = new Object2ObjectOpenHashMap<>();

	/**
	 * Replaces every synced override wholesale, from a full snapshot - see
	 * {@link com.sandymandy.strut.animation.authority.ClientAnimationState#applySnapshot}'s own
	 * doc comment for why clearing first is what makes "the server stopped overriding this, and the
	 * snapshot simply does not mention it any more" fall out correctly rather than needing to be
	 * handled as a special case. Never touches {@link #local}
	 */
	public void applySnapshot(Map<OverrideKey, BoneOverride> snapshot) {
		this.synced.clear();
		this.lastSyncedSequence.clear();

		for (Map.Entry<OverrideKey, BoneOverride> entry : snapshot.entrySet()) {
			this.synced.put(entry.getKey(), entry.getValue());
			this.lastSyncedSequence.put(entry.getKey(), entry.getValue().sequence());
		}
	}

	/**
	 * Applies one key's synced change, unless a delayed packet has arrived after a newer one already
	 * did - see {@link com.sandymandy.strut.animation.authority.ClientAnimationState#applyDelta}'s
	 * own doc comment; this is the identical guard, applied to {@link #synced} rather than an
	 * animation playback map. {@code override} null means the server cleared it
	 *
	 * @return Whether the delta was applied. {@code false} means it was stale and correctly ignored
	 */
	public boolean applyDelta(OverrideKey key, long sequence, @Nullable BoneOverride override) {
		if (sequence <= this.lastSyncedSequence.getLong(key))
			return false;

		if (override == null) {
			this.synced.remove(key);
		}
		else {
			this.synced.put(key, override);
		}

		this.lastSyncedSequence.put(key, sequence);

		return true;
	}

	/**
	 * Sets (or replaces) this client's own local override for one bone/property - never sent
	 * anywhere, see this class's own doc comment
	 */
	public void setLocal(String bone, OverrideProperty property, OverrideValue value, OverrideScope scope,
						  int priority, float blendWeight, float transitionTicks) {
		// A local override never needs sequence-guarding - nothing races it over the network, so a
		// simple 0 is enough. Only synced entries spend BoneOverride#sequence for anything
		OverrideKey key = new OverrideKey(bone, property);
		BoneOverride next = new BoneOverride(value, scope, priority, blendWeight, transitionTicks, 0);

		// Idempotent: client code sets a per-viewer skin or colour every frame, and an unchanged value must not churn the map
		if (!next.equals(this.local.get(key)))
			this.local.put(key, next);
	}

	/**
	 * Clears this client's own local override for one bone/property, if it had one
	 */
	public void clearLocal(String bone, OverrideProperty property) {
		this.local.remove(new OverrideKey(bone, property));
	}

	/**
	 * Drops every {@link OverrideScope#FRAME}-scoped local override - the per-frame sweep that
	 * calls once per client frame, after render, so a {@code FRAME} override set this frame is gone
	 * by the next one unless something sets it again
	 */
	public void clearFrameScopedLocal() {
		this.local.values().removeIf(override -> override.scope() == OverrideScope.FRAME);
	}

	/**
	 * Every key that has a value in either store - the union {@code ModelInstance#effectivePose()}
	 * walks to know which bones actually need resolving, rather than checking every
	 * bone in a model against two maps every frame
	 */
	public Set<OverrideKey> activeKeys() {
		if (this.local.isEmpty())
			return this.synced.keySet();

		if (this.synced.isEmpty())
			return this.local.keySet();

		Set<OverrideKey> union = new ObjectOpenHashSet<>(this.synced.keySet());

		union.addAll(this.local.keySet());

		return union;
	}

	/**
	 * This bone/property's effective override - whichever of {@link #local}/{@link #synced} should
	 * win, or null if neither has one set
	 * <p>
	 * <b>Local beats synced on a tie</b>; otherwise strictly higher {@link BoneOverride#priority()}
	 * wins. A caller wanting the server to be un-overridable by any client override at all should
	 * set the server-side override at a priority strictly above whatever a client override might use
	 * - this method enforces the tie-break, not an unconditional "local always wins"
	 */
	@Nullable
	public BoneOverride resolve(String bone, OverrideProperty property) {
		OverrideKey key = new OverrideKey(bone, property);
		BoneOverride localValue = this.local.get(key);
		BoneOverride syncedValue = this.synced.get(key);

		if (localValue == null)
			return syncedValue;

		if (syncedValue == null)
			return localValue;

		return localValue.priority() >= syncedValue.priority() ? localValue : syncedValue;
	}
}
