package com.sandymandy.strut.animation.override;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;

/**
 * The value a {@link BoneOverride} carries - one of five shapes, matching whichever
 * {@link OverrideProperty} it belongs to. Callers never construct one of these directly; the typed
 * setters on {@code EntityBoneOverrides} build the right shape for the property being set
 */
public sealed interface OverrideValue {
	/** Which {@link OverrideValue} record a given {@link OverrideProperty} expects */
	enum Shape {
		BOOL, VEC3, VEC2, COLOR, IDENTIFIER
	}

	record BoolValue(boolean value) implements OverrideValue {}

	/** Shared by {@link OverrideProperty#POSITION}, {@link OverrideProperty#ROTATION} and {@link OverrideProperty#SCALE} */
	record Vec3Value(float x, float y, float z) implements OverrideValue {}

	/** {@link OverrideProperty#UV_OFFSET}'s own shape */
	record Vec2Value(float x, float y) implements OverrideValue {}

	/** {@link OverrideProperty#COLOR}'s own shape - packed ARGB, matching {@link com.sandymandy.strut.util.Color} */
	record ColorValue(int argb) implements OverrideValue {}

	/** {@link OverrideProperty#TEXTURE}'s own shape */
	record TextureValue(Identifier texture) implements OverrideValue {}

	/**
	 * Self-describing - writes its own {@link Shape} ordinal first, the same convention
	 * {@code LoopMode#fromWire} already uses for the binary model/animation wire format, rather than
	 * relying on a caller supplying {@link OverrideProperty#valueShape()} as external context to
	 * pick the right decode path
	 */
	PacketCodec<PacketByteBuf, OverrideValue> STREAM_CODEC = PacketCodec.of(
			(value, buf) -> {
				switch (value) {
					case BoolValue bool -> {
						buf.writeByte(Shape.BOOL.ordinal());
						buf.writeBoolean(bool.value());
					}
					case Vec3Value vec3 -> {
						buf.writeByte(Shape.VEC3.ordinal());
						buf.writeFloat(vec3.x());
						buf.writeFloat(vec3.y());
						buf.writeFloat(vec3.z());
					}
					case Vec2Value vec2 -> {
						buf.writeByte(Shape.VEC2.ordinal());
						buf.writeFloat(vec2.x());
						buf.writeFloat(vec2.y());
					}
					case ColorValue color -> {
						buf.writeByte(Shape.COLOR.ordinal());
						buf.writeInt(color.argb());
					}
					case TextureValue texture -> {
						buf.writeByte(Shape.IDENTIFIER.ordinal());
						buf.writeIdentifier(texture.texture());
					}
				}
			},
			buf -> {
				Shape shape = Shape.values()[buf.readByte()];

				return switch (shape) {
					case BOOL -> new BoolValue(buf.readBoolean());
					case VEC3 -> new Vec3Value(buf.readFloat(), buf.readFloat(), buf.readFloat());
					case VEC2 -> new Vec2Value(buf.readFloat(), buf.readFloat());
					case COLOR -> new ColorValue(buf.readInt());
					case IDENTIFIER -> new TextureValue(buf.readIdentifier());
				};
			});
}
