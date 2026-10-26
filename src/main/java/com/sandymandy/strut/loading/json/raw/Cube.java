package com.sandymandy.strut.loading.json.raw;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.util.JsonHelper;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.util.JsonUtil;

/**
 * Container class for cube information, only used in deserialization at startup
 */
public record Cube(@Nullable Double inflate, @Nullable Boolean mirror, double[] origin, double[] pivot, double[] rotation, double[] size, UVUnion uv) {
	public static JsonDeserializer<Cube> deserializer() throws JsonParseException {
		return (json, type, context) -> {
			JsonObject obj = json.getAsJsonObject();
			Double inflate = JsonUtil.getOptionalDouble(obj, "inflate");
			Boolean mirror = JsonUtil.getOptionalBoolean(obj, "mirror");
			double[] origin = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "origin", null));
			double[] pivot = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "pivot", null));
			double[] rotation = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "rotation", null));
			double[] size = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "size", null));
			UVUnion uvUnion = JsonHelper.deserialize(obj, "uv", null, context, UVUnion.class);

			return new Cube(inflate, mirror, origin, pivot, rotation, size, uvUnion);
		};
	}
}
