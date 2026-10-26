package com.sandymandy.strut.event.builtin;

import com.sandymandy.strut.loading.binary.StrutBinaryFormats;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * The payload of {@code strut:particle}: the same effect / locator / pre-effect-script triple the
 * legacy particle keyframe carried
 * <p>
 * Text form is {@code effect<sep>locator<sep>script} with
 * {@link StrutBinaryFormats#EVENT_FIELD_SEPARATOR} between fields, exactly as
 * {@code BinaryAnimationBaker} already reads a legacy particle event, so old clips need no
 * conversion. Trailing fields may be omitted; an empty field means "absent"
 *
 * @param effect The particle effect's id
 * @param locator The bone to emit from, or empty
 * @param script A pre-effect Molang script, or empty
 */
public record ParticleEventPayload(String effect, String locator, String script) {
	public static final PacketCodec<PacketByteBuf, ParticleEventPayload> CODEC = PacketCodec.of(
			(payload, buf) -> {
				buf.writeString(payload.effect);
				buf.writeString(payload.locator);
				buf.writeString(payload.script);
			},
			buf -> new ParticleEventPayload(buf.readString(), buf.readString(), buf.readString()));

	public static ParticleEventPayload parse(String text) {
		String[] fields = text.split(StrutBinaryFormats.EVENT_FIELD_SEPARATOR, -1);

		if (fields.length > 3)
			throw new IllegalArgumentException("takes at most 3 fields (effect, locator, script), got " + fields.length);

		String effect = fields[0].strip();

		if (effect.isEmpty())
			throw new IllegalArgumentException("needs an effect id");

		return new ParticleEventPayload(effect, fields.length > 1 ? fields[1].strip() : "",
				fields.length > 2 ? fields[2] : "");
	}
}
