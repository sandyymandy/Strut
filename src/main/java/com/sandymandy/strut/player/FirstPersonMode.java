package com.sandymandy.strut.player;

/**
 * What the player sees of their own body in first person
 */
public enum FirstPersonMode {
	/**
	 * Nothing changes: the camera is where the player's eyes are and the game draws the vanilla arm and held item. The default, safe for any body
	 */
	VANILLA,

	/**
	 * The whole body is drawn and the camera sits at the body's eye bone ({@link PlayerBody.Builder#eyeBone}), so the player looks out of their own
	 * body and sees their own arms and chest. The bones in {@link PlayerBody.Builder#hideInFirstPerson} (the head, by default) are hidden for this
	 * player only. The camera moves; where the player aims, hits and shoots from does not, so nothing disagrees with the server
	 */
	IMMERSIVE
}
