package com.sandymandy.strut.animation;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats.TargetKind;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

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
public record BoneKey(TargetKind kind, String name) {
	public static BoneKey group(String name) {
		return new BoneKey(TargetKind.GROUP_BONE, name);
	}

	public static BoneKey armature(String name) {
		return new BoneKey(TargetKind.ARMATURE_BONE, name);
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

		BoneKey key = new BoneKey(kind, name);

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
