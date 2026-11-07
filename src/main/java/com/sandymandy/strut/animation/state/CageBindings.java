package com.sandymandy.strut.animation.state;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The part of every flesh mesh's deformation that never changes: for each welded particle, which cage nodes it follows and by how much, where it sits in its
 * region's frame, and which region of its mesh owns it ({@link CageDeform#PARTICLE_WORDS} words each). Written once when a flesh is made and uploaded once to
 * the GPU, whatever the number of instances: only the nodes' own displacements change from frame to frame ({@link CageDeform})
 * <p>
 * Append-only, so a mesh's base (the word where its particles start) stays what it was; a mesh with the same key (the same model, the same flesh settings, the
 * same bone and mesh) is registered once and shared by every instance that has it
 */
public final class CageBindings {
	private static final Map<Object, Integer> BY_KEY = new HashMap<>();
	private static int[] words = new int[4096];
	private static int size;
	private static int generation;

	private CageBindings() {}

	/**
	 * The word at which {@code data} starts in the shared table, adding it unless something with the same {@code key} is there already
	 *
	 * @param key What makes two meshes' bindings the same
	 */
	public static synchronized int register(Object key, int[] data) {
		Integer known = BY_KEY.get(key);

		if (known != null)
			return known;

		if (size + data.length > words.length)
			words = java.util.Arrays.copyOf(words, Math.max(words.length * 2, size + data.length));

		System.arraycopy(data, 0, words, size, data.length);

		int base = size;

		size += data.length;
		BY_KEY.put(key, base);
		generation++;

		return base;
	}

	/** How many times something has been added: the GPU's copy is out of date when this is not what it uploaded */
	public static synchronized int generation() {
		return generation;
	}

	/** The words so far as a buffer for the upload, a few words at least */
	public static synchronized ByteBuffer snapshot() {
		ByteBuffer out = BufferUtils.createByteBuffer(Math.max(16, size * 4));

		for (int i = 0; i < size; i++)
			out.putInt(i * 4, words[i]);

		out.limit(Math.max(16, size * 4)).position(0);

		return out;
	}

	/** A word of the table, for tests */
	public static synchronized int word(int at) {
		return words[at];
	}

	/** Forgets everything: for tests */
	public static synchronized void clear() {
		BY_KEY.clear();
		size = 0;
		generation++;
	}
}
