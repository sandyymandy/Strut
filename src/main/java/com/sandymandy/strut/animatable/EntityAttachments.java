package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animation.attachment.Attachment;
import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

/**
 * The entity-facing attachment API - {@code entity.attachments()} on {@link StrutEntity}, or
 * {@code entity.attachments(relatedEntity)} on {@link StrutReplacedEntity} - the
 * {@code BlockAndItemStrutLayer} replacement
 * <p>
 * Server-authoritative only, unlike {@link EntityBoneOverrides} - there is no client-local half here
 * (see {@code ClientAttachmentState}'s own doc comment on why), so every setter is a no-op when
 * called client-side rather than dispatching somewhere. A future viewer-specific attachment use case
 * (a first-person-only held item, say) would need one, the same way the first-person-own-head rule
 * needed {@code EntityBoneOverrides}' own {@code local} store - not scoped here
 */
public final class EntityAttachments {
	private final Entity entity;
	private final boolean isReplacedEntity;

	EntityAttachments(Entity entity, boolean isReplacedEntity) {
		this.entity = entity;
		this.isReplacedEntity = isReplacedEntity;
	}

	/**
	 * The attachments of an entity whose rendering Strut replaces, such as a player with a body: what {@code StrutReplacedEntity#attachments} returns
	 */
	public static EntityAttachments forReplaced(Entity entity) {
		return new EntityAttachments(entity, true);
	}

	/**
	 * Attaches {@code item} to {@code bone}, under {@code slot} - a caller-chosen label
	 * ({@code "mainhand"}, {@code "offhand"}, whatever is meaningful to it) that later calls use to
	 * update or {@link #clear} the same attachment, rather than a second attachment stacking on top
	 */
	public void setItem(String slot, String bone, Identifier item) {
		setItem(slot, bone, item, 1, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f);
	}

	/**
	 * The full-parameter form - {@code count} only matters for a stack-size-sensitive item model;
	 * offset/rotation (radians)/scale apply on top of the bone's own world transform, in that order
	 */
	public void setItem(String slot, String bone, Identifier item, int count,
						 float offsetX, float offsetY, float offsetZ,
						 float rotX, float rotY, float rotZ,
						 float scaleX, float scaleY, float scaleZ) {
		apply(slot, new Attachment(bone, item, count, null, offsetX, offsetY, offsetZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ, 0));
	}

	/**
	 * Attaches {@code item} to {@code bone} as a hand holds it: drawn with the item model's own third-person transform, exactly as a player's hand draws it, so a
	 * sword, a bow or a tool sits the way it does in a player's hand, and the offset and turn given are the same ones {@code HandItem} takes for a player body.
	 * Prefer this over {@link #setItem} for something a character holds
	 *
	 * @param rightHand which hand's transform the model is drawn with
	 * @param offsetX in blocks, in the bone's space (pixels / 16)
	 * @param rotXDegrees the turn in <b>degrees</b>, unlike {@link #setItem}'s radians; applied as {@code HandItem} applies it (Z, then Y, then X)
	 */
	public void setHeldItem(String slot, String bone, Identifier item, int count, boolean rightHand, float offsetX, float offsetY, float offsetZ, float rotXDegrees, float rotYDegrees,
			float rotZDegrees, float scale) {
		apply(slot, new Attachment(bone, item, count, null, offsetX, offsetY, offsetZ, (float) Math.toRadians(rotXDegrees), (float) Math.toRadians(rotYDegrees),
				(float) Math.toRadians(rotZDegrees), scale, scale, scale, 0, rightHand ? Attachment.HAND_RIGHT : Attachment.HAND_LEFT));
	}

	/**
	 * Attaches {@code block}'s default state to {@code bone}, under {@code slot} - see
	 * {@link #setItem(String, String, Identifier)}'s own doc comment on what {@code slot} means
	 */
	public void setBlock(String slot, String bone, Identifier block) {
		setBlock(slot, bone, block, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f);
	}

	public void setBlock(String slot, String bone, Identifier block,
						  float offsetX, float offsetY, float offsetZ,
						  float rotX, float rotY, float rotZ,
						  float scaleX, float scaleY, float scaleZ) {
		apply(slot, new Attachment(bone, null, 0, block, offsetX, offsetY, offsetZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ, 0));
	}

	/**
	 * Removes {@code slot}'s attachment, if it had one
	 */
	public void clear(String slot) {
		if (this.entity.getWorld().isClient())
			return;

		ServerAnimationStates.attachmentsFor(this.entity, this.isReplacedEntity).clear(slot);
	}

	private void apply(String slot, Attachment attachment) {
		if (this.entity.getWorld().isClient())
			return;

		ServerAnimationStates.attachmentsFor(this.entity, this.isReplacedEntity).set(slot, attachment);
	}
}
