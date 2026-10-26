package com.sandymandy.strut.player.client;

import com.sandymandy.strut.model.DefaultedEntityStrutModel;
import com.sandymandy.strut.model.StrutModel;
import com.sandymandy.strut.player.PlayerBody;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The renderer of each registered body, made the first time a player with that body is drawn
 * <p>
 * Players are not in the entity renderer registry (the game keeps them apart, by skin model), so the renderer dispatcher is asked, by
 * {@code EntityRenderDispatcherMixin}, for a player's renderer, and this answers for a player that has a body. The renderers are dropped when resources
 * reload, so they pick up changed models
 */
@ApiStatus.Internal
public final class PlayerRenderers {
	private static final Map<Identifier, StrutPlayerRenderer> RENDERERS = new HashMap<>();
	private static final Map<Identifier, StrutModel<PlayerBody>> MODELS = new HashMap<>();

	/** Set while the game draws the first-person arm: it casts the player's renderer to the vanilla one, so a body must not be answered there */
	private static int vanillaDepth;

	private PlayerRenderers() {}

	static void setModel(Identifier body, StrutModel<PlayerBody> model) {
		MODELS.put(body, model);
		RENDERERS.remove(body);
	}

	/**
	 * The renderer for {@code body}, made with {@code context} if there is none yet
	 */
	public static StrutPlayerRenderer forBody(PlayerBody body, Supplier<EntityRendererFactory.Context> context) {
		StrutPlayerRenderer renderer = RENDERERS.get(body.id());

		if (renderer == null) {
			StrutModel<PlayerBody> model = MODELS.get(body.id());

			if (model == null)
				model = new DefaultedEntityStrutModel<>(body.id(), body.headBone());

			renderer = new StrutPlayerRenderer(context.get(), model, body);
			RENDERERS.put(body.id(), renderer);
		}

		return renderer;
	}

	/**
	 * Forgets every renderer: the resources reloaded
	 */
	public static void clear() {
		RENDERERS.clear();
	}

	/**
	 * Whether the vanilla player renderer is wanted for every player right now (the first-person arm and held map)
	 */
	public static boolean vanillaOnly() {
		return vanillaDepth > 0;
	}

	public static void enterVanilla() {
		vanillaDepth++;
	}

	public static void leaveVanilla() {
		if (vanillaDepth > 0)
			vanillaDepth--;
	}

	@Nullable
	static StrutModel<PlayerBody> modelFor(Identifier body) {
		return MODELS.get(body);
	}
}
