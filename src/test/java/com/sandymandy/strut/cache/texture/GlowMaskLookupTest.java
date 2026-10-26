package com.sandymandy.strut.cache.texture;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The emissive check runs on every GPU draw; a texture with no glowmask must cost one lookup, not one per draw (and the log spam that came with it)
 */
class GlowMaskLookupTest {
	private static final Identifier PLAIN = Identifier.of("strut", "textures/entity/plain.png");
	private static final Identifier GLOWING = Identifier.of("strut", "textures/entity/glowing.png");

	@Test
	void eachBaseIsLookedUpOnceAndBothAnswersAreRemembered() {
		Object resources = new Object();
		AtomicInteger lookups = new AtomicInteger();
		GlowMaskLookup lookup = new GlowMaskLookup();

		for (int draw = 0; draw < 5; draw++) {
			assertFalse(lookup.hasGlowMask(resources, PLAIN, base -> {
				lookups.incrementAndGet();

				return false;
			}));
			assertTrue(lookup.hasGlowMask(resources, GLOWING, base -> {
				lookups.incrementAndGet();

				return true;
			}));
		}

		assertEquals(2, lookups.get(), "one lookup per base, however many draws");
	}

	private static final Identifier SKIN = Identifier.of("minecraft", "skins/0123456789abcdef0123456789abcdef01234567");

	@Test
	void anExtensionlessIdMeansNoGlowMaskAndIsNeverLookedUp() {
		AtomicInteger lookups = new AtomicInteger();
		GlowMaskLookup lookup = new GlowMaskLookup();

		assertFalse(lookup.hasGlowMask(new Object(), SKIN, base -> {
			lookups.incrementAndGet();

			return true;
		}));
		assertEquals(0, lookups.get());
		assertFalse(StrutAbstractTexture.hasFileExtension(SKIN));
		assertTrue(StrutAbstractTexture.hasFileExtension(PLAIN));
		assertFalse(StrutAbstractTexture.hasFileExtension(Identifier.of("strut", "textures/v1.2/skin")), "a dot in a directory is not an extension");
	}

	@Test
	void appendToPathNeverThrowsForAnExtensionlessId() {
		assertEquals(Identifier.of("minecraft", "skins/0123_glowmask"), StrutAbstractTexture.appendToPath(Identifier.of("minecraft", "skins/0123"), "_glowmask"));
		assertEquals(Identifier.of("strut", "textures/entity/plain_glowmask.png"), StrutAbstractTexture.appendToPath(PLAIN, "_glowmask"));
	}

	@Test
	void aLookupThatThrowsCountsAsNoGlowMaskAndIsRemembered() {
		AtomicInteger lookups = new AtomicInteger();
		GlowMaskLookup lookup = new GlowMaskLookup();

		for (int draw = 0; draw < 3; draw++) {
			assertFalse(lookup.hasGlowMask("token", PLAIN, base -> {
				lookups.incrementAndGet();

				throw new IllegalStateException("boom");
			}));
		}

		assertEquals(1, lookups.get(), "the failure is remembered, not retried every draw");
	}

	@Test
	void aResourceReloadForgetsTheAnswers() {
		AtomicInteger lookups = new AtomicInteger();
		GlowMaskLookup lookup = new GlowMaskLookup();
		Object before = new Object();
		Object after = new Object();

		lookup.hasGlowMask(before, PLAIN, base -> {
			lookups.incrementAndGet();

			return false;
		});

		// A glowmask added by the reload must be found
		assertTrue(lookup.hasGlowMask(after, PLAIN, base -> {
			lookups.incrementAndGet();

			return true;
		}));
		assertEquals(2, lookups.get());
	}
}
