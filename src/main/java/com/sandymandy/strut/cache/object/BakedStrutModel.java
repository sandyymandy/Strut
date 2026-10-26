package com.sandymandy.strut.cache.object;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.sandymandy.strut.loading.json.raw.ModelProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Baked model object for Strut models
 * <p>
 * {@code flatBones} is derived from {@code topLevelBones}, not authored data - it exists so a
 * bone can be addressed by index as well as by name. See {@link GroupBoneDefinition#getIndex()} and {@link #flatBones()}
 */
public record BakedStrutModel(List<GroupBoneDefinition> topLevelBones, List<StrutArmature> armatures, ModelProperties properties, GroupBoneDefinition[] flatBones,
							 Map<String, byte[]> extensions) {
	/**
	 * The constructor every caller actually uses. Flattens {@code topLevelBones} into
	 * parent-before-child order and assigns each bone its {@link GroupBoneDefinition#getIndex() index} as a
	 * side effect - once, here, because this is the one place the whole tree is known to be
	 * complete
	 */
	public BakedStrutModel(List<GroupBoneDefinition> topLevelBones, List<StrutArmature> armatures, ModelProperties properties) {
		this(topLevelBones, armatures, properties, flatten(topLevelBones), Map.of());
	}

	/**
	 * A model carrying extension sections its file held (see {@link com.sandymandy.strut.loading.binary.StrutBinaryFormats#MODEL_FLAG_EXTENSIONS}): opaque
	 * bytes keyed by a namespaced name, for a module to parse. Core never reads them
	 */
	public BakedStrutModel(List<GroupBoneDefinition> topLevelBones, List<StrutArmature> armatures, ModelProperties properties, Map<String, byte[]> extensions) {
		this(topLevelBones, armatures, properties, flatten(topLevelBones), extensions);
	}

	/**
	 * The bytes of the extension section called {@code name} (for example {@code strut_physics:physics}), if this model's file carried one
	 */
	public Optional<byte[]> extension(String name) {
		byte[] bytes = this.extensions.get(name);

		return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
	}

	/**
	 * A model with no armatures, which is every model loaded from {@code .geo.json}
	 * <p>
	 * Kept so that existing {@link com.sandymandy.strut.loading.object.BakedModelFactory}
	 * implementations, including third-party ones, continue to compile and behave unchanged
	 */
	public BakedStrutModel(List<GroupBoneDefinition> topLevelBones, ModelProperties properties) {
		this(topLevelBones, List.of(), properties);
	}

	/**
	 * Walk the tree in pre-order - each bone before its children, top-level bones in their given
	 * order - and number the bones as it goes
	 * <p>
	 * Pre-order guarantees a parent's index is always lower than any of its descendants', which
	 * is what lets {@link com.sandymandy.strut.animation.state.SkinningState}-style per-instance
	 * pose storage pose a whole model in one forward pass with no recursion
	 */
	private static GroupBoneDefinition[] flatten(List<GroupBoneDefinition> topLevelBones) {
		List<GroupBoneDefinition> flat = new ObjectArrayList<>();

		for (GroupBoneDefinition bone : topLevelBones) {
			flattenInto(bone, flat);
		}

		GroupBoneDefinition[] flatBones = flat.toArray(new GroupBoneDefinition[0]);

		for (int i = 0; i < flatBones.length; i++) {
			flatBones[i].setIndex(i);
		}

		return flatBones;
	}

	private static void flattenInto(GroupBoneDefinition bone, List<GroupBoneDefinition> out) {
		out.add(bone);

		for (GroupBoneDefinition child : bone.getChildBones()) {
			flattenInto(child, out);
		}
	}

	/**
	 * The group bone at the given flat index
	 *
	 * @see GroupBoneDefinition#getIndex()
	 * @see #flatBones()
	 */
	public GroupBoneDefinition getFlatBone(int index) {
		return this.flatBones[index];
	}

	/**
	 * A hash of every bone name in this model - group and armature alike - for comparison against
	 * an {@link com.sandymandy.strut.animation.Animation#skeletonFingerprint()} to catch a clip
	 * authored against a different rig
	 * <p>
	 * Deliberately just {@link String#hashCode()} over the concatenated names: cheap, stable across
	 * JVMs (the formula is part of {@code String.hashCode()}'s documented contract), and this side
	 * of the format has no existing exporter to stay wire-compatible with yet - this <i>is</i> the
	 * definition, not a value being matched against one. Group bones are walked in
	 * {@link #flatBones()} order, then each armature's bones in {@link StrutArmature#bones()}
	 * order; both are stable for one baked model but not meaningful beyond equality comparison, so
	 * this is never persisted or compared across a resource reload
	 * <p>
	 * Recomputed on every call rather than cached - only ever called where a specific animation is
	 * about to be queued (see {@link com.sandymandy.strut.animation.AnimationProcessor#buildAnimationQueue}),
	 * not once per frame
	 */
	public int skeletonFingerprint() {
		StringBuilder names = new StringBuilder();

		for (GroupBoneDefinition bone : this.flatBones) {
			names.append(bone.getName()).append('\0');
		}

		for (StrutArmature armature : this.armatures) {
			for (ArmatureBoneDefinition bone : armature.bones()) {
				names.append(bone.getName()).append('\0');
			}
		}

		return names.toString().hashCode();
	}

	/**
	 * Get an armature from this model by name
	 */
	public Optional<StrutArmature> getArmature(String name) {
		for (StrutArmature armature : this.armatures) {
			if (armature.name().equals(name))
				return Optional.of(armature);
		}

		return Optional.empty();
	}

	/**
	 * Gets a bone from this model by name
	 * <p>
	 * Generally not a very efficient method, should be avoided where possible
	 *
	 * @param name The name of the bone
	 * @return An {@link Optional} containing the {@link GroupBoneDefinition} if one matches, otherwise an empty Optional
	 */
	public Optional<GroupBoneDefinition> getBone(String name) {
		for (GroupBoneDefinition bone : this.topLevelBones) {
			GroupBoneDefinition childBone = searchForChildBone(bone, name);

			if (childBone != null)
				return Optional.of(childBone);
		}

		return Optional.empty();
	}

	/**
	 * Search a given {@link GroupBoneDefinition}'s child bones and see if any of them match the given name, then return it
	 *
	 * @param parent The parent bone to search the children of
	 * @param name The name of the child bone to find
	 * @return The {@code GroupBoneDefinition} found in the parent's children list, or null if not found
	 */
	public GroupBoneDefinition searchForChildBone(GroupBoneDefinition parent, String name) {
		if (parent.getName().equals(name))
			return parent;

		for (GroupBoneDefinition bone : parent.getChildBones()) {
			if (bone.getName().equals(name))
				return bone;

			GroupBoneDefinition subChildBone = searchForChildBone(bone, name);

			if (subChildBone != null)
				return subChildBone;
		}

		return null;
	}
}
