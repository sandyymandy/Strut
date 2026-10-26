package com.sandymandy.strut.player.client;

import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.player.PlayerBody;
import com.sandymandy.strut.player.StrutPlayers;
import com.sandymandy.strut.player.FirstPersonMode;
import java.util.function.Consumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * The client side of {@link com.sandymandy.strut.player.StrutPlayers}: choosing a body's model, and reading which player is being drawn
 */
public final class StrutPlayersClient {
	/** Set by {@link StrutPlayerRenderer} while it draws a player, on the render thread */
	static AbstractClientPlayerEntity renderedPlayer;

	@Nullable
	private static Function<AbstractClientPlayerEntity, String> eyeBoneChooser;
	@Nullable
	private static Function<AbstractClientPlayerEntity, FirstPersonMode> firstPersonChooser;
	/** The bones a mod asked the place of: by player id, then bone name */
	private static final Map<Integer, Map<String, TrackedBone>> TRACKED = new HashMap<>();
	/** How long a bone is kept after it was last asked for, in milliseconds */
	private static final long TRACK_MILLIS = 2000L;

	private static final class TrackedBone {
		@Nullable
		Vec3d offset;
		long askedAt;
	}

	private StrutPlayersClient() {}

	/**
	 * Chooses, for each player drawn, which bone the camera sits at in {@link FirstPersonMode#IMMERSIVE}, in place of the body's own
	 * ({@link PlayerBody.Builder#eyeBone}): a scene's camera bone, say. Return null to leave the body's. Asked every frame, so keep it cheap
	 */
	public static void eyeBone(@Nullable Function<AbstractClientPlayerEntity, String> chooser) {
		eyeBoneChooser = chooser;
	}

	/**
	 * Chooses, for each player drawn, the first person mode, in place of the body's own ({@link PlayerBody.Builder#firstPerson}): an option the player sets, for
	 * one. Return null to leave the body's. Asked every frame, so keep it cheap
	 */
	public static void firstPerson(@Nullable Function<AbstractClientPlayerEntity, FirstPersonMode> chooser) {
		firstPersonChooser = chooser;
	}

	/** The bone the camera sits at for {@code player}: the chooser's, else the body's */
	public static String eyeBoneOf(AbstractClientPlayerEntity player, PlayerBody body) {
		String chosen = eyeBoneChooser == null ? null : eyeBoneChooser.apply(player);

		return chosen == null || chosen.isEmpty() ? body.eyeBone(StrutPlayers.variant(player)) : chosen;
	}

	/** The first person mode for {@code player}: the chooser's, else the body's */
	public static FirstPersonMode firstPersonOf(AbstractClientPlayerEntity player, PlayerBody body) {
		FirstPersonMode chosen = firstPersonChooser == null ? null : firstPersonChooser.apply(player);

		return chosen == null ? body.firstPerson(StrutPlayers.variant(player)) : chosen;
	}

	/**
	 * Where {@code bone} of {@code player}'s body is, relative to the player's own position and in the world's axes, as it was drawn in the last frame, or null if
	 * the player has no body, the model has no such bone, or it has not been drawn yet. The first call for a bone starts keeping track of it (a few cheap
	 * matrix steps for each frame the player is drawn), so it answers null that once; it is let go of two seconds after it was last asked for. Render thread
	 * only. A scene puts a rider at a bone with it
	 */
	@Nullable
	public static Vec3d bonePosition(AbstractClientPlayerEntity player, String bone) {
		TrackedBone tracked = TRACKED.computeIfAbsent(player.getId(), id -> new HashMap<>()).computeIfAbsent(bone, name -> new TrackedBone());

		tracked.askedAt = System.currentTimeMillis();

		return tracked.offset;
	}

	/** Called by the renderer after it draws {@code player}: notes the place of each bone that is being asked for, forgets the ones that no longer are */
	static void recordBones(AbstractClientPlayerEntity player, Function<String, Vec3d> placeOf) {
		Map<String, TrackedBone> bones = TRACKED.get(player.getId());

		if (bones == null)
			return;

		long now = System.currentTimeMillis();

		bones.entrySet().removeIf(entry -> now - entry.getValue().askedAt > TRACK_MILLIS);

		for (Map.Entry<String, TrackedBone> entry : bones.entrySet())
			entry.getValue().offset = placeOf.apply(entry.getKey());

		if (bones.isEmpty())
			TRACKED.remove(player.getId());
	}

	/**
	 * Uses {@code model} for the body registered as {@code body}, instead of the conventional one (a model named for the body's id, in the entity folders).
	 * Call it from the client initialiser, after the body is registered; the model decides the model file, texture and animation file, and may read
	 * {@link #renderedPlayer()} to choose them for the player being drawn
	 */
	public static void model(Identifier body, StrutModel<PlayerBody> model) {
		PlayerRenderers.setModel(body, model);
	}

	/**
	 * Draws {@code body} in a screen, in the box {@code x1, y1} to {@code x2, y2}, turned toward the mouse as the inventory draws the player: for a menu that
	 * shows what a player would look like. The body is drawn as it would be on a player who is standing still, with {@code variant} (see
	 * {@code StrutPlayers.variant}). Needs a world, so nothing is drawn in the title screen
	 *
	 * @param size the scale, as the inventory\'s (30 draws a player a little under the box\'s height)
	 * @return whether it was drawn
	 */
	public static boolean drawBody(DrawContext context, Identifier body, String variant, int x1, int y1, int x2, int y2, int size, float mouseX,
			float mouseY) {
		return BodyPreview.draw(context, body, variant, x1, y1, x2, y2, size, mouseX, mouseY);
	}

	/**
	 * {@link #drawBody(net.minecraft.client.gui.DrawContext, Identifier, String, int, int, int, int, int, float, float)} turned to a yaw and tipped to a pitch you give
	 * (a preview the player drags) instead of toward the mouse, with a hook on the preview player
	 *
	 * @param yawDegrees how far the body is turned: 0 faces the viewer, 180 shows its back
	 * @param pitchDegrees how far it is tipped toward the viewer
	 * @param prepare runs on the preview player after it is given the body and before it is drawn, on every call: the player is the same one for every preview, so a
	 *                model that reads a client-only flag from it (the stripped form, a pose) is told it here each time. May be null
	 */
	public static boolean drawBody(DrawContext context, Identifier body, String variant, int x1, int y1, int x2, int y2, int size, float yawDegrees,
			float pitchDegrees, @Nullable Consumer<AbstractClientPlayerEntity> prepare) {
		return BodyPreview.draw(context, body, variant, x1, y1, x2, y2, size, yawDegrees, pitchDegrees, prepare);
	}

	/**
	 * The player whose body is being drawn right now, or null outside a body's draw. A model reads it to pick a texture or an animation file by
	 * {@code StrutPlayers.variant(player)}. Only meaningful on the render thread, while the model's own methods are being called
	 */
	@Nullable
	public static AbstractClientPlayerEntity renderedPlayer() {
		return renderedPlayer;
	}
}
