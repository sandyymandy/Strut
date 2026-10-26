package com.sandymandy.strut.player;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What differs from the body's own for one variant of it (see {@link PlayerBody.Builder#variants}): a character's hitbox, the wearer profile her armour is by, where
 * her camera sits. Anything left out (null) is the body's
 * <pre>{@code
 * .variants(variant -> BodyVariant.builder().dimensions(0.5f, 1.4f, 1.25f).armour(profileOf(variant)).build())
 * }</pre>
 *
 * @param dimensions the hitbox and eye height, or null for the body's
 * @param armourProfile the wearer profile armour is worn by, or null for the body's
 * @param eyeBone the bone the camera sits at in {@link FirstPersonMode#IMMERSIVE}, or null for the body's
 * @param hiddenBones the bones always hidden, or null for the body's (a list given replaces the body's)
 * @param firstPersonHiddenBones the bones hidden for the player themself in first person, or null for the body's (a list given replaces)
 * @param mainHandItem where the main hand item is drawn, or null for the body's
 * @param offHandItem where the off hand item is drawn, or null for the body's
 * @param firstPerson the first person mode, or null for the body's
 */
public record BodyVariant(@Nullable BodyDimensions dimensions, @Nullable Identifier armourProfile, @Nullable String eyeBone, @Nullable List<String> hiddenBones,
						  @Nullable List<String> firstPersonHiddenBones, @Nullable HandItem mainHandItem, @Nullable HandItem offHandItem, @Nullable FirstPersonMode firstPerson) {
	/** A variant that differs from the body in nothing */
	public static final BodyVariant NONE = new BodyVariant(null, null, null, null, null, null, null, null);

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private BodyDimensions dimensions;
		private Identifier armourProfile;
		private String eyeBone;
		private List<String> hiddenBones;
		private List<String> firstPersonHiddenBones;
		private HandItem mainHandItem;
		private HandItem offHandItem;
		private FirstPersonMode firstPerson;

		private Builder() {}

		/** The hitbox and eye height of this variant, in blocks (see {@link BodyDimensions}) */
		public Builder dimensions(float width, float height, float eyeHeight) {
			this.dimensions = new BodyDimensions(width, height, eyeHeight);

			return this;
		}

		public Builder armour(@Nullable Identifier profile) {
			this.armourProfile = profile;

			return this;
		}

		public Builder eyeBone(String bone) {
			this.eyeBone = bone;

			return this;
		}

		public Builder hideBones(String... bones) {
			this.hiddenBones = List.of(bones);

			return this;
		}

		public Builder hideInFirstPerson(String... bones) {
			this.firstPersonHiddenBones = List.of(bones);

			return this;
		}

		public Builder mainHandItem(String bone) {
			this.mainHandItem = HandItem.at(bone);

			return this;
		}

		public Builder mainHandItem(HandItem item) {
			this.mainHandItem = item;

			return this;
		}

		public Builder offHandItem(String bone) {
			this.offHandItem = HandItem.at(bone);

			return this;
		}

		public Builder offHandItem(HandItem item) {
			this.offHandItem = item;

			return this;
		}

		public Builder firstPerson(FirstPersonMode mode) {
			this.firstPerson = mode;

			return this;
		}

		public BodyVariant build() {
			return new BodyVariant(this.dimensions, this.armourProfile, this.eyeBone, this.hiddenBones, this.firstPersonHiddenBones, this.mainHandItem, this.offHandItem, this.firstPerson);
		}
	}
}
