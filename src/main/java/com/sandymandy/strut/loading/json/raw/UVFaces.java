package com.sandymandy.strut.loading.json.raw;

import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import net.minecraft.util.math.Direction;
import net.minecraft.util.JsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Container class for UV face information, only used in deserialization at startup
 */
public record UVFaces(@Nullable FaceUV north, @Nullable FaceUV south, @Nullable FaceUV east, @Nullable FaceUV west, @Nullable FaceUV up, @Nullable FaceUV down) {
	public static JsonDeserializer<UVFaces> deserializer() {
		return (json, type, context) -> {
			JsonObject obj = json.getAsJsonObject();
			FaceUV north = JsonHelper.deserialize(obj, "north", null, context, FaceUV.class);
			FaceUV south = JsonHelper.deserialize(obj, "south", null, context, FaceUV.class);
			FaceUV east = JsonHelper.deserialize(obj, "east", null, context, FaceUV.class);
			FaceUV west = JsonHelper.deserialize(obj, "west", null, context, FaceUV.class);
			FaceUV up = JsonHelper.deserialize(obj, "up", null, context, FaceUV.class);
			FaceUV down = JsonHelper.deserialize(obj, "down", null, context, FaceUV.class);

			return new UVFaces(north, south, east, west, up, down);
		};
	}

	public FaceUV fromDirection(Direction direction) {
		return switch(direction) {
			case NORTH -> north;
			case SOUTH -> south;
			case EAST -> east;
			case WEST -> west;
			case UP -> up;
			case DOWN -> down;
		};
	}
}
