package com.sandymandy.strut.cache.texture;

import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Remembers, per base texture, whether its {@code _glowmask} file exists, so the GPU path's per-draw emissive check asks the resource
 * manager once instead of every draw
 * <p>
 * Without this, a texture with no glowmask (most of them) went through the texture manager on every draw: the manager tried to load the
 * missing file, logged a warning with a stack trace, replaced the texture with its missing-texture stand-in, and the next draw did it all
 * again. The answer is valid until the resources reload, which is detected by the resource manager changing identity ({@code token}): a
 * reload builds a new one
 * <p>
 * Fail-safe: a texture id with no file extension (a player skin registered at runtime, {@code minecraft:skins/<hash>}) has no glowmask and is never
 * looked up, and a lookup that throws counts as "no glowmask" and is remembered, so one bad id cannot throw on the render thread every frame
 */
final class GlowMaskLookup {
	private final Map<Identifier, Boolean> known = new HashMap<>();
	private Object token;

	/**
	 * @param token Something whose identity changes when resources reload (the resource manager)
	 * @param base The base texture
	 * @param glowMaskExists Looks the glowmask file up; called at most once per base per token
	 * @return Whether {@code base} has a glowmask file
	 */
	synchronized boolean hasGlowMask(Object token, Identifier base, Predicate<Identifier> glowMaskExists) {
		if (this.token != token) {
			this.known.clear();

			this.token = token;
		}

		if (!StrutAbstractTexture.hasFileExtension(base))
			return false;

		Boolean cached = this.known.get(base);

		if (cached != null)
			return cached;

		boolean exists;

		try {
			exists = glowMaskExists.test(base);
		}
		catch (RuntimeException ex) {
			exists = false;
		}

		this.known.put(base, exists);

		return exists;
	}
}
