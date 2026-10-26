package com.sandymandy.strut.loading.json.raw;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.util.JsonUtil;

/**
 * Container class for face UV information, only used in deserialization at startup
 */
public record FaceUV(@Nullable String materialInstance, double[] uv, double[] uvSize, Rotation uvRotation) {
	public FaceUV(@Nullable String materialInstance, double[] uv, double[] uvSize) {
		this(materialInstance, uv, uvSize, Rotation.NONE);
	}

	public static JsonDeserializer<FaceUV> deserializer() throws JsonParseException {
		return (json, type, context) -> {
			JsonObject obj = json.getAsJsonObject();
			String materialInstance = JsonHelper.getString(obj, "material_instance", null);
			double[] uv = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "uv", null));
			double[] uvSize = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "uv_size", null));
			Rotation uvRotation = Rotation.fromValue(JsonHelper.getInt(obj, "uv_rotation", 0));

			return new FaceUV(materialInstance, uv, uvSize, uvRotation);
		};
	}

	public enum Rotation {
		NONE,
		CLOCKWISE_90,
		CLOCKWISE_180,
		CLOCKWISE_270;

		public static Rotation fromValue(int value) throws JsonParseException {
			try {
				return Rotation.values()[(value % 360) / 90];
			}
			catch (Exception e) {
				StrutConstants.LOGGER.error("Invalid Face UV rotation: " + value);

				return fromValue(MathHelper.floor(Math.abs(value) / 90f) * 90);
			}
		}

		public float[] rotateUvs(float u, float v, float uWidth, float vHeight) {
			return switch (this) {
				case NONE -> new float[] {u, v, uWidth, v, uWidth, vHeight, u, vHeight};
				case CLOCKWISE_90 -> new float[] {uWidth, v, uWidth, vHeight, u, vHeight, u, v};
				case CLOCKWISE_180 -> new float[] {uWidth, vHeight, u, vHeight, u, v, uWidth, v};
				case CLOCKWISE_270 -> new float[] {u, vHeight, u, v, uWidth, v, uWidth, vHeight};
			};
		}
	}
}
