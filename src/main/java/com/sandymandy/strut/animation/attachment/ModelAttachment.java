package com.sandymandy.strut.animation.attachment;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.animation.state.BoneVisuals;
import com.sandymandy.strut.animation.state.Pose;
import com.sandymandy.strut.cache.StrutCache;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.model.DepthBias;
import com.sandymandy.strut.renderer.gpu.GpuBatchQueue;
import com.sandymandy.strut.renderer.gpu.GpuModelCache;
import com.sandymandy.strut.renderer.iris.IrisCompat;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Strut model worn by another Strut model (a piece of armour, a hat, a cape, a backpack), on the client
 * <p>
 * It is bound to the wearer <b>by bone name</b>. Each bone of the attached model that has the name of a bone of the wearer is posed exactly as the wearer's bone
 * is, every frame: the wearer's own animation, physics, overrides and visibility, so the attachment follows every pose and a wearer's jiggle without being
 * animated itself. A bone with no match keeps its own rest pose, and moves with whichever of its parents is matched. The attached model is therefore made on
 * top of the wearer's skeleton (the same bone names, the same pivots, the same hierarchy), with only the part that is worn kept; one attachment then fits
 * every wearer that has those bones, the bare and the dressed model of a character alike
 * <p>
 * Hiding: a bone that carries geometry does not take the wearer's own hidden flag (a wearer hides its own armour bones to show the worn model in their place), but
 * is hidden by whatever above it is, so a helmet goes with the head in first person. A wearer whose names differ gets a {@link #boneMap} and one whose proportions differ a {@link #fit}. Names it matches are copied as they are, hidden flags included: name the attached bones after the wearer's skeleton (a {@code head}, an {@code upperBody}),
 * not after bones the wearer keeps hidden to show by an override. An attachment with no bone in common with its wearer is not drawn (it would float at the
 * wearer's origin), and neither is one whose {@link #requireBone required bone} the wearer does not have
 * <p>
 * It is drawn by the GPU batch with the wearer, in the world pass and in shadows alike. Nothing is drawn for a wearer that is not drawn by the GPU path
 * (a screen's doll)
 */
public final class ModelAttachment {
	private final String slot;
	private final Identifier model;
	private Identifier texture;
	private boolean visible = true;
	private boolean translucent;
	private boolean additive;
	/** The scroll of a glint: how many times the texture repeats over the model's UVs, or 0 for none */
	private float glintRepeatU;
	private float glintRepeatV;
	@Nullable
	private String requiredBone;
	private int tint = 0xFFFFFFFF;
	private float uOffset;
	private float vOffset;
	private final Set<String> hiddenBones = new HashSet<>();
	private Map<String, String> boneMap = Map.of();
	private Map<String, BoneFit> fits = Map.of();
	private BoneFit[] fitByBone;

	@Nullable
	private BakedStrutModel baked;
	@Nullable
	private Pose pose;
	@Nullable
	private BoneVisuals visuals;
	private boolean visualsStale = true;
	private boolean warned;
	private String status = "not asked to draw yet: the wearer has not been drawn by the GPU path since it was set";

	/** The wearer's bone index for each bone of the attached model, or -1; built for {@link #sourceFor} */
	@Nullable
	private int[] source;
	@Nullable
	private BakedStrutModel sourceFor;
	private boolean hasRequired;
	/** Scratch for {@link #prepare}: whether each bone is hidden, and whether everything under it is, once what is above it is counted */
	private boolean[] hiddenHere;
	private boolean[] childrenHiddenHere;
	private boolean matchedAny;

	public ModelAttachment(String slot, Identifier model, Identifier texture) {
		this.slot = slot;
		this.model = model;
		this.texture = texture;
	}

	public String slot() {
		return this.slot;
	}

	public Identifier model() {
		return this.model;
	}

	public Identifier texture() {
		return this.texture;
	}

	/** Draws the model with another texture from now on */
	public ModelAttachment texture(Identifier texture) {
		this.texture = texture;

		return this;
	}

	/**
	 * Draws the model blended, over what is under it (a trim over armour made of the same geometry: the texture's transparent parts show the armour). Off by
	 * default. A translucent attachment casts no shadow
	 */
	public ModelAttachment translucent(boolean translucent) {
		this.translucent = translucent;

		return this;
	}

	/**
	 * Adds the model to what is under it instead of covering it (the drawn alpha, with the tint's, says how much). Implies {@link #translucent}. Under a shader
	 * pack the pack's own translucent blending is used instead
	 */
	public ModelAttachment additive(boolean additive) {
		this.additive = additive;

		if (additive)
			this.translucent = true;

		return this;
	}

	/**
	 * Draws the model's texture scrolling over it, as an enchantment's glint: the texture repeats {@code repeatU} by {@code repeatV} times over the model's
	 * UVs and moves with the game's clock. Use with {@link #additive} and a tint whose alpha is the strength. 0 for no scroll
	 */
	public ModelAttachment glint(float repeatU, float repeatV) {
		this.glintRepeatU = repeatU;
		this.glintRepeatV = repeatV;
		this.visualsStale = true;

		return this;
	}

	public boolean isTranslucent() {
		return this.translucent;
	}

	/** Whether the attachment is drawn: true unless turned off */
	public ModelAttachment visible(boolean visible) {
		this.visible = visible;

		return this;
	}

	public boolean isVisible() {
		return this.visible;
	}

	/**
	 * Does not draw the attachment unless the wearer has a bone of this name: a cape needs a {@code cape} bone to hang from, and is not drawn on a wearer
	 * with none
	 */
	public ModelAttachment requireBone(@Nullable String bone) {
		this.requiredBone = bone;
		this.sourceFor = null;

		return this;
	}

	/** Tints the whole model, packed ARGB ({@code 0xFFFFFFFF} for none): leather's dye */
	public ModelAttachment tint(int argb) {
		this.tint = argb;
		this.visualsStale = true;

		return this;
	}

	/** Shifts the UVs of the whole model, in texture fractions: a column of an atlas of armour materials */
	public ModelAttachment uvOffset(float u, float v) {
		this.uOffset = u;
		this.vOffset = v;
		this.visualsStale = true;

		return this;
	}

	/**
	 * Which wearer bone each of the attached model's bones follows, for the ones whose names are not the wearer's: {@code {"armorHead": "head"}} makes the
	 * attached bone {@code armorHead} follow the wearer's {@code head}. A bone not listed follows the bone of its own name
	 */
	public ModelAttachment boneMap(Map<String, String> attachedToWearer) {
		this.boneMap = Map.copyOf(attachedToWearer);
		this.sourceFor = null;

		return this;
	}

	/** Moves, turns or scales the attached model's bones from where the wearer's put them, by attached bone name (see {@link BoneFit}) */
	public ModelAttachment fit(Map<String, BoneFit> fits) {
		this.fits = Map.copyOf(fits);
		this.sourceFor = null;

		return this;
	}

	/** Hides or shows one of the attached model's bones (and, as with any hidden bone, not the ones below it unless they say so) */
	public ModelAttachment hideBone(String bone, boolean hidden) {
		if (hidden)
			this.hiddenBones.add(bone);
		else
			this.hiddenBones.remove(bone);

		return this;
	}

	/**
	 * Poses the attached model on {@code wearerPose} (the wearer's pose this frame, indexed as {@code wearer}'s bones), returning whether it is to be drawn.
	 * Split from {@link #submit} so the binding can be tested without a game
	 */
	public boolean prepare(BakedStrutModel wearer, Pose wearerPose) {
		BakedStrutModel attached = this.baked != null ? this.baked : lookUp();

		if (!this.visible) {
			this.status = "turned off";

			return false;
		}

		if (attached == null) {
			this.status = "the model is not loaded: " + this.model;

			return false;
		}

		return prepare(attached, wearer, wearerPose);
	}

	/** {@link #prepare(BakedStrutModel, Pose)} with the attached model given, for a test */
	boolean prepare(BakedStrutModel attached, BakedStrutModel wearer, Pose wearerPose) {
		if (this.baked != attached) {
			this.baked = attached;
			this.pose = new Pose(attached.flatBones().length);
			this.source = null;
			this.sourceFor = null;
			this.visuals = null;
			this.visualsStale = true;
		}

		if (this.sourceFor != wearer)
			bind(attached, wearer);

		if (!this.matchedAny) {
			this.status = "no bone of the model has the name of a bone of the wearer";

			return false;
		}

		if (this.requiredBone != null && !this.hasRequired) {
			this.status = "the wearer has no bone named " + this.requiredBone;

			return false;
		}

		GroupBoneDefinition[] definitions = attached.flatBones();
		Pose pose = this.pose;

		if (this.hiddenHere == null || this.hiddenHere.length != definitions.length) {
			this.hiddenHere = new boolean[definitions.length];
			this.childrenHiddenHere = new boolean[definitions.length];
		}

		for (int i = 0; i < definitions.length; i++) {
			int from = this.source[i];
			GroupBoneDefinition own = definitions[i];
			int parent = own.getParent() == null ? -1 : own.getParent().getIndex();
			boolean carriesGeometry = !own.getCubes().isEmpty() || !own.getMeshes().isEmpty();
			boolean hiddenFlag;
			boolean childrenFlag;

			if (from >= 0) {
				pose.setPosition(i, wearerPose.getPosX(from), wearerPose.getPosY(from), wearerPose.getPosZ(from));
				pose.setRotation(i, wearerPose.getRotX(from), wearerPose.getRotY(from), wearerPose.getRotZ(from));
				pose.setScale(i, wearerPose.getScaleX(from), wearerPose.getScaleY(from), wearerPose.getScaleZ(from));
				pose.setPivotDeltaX(i, wearerPose.getPivotDeltaX(from));
				pose.setPivotDeltaY(i, wearerPose.getPivotDeltaY(from));
				pose.setPivotDeltaZ(i, wearerPose.getPivotDeltaZ(from));
				// A bone that carries geometry does not take the wearer's own hidden flag: a wearer hides its own armour bones (to show the worn model instead),
				// and a worn piece is named after them. What hides such a bone is what is above it
				hiddenFlag = !carriesGeometry && wearerPose.isHidden(from);
				childrenFlag = wearerPose.isChildrenHidden(from);
			}
			else {
				GroupBoneDefinition definition = definitions[i];

				pose.setPosition(i, 0f, 0f, 0f);
				pose.setRotation(i, definition.getInitialRotX(), definition.getInitialRotY(), definition.getInitialRotZ());
				pose.setScale(i, 1f, 1f, 1f);
				pose.setPivotDeltaX(i, 0f);
				pose.setPivotDeltaY(i, 0f);
				pose.setPivotDeltaZ(i, 0f);
				hiddenFlag = definition.shouldNeverRender() == Boolean.TRUE;
				childrenFlag = false;
			}

			// hidden and children-hidden are inherited down the bones of the worn model itself: parents come before their children
			boolean fromAbove = parent >= 0 && (this.hiddenHere[parent] || this.childrenHiddenHere[parent]);

			this.hiddenHere[i] = hiddenFlag || fromAbove;
			this.childrenHiddenHere[i] = childrenFlag || (parent >= 0 && this.childrenHiddenHere[parent]);
			pose.setHidden(i, this.hiddenHere[i]);
			pose.setChildrenHidden(i, false);

			BoneFit fit = this.fitByBone[i];

			if (fit != null) {
				pose.setPosition(i, pose.getPosX(i) + fit.offset()[0], pose.getPosY(i) + fit.offset()[1], pose.getPosZ(i) + fit.offset()[2]);
				pose.setRotation(i, pose.getRotX(i) + (float) Math.toRadians(fit.rotation()[0]), pose.getRotY(i) + (float) Math.toRadians(fit.rotation()[1]),
						pose.getRotZ(i) + (float) Math.toRadians(fit.rotation()[2]));
				pose.setScale(i, pose.getScaleX(i) * fit.scale()[0], pose.getScaleY(i) * fit.scale()[1], pose.getScaleZ(i) * fit.scale()[2]);
			}

			if (!this.hiddenBones.isEmpty() && this.hiddenBones.contains(definitions[i].getName())) {
				this.hiddenHere[i] = true;
				pose.setHidden(i, true);
			}
		}

		return true;
	}

	/**
	 * Queues the attached model for the GPU batch this frame, posed on its wearer. {@code transform} and the draw state are the wearer's own
	 */
	public void submit(BakedStrutModel wearer, Pose wearerPose, Matrix4f transform, GpuBatchQueue.RenderKey key, int packedLight, int packedOverlay, int colour) {
		if (this.translucent && IrisCompat.isRenderingShadowPass())
			return;

		if (!prepare(wearer, wearerPose))
			return;

		BakedStrutModel attached = this.baked;

		if (!attached.armatures().isEmpty()) {
			if (!this.warned) {
				this.warned = true;
				StrutConstants.LOGGER.log(Level.WARN, "The model attachment \"" + this.slot + "\" (" + this.model + ") has an armature, which an attached model cannot have yet: not drawn");
			}

			this.status = "the model has an armature, which an attached model cannot have yet";

			return;
		}

		this.status = "drawn";
		GpuBatchQueue.RenderKey drawKey = this.translucent ? new GpuBatchQueue.RenderKey(true, DepthBias.NONE, false, true, this.additive) : key;

		GpuBatchQueue.submit(GpuModelCache.get(attached), attached, this.texture, drawKey, new Matrix4f(transform), this.pose, visuals(attached), null, null, null,
				packedLight, packedOverlay, colour);
	}

	/** Why this attachment was or was not drawn the last time the wearer was; for tools */
	public String status() {
		return this.status;
	}

	/** The pose the attached model has this frame, after {@link #prepare}; for tests and tools */
	@Nullable
	public Pose pose() {
		return this.pose;
	}

	@Nullable
	private BoneVisuals visuals(BakedStrutModel attached) {
		boolean scrolling = this.glintRepeatU != 0f || this.glintRepeatV != 0f;

		if (this.tint == 0xFFFFFFFF && this.uOffset == 0f && this.vOffset == 0f && !scrolling)
			return null;

		if (scrolling) {
			// vanilla's glint moves a texture across the model: a whole texture in about 14 seconds along one axis and 4 along the other
			long ms = Util.getMeasuringTimeMs();
			float u = -((ms * 4L % 110000L) / 110000f);
			float v = (ms * 4L % 30000L) / 30000f;
			int count = attached.flatBones().length;

			if (this.visuals == null)
				this.visuals = new BoneVisuals(count);

			for (int i = 0; i < count; i++) {
				this.visuals.setColor(i, this.tint);
				this.visuals.setUvScale(i, this.glintRepeatU, this.glintRepeatV);
				this.visuals.setUvOffset(i, u, v);
			}

			return this.visuals;
		}

		if (this.visuals == null || this.visualsStale) {
			int count = attached.flatBones().length;

			if (this.visuals == null)
				this.visuals = new BoneVisuals(count);

			for (int i = 0; i < count; i++) {
				this.visuals.setColor(i, this.tint);
				this.visuals.setUvOffset(i, this.uOffset, this.vOffset);
			}

			this.visualsStale = false;
		}

		return this.visuals;
	}

	@Nullable
	private BakedStrutModel lookUp() {
		BakedStrutModel found = StrutCache.getBakedModels().get(this.model);

		if (found == null && !this.warned) {
			this.warned = true;
			StrutConstants.LOGGER.log(Level.WARN, "The model attachment \"" + this.slot + "\" names a model that is not loaded: " + this.model);
		}

		return found;
	}

	private void bind(BakedStrutModel attached, BakedStrutModel wearer) {
		Map<String, Integer> wearerBones = new HashMap<>();
		GroupBoneDefinition[] wearerDefinitions = wearer.flatBones();

		for (int i = 0; i < wearerDefinitions.length; i++)
			wearerBones.putIfAbsent(wearerDefinitions[i].getName(), i);

		GroupBoneDefinition[] definitions = attached.flatBones();
		int[] source = new int[definitions.length];

		this.matchedAny = false;

		for (int i = 0; i < definitions.length; i++) {
			source[i] = wearerBones.getOrDefault(this.boneMap.getOrDefault(definitions[i].getName(), definitions[i].getName()), -1);
			this.matchedAny |= source[i] >= 0;
		}

		BoneFit[] fitByBone = new BoneFit[definitions.length];

		for (int i = 0; i < definitions.length; i++) {
			BoneFit fit = this.fits.get(definitions[i].getName());

			fitByBone[i] = fit == null || fit.isNone() ? null : fit;
		}

		this.fitByBone = fitByBone;
		this.hasRequired = this.requiredBone != null && wearerBones.containsKey(this.requiredBone);
		this.source = source;
		this.sourceFor = wearer;
	}
}
