package com.sandymandy.strut.loading.json.raw;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.util.JsonHelper;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.util.JsonUtil;

/**
 * Container class for texture mesh information, only used in deserialization at startup
 */
public record TextureMesh(double[] localPivot, double[] position, double[] rotation, double[] scale, @Nullable String texture) {
	public static JsonDeserializer<TextureMesh> deserializer() throws JsonParseException {
		return (json, type, context) -> {
			JsonObject obj = json.getAsJsonObject();
			double[] pivot = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "local_pivot", null));
			double[] position = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "position", null));
			double[] rotation = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "rotation", null));
			double[] scale = JsonUtil.jsonArrayToDoubleArray(JsonHelper.getArray(obj, "scale", null));
			String texture = JsonHelper.getString(obj, "texture", null);

			return new TextureMesh(pivot, position, rotation, scale, texture);
		};
	}
}
