package com.sandymandy.strut.animation;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Addresses one bone by what kind of bone it is and its name
 * <p>
 * A group bone and an armature bone may legitimately share a name - {@link TargetKind} is what
 * disambiguates them, the same reason it exists on a binary animation channel (see
 * Everywhere {@link AnimationProcessor} and
 * {@link AnimationController} used to key a map by bare bone name, they now key it by
 * {@code BoneKey} instead, so two same-named bones of different kinds never alias each other's
 * handle, snapshot or animation queue
 *
 * <h2>Unqualified sources</h2>
 *
 * <p>Every animation source that exists today - every {@code .animation.json}, and every
 * {@code .strutanim} until bone kinds are stored in it - names a bone with a bare {@code String}
 * and no kind. {@link #resolveUnqualified} is how such a name becomes a key: it checks
 * {@link TargetKind#GROUP_BONE} first and falls back to {@link TargetKind#ARMATURE_BONE}, which is
 * exactly today's behaviour for every existing asset, since a model with a group bone and an
 * armature bone sharing a name is rare and the group bone winning unqualified resolution keeps the
 * common case unsurprising
 */
public final class BoneKey {
	// one key per kind and name, so the maps an animation runs through every frame find a key by identity rather than by comparing names
	private static final ConcurrentHashMap<String, BoneKey> GROUP_KEYS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, BoneKey> ARMATURE_KEYS = new ConcurrentHashMap<>();

	private final TargetKind kind;
	private final String name;
	private final int hash;

	private BoneKey(TargetKind kind, String name) {
		this.kind = kind;
		this.name = name;
		this.hash = 31 * kind.hashCode() + name.hashCode();
	}

	/** The key for a bone of {@code kind} named {@code name}: always the same instance for the same two */
	public static BoneKey of(TargetKind kind, String name) {
		ConcurrentHashMap<String, BoneKey> keys = kind == TargetKind.ARMATURE_BONE ? ARMATURE_KEYS : GROUP_KEYS;
		BoneKey key = keys.get(name);

		// looked up first: the key nearly always exists, and making the function that would add it costs an allocation every call
		return key != null ? key : keys.computeIfAbsent(name, n -> new BoneKey(kind, n));
	}

	public static BoneKey group(String name) {
		return of(TargetKind.GROUP_BONE, name);
	}

	public static BoneKey armature(String name) {
		return of(TargetKind.ARMATURE_BONE, name);
	}

	public TargetKind kind() {
		return this.kind;
	}

	public String name() {
		return this.name;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;

		return other instanceof BoneKey key && key.hash == this.hash && key.kind == this.kind && key.name.equals(this.name);
	}

	@Override
	public int hashCode() {
		return this.hash;
	}

	@Override
	public String toString() {
		return "BoneKey[kind=" + this.kind + ", name=" + this.name + "]";
	}

	/**
	 * Resolves a bone name against {@code registered}, honouring {@code kind} where the source
	 * supplied one
	 * <p>
	 * A {@code .strutanim} channel says which kind of bone it drives, so it binds to exactly that
	 * kind or to nothing - falling back to the other kind would defeat the point of the field. A
	 * {@code .animation.json} bone animation says nothing, so it resolves
	 * {@link #resolveUnqualified unqualified} instead
	 *
	 * @param kind The kind the source named, or null if it did not
	 * @return The matching registered key, or {@code null} if there is none
	 */
	public static BoneKey resolve(Map<BoneKey, ?> registered, String name, @Nullable TargetKind kind) {
		if (kind == null)
			return resolveUnqualified(registered, name);

		BoneKey key = of(kind, name);

		return registered.containsKey(key) ? key : null;
	}

	/**
	 * Resolves a bare, unqualified bone name to whichever key is actually present in
	 * {@code registered} - {@link TargetKind#GROUP_BONE} checked first, {@link TargetKind#ARMATURE_BONE}
	 * as the fallback - or {@code null} if neither is registered
	 */
	public static BoneKey resolveUnqualified(Map<BoneKey, ?> registered, String name) {
		BoneKey groupKey = group(name);

		if (registered.containsKey(groupKey))
			return groupKey;

		BoneKey armatureKey = armature(name);

		if (registered.containsKey(armatureKey))
			return armatureKey;

		return null;
	}
}
