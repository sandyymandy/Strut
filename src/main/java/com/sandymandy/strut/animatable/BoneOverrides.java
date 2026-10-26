package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.animation.override.OverrideValue;
import com.sandymandy.strut.util.Color;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * The bone-override setters, shared by what an entity has ({@link EntityBoneOverrides}, {@code entity.overrides()} on {@link StrutEntity}, or
 * {@code entity.overrides(relatedEntity)} on {@link StrutReplacedEntity}) and what a block entity has ({@link BlockEntityBoneOverrides},
 * {@code overrides()} on {@link StrutBlockEntity}): the replacement for {@code StrutRenderLayer} (the old layer system itself was
 * removed)
 * <p>
 * Dispatches on the side of the world the subject is in, the identical pattern {@link StrutEntity#triggerAnim} already uses: called
 * server-side, an override is authoritative and replicates to every client that can see the subject. Called client-side, an
 * override is purely local - never sent anywhere - which is what a per-viewer decision like "hide my own head in first person" needs, and
 * what makes it possible at all: the server cannot know which client is looking through which camera
 * <p>
 * Every setter defaults to {@link OverrideScope#PERSISTENT}, priority {@code 0}, full blend weight
 * and no transition - the full-parameter overload exists for a caller that needs one of
 * the other knobs. {@link BoneOverride#blendWeight()}/
 * {@link BoneOverride#transitionTicks()} are carried but not yet consumed by anything (blending a
 * transition smoothly is future work) - see that record's own doc comment
 */
public abstract class BoneOverrides {
	BoneOverrides() {}

	/**
	 * Sets (or clears) {@code bone}'s own {@code HIDDEN} override
	 * <p>
	 * {@code HIDDEN} (like every override property except {@code CHILDREN_HIDDEN} - see
	 * {@code ModelInstance#resolveEffectivePose}'s own doc comment) inherits down the bone tree: a
	 * descendant with no {@code HIDDEN} override of its own inherits this one, so hiding a bone with
	 * no geometry of its own (a pure pivot/socket - a real, common case, not a hypothetical -
	 * {@code test_dummy}'s own {@code pouches} bone owns zero cubes and zero meshes, every visible part
	 * sitting two levels further down the tree) correctly hides everything under it too, with no
	 * extra call needed. A descendant that wants to stay visible anyway just sets its own
	 * {@code HIDDEN=false} - "child wins" beats whatever it inherited.
	 * <p>
	 * No longer couples {@code CHILDREN_HIDDEN} alongside this the way an earlier version of this
	 * method did, before inheritance existed to fix the root cause properly: coupling it now would
	 * actively break the opt-out above, since {@code CHILDREN_HIDDEN} is the one property that
	 * <em>cannot</em> be overridden back by a descendant (see {@link #setChildrenHidden}) - exactly
	 * what a caller reaching for plain {@code setHidden} does not want
	 */
	public void setHidden(String bone, boolean hidden) {
		setHidden(bone, hidden, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	/**
	 * The full-parameter form - only ever touches {@code HIDDEN}. A caller that specifically wants
	 * {@code CHILDREN_HIDDEN}'s stronger, non-overridable-by-descendants suppression calls
	 * {@link #setChildrenHidden(String, boolean, OverrideScope, int, float, float)} separately
	 */
	public void setHidden(String bone, boolean hidden, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.HIDDEN, new OverrideValue.BoolValue(hidden), scope, priority, blendWeight, transitionTicks);
	}

	/**
	 * Sets (or clears) {@code bone}'s own {@code CHILDREN_HIDDEN} override - unlike every other
	 * override property, this one is deliberately literal-only, not inherited: it always hides every
	 * descendant of {@code bone} unconditionally, with no {@code HIDDEN=false} on any of them able to
	 * opt back out. That is the actual reason this property exists separately from (now-inheriting)
	 * {@code HIDDEN} at all - see {@code ModelInstance#resolveEffectivePose}'s own doc comment
	 */
	public void setChildrenHidden(String bone, boolean hidden) {
		setChildrenHidden(bone, hidden, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setChildrenHidden(String bone, boolean hidden, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.CHILDREN_HIDDEN, new OverrideValue.BoolValue(hidden), scope, priority, blendWeight, transitionTicks);
	}

	public void setPosition(String bone, float x, float y, float z) {
		setPosition(bone, x, y, z, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setPosition(String bone, float x, float y, float z, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.POSITION, new OverrideValue.Vec3Value(x, y, z), scope, priority, blendWeight, transitionTicks);
	}

	public void setRotation(String bone, float x, float y, float z) {
		setRotation(bone, x, y, z, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setRotation(String bone, float x, float y, float z, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.ROTATION, new OverrideValue.Vec3Value(x, y, z), scope, priority, blendWeight, transitionTicks);
	}

	public void setScale(String bone, float x, float y, float z) {
		setScale(bone, x, y, z, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setScale(String bone, float x, float y, float z, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.SCALE, new OverrideValue.Vec3Value(x, y, z), scope, priority, blendWeight, transitionTicks);
	}

	public void setColor(String bone, Color color) {
		setColor(bone, color, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setColor(String bone, Color color, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.COLOR, new OverrideValue.ColorValue(color.argbInt()), scope, priority, blendWeight, transitionTicks);
	}

	public void setUvOffset(String bone, float u, float v) {
		setUvOffset(bone, u, v, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setUvOffset(String bone, float u, float v, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.UV_OFFSET, new OverrideValue.Vec2Value(u, v), scope, priority, blendWeight, transitionTicks);
	}

	/**
	 * The texture property - see {@link OverrideProperty#TEXTURE}'s doc comment on why it is
	 * the one deliberately shipped last (it cannot keep the GPU path's one-draw-call-per-group
	 * property for a bone that disagrees with the rest of its instance)
	 */
	public void setTexture(String bone, Identifier texture) {
		setTexture(bone, texture, OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	public void setTexture(String bone, Identifier texture, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		apply(bone, OverrideProperty.TEXTURE, new OverrideValue.TextureValue(texture), scope, priority, blendWeight, transitionTicks);
	}

	/**
	 * Replaces {@code bone}'s texture with one of a different size than the model was made for, and reads its UVs against {@code uvWidth} x
	 * {@code uvHeight} pixels instead: a player's skin (64 x 64) on a model whose own texture is smaller, say. Without the size the model's UVs
	 * are fractions of its own texture and miss the pixels they were drawn for
	 */
	public void setTexture(String bone, Identifier texture, int uvWidth, int uvHeight) {
		setTexture(bone, texture);
		setUvSize(bone, uvWidth, uvHeight);
	}

	/**
	 * Sets (or, with {@link #clear}, removes) the pixel size {@code bone}'s UVs are read against, see {@link OverrideProperty#UV_SIZE}. It
	 * applies to every texture drawn on the bone (an overlay too), and what is under the bone unless a closer override says otherwise
	 *
	 * @throws IllegalArgumentException If a size is not above 0
	 */
	public void setUvSize(String bone, int uvWidth, int uvHeight) {
		if (uvWidth <= 0 || uvHeight <= 0)
			throw new IllegalArgumentException("a UV size must be above 0, not " + uvWidth + " x " + uvHeight);

		apply(bone, OverrideProperty.UV_SIZE, new OverrideValue.Vec2Value(uvWidth, uvHeight), OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	/**
	 * Draws {@code bone} (and, like every property here, what is under it unless a closer override says otherwise) in the blended
	 * pass rather than the cutout one.
	 */
	public void setTranslucent(String bone, boolean translucent) {
		apply(bone, OverrideProperty.TRANSLUCENT, new OverrideValue.BoolValue(translucent), OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	/**
	 * Draws {@code texture} over {@code bone} a second time, translucent, on top of the base draw and any lower layer. Called on the
	 * client this is this viewer's own overlay (a skin, a player's setting); called on the server it goes to everyone tracking
	 *
	 * @param layer {@code 0} to {@link OverrideProperty#MAX_OVERLAYS} exclusive; a higher layer draws later, so on top
	 * @throws IllegalArgumentException If {@code layer} is out of range
	 */
	public void setOverlay(String bone, int layer, Identifier texture) {
		apply(bone, OverrideProperty.overlay(layer), new OverrideValue.TextureValue(texture), OverrideScope.PERSISTENT, 0, 1f, 0f);
	}

	/**
	 * Removes overlay {@code layer} from {@code bone}, on this call's own side (see {@link #clear})
	 */
	public void clearOverlay(String bone, int layer) {
		clear(bone, OverrideProperty.overlay(layer));
	}

	/**
	 * Clears whichever override this call's own side owns for {@code bone}/{@code property} -
	 * server-side, the authoritative one; client-side, this client's own local one. Never both:
	 * a client cannot clear the server's override any more than it can set one, for the same reason
	 * {@code apply} cannot let it
	 */
	public abstract void clear(String bone, OverrideProperty property);

	/**
	 * This call's own side's current override for {@code bone}/{@code property}, or null if none is
	 * set - the authoritative one server-side, or {@link com.sandymandy.strut.animation.override.ClientBoneOverrideState#resolve}'s
	 * result client-side (local-beats-synced already applied, so this is what actually renders)
	 */
	@Nullable
	public abstract BoneOverride get(String bone, OverrideProperty property);

	/** Sets one override on this call's own side: authoritative on the server, local on a client */
	abstract void apply(String bone, OverrideProperty property, OverrideValue value, OverrideScope scope, int priority, float blendWeight, float transitionTicks);
}
