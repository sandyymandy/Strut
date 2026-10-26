package com.sandymandy.strut.player;

/**
 * Where a hand's item is drawn on a body: the bone it rides, and how it is turned and moved from the bone's pivot
 * <p>
 * The item is drawn as the game draws one held in a hand (the same model transform as the vanilla player's third-person hand), starting from the bone's pivot
 * with the bone's own animation, so it moves with the hand. Rotations are in degrees, offsets in blocks (16 pixels), in the bone's own space. The defaults turn
 * the item the way a vanilla arm holds it: pointing out from the end of an arm that hangs down
 *
 * @param bone the bone the item rides: put its pivot at the hand
 */
public record HandItem(String bone, float offsetX, float offsetY, float offsetZ, float rotationX, float rotationY, float rotationZ, float scaleX, float scaleY, float scaleZ) {
	/** The item on {@code bone}, turned as a vanilla arm holds it and not moved */
	public static HandItem at(String bone) {
		return new HandItem(bone, 0f, 0f, 0f, -90f, 0f, 0f, 1, 1, 1);
	}

	/** This item moved by {@code x}, {@code y}, {@code z} blocks in the bone's space */
	public HandItem offset(float x, float y, float z) {
		return new HandItem(this.bone, x, y, z, this.rotationX, this.rotationY, this.rotationZ, this.scaleX, this.scaleY, this.scaleZ);
	}

    /** This item moved by {@code x}, {@code y}, {@code z} pixels in the bone's space */
    public HandItem offsetPixels(float xPixels, float yPixels, float zPixels) {
        return this.offset(xPixels / 16f, yPixels / 16f, zPixels / 16f);
    }

	/** This item turned to {@code x}, {@code y}, {@code z} degrees in place of the default turn */
	public HandItem rotation(float x, float y, float z) {
		return new HandItem(this.bone, this.offsetX, this.offsetY, this.offsetZ, x, y, z, this.scaleX, this.scaleY, this.scaleZ);
	}

    /** This item scaled to {@code x}, {@code y}, {@code z} times the original size */
    public HandItem scale(float x, float y, float z) {
        return new HandItem(this.bone, this.offsetX, this.offsetY, this.offsetZ, this.rotationX, this.rotationY, this.rotationZ, x, y, z);
    }

    /** This item scaled to {@code scale} times the original size */
    public HandItem scale(float scale) {
        return this.scale(scale, scale, scale);
    }
}
